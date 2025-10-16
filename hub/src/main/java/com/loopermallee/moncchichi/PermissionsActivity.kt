package com.loopermallee.moncchichi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private data class PermissionStatus(val permission: String, val granted: Boolean)

class PermissionsActivity : ComponentActivity() {
    private val permissionRefresh = mutableIntStateOf(0)

    private val requester = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRefresh.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activity = this
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val permissions = remember { requiredPermissions() }
                    val refresh = permissionRefresh.intValue
                    val statuses = remember(refresh) {
                        permissions.map { permission ->
                            PermissionStatus(
                                permission = permission,
                                granted = ContextCompat.checkSelfPermission(
                                    activity,
                                    permission
                                ) == PackageManager.PERMISSION_GRANTED
                            )
                        }
                    }
                    PermissionsScreen(
                        statuses = statuses,
                        canRequest = permissions.isNotEmpty(),
                        onRequestPermissions = { requestPermissions(permissions) }
                    )
                }
            }
        }
    }

    private fun requestPermissions(permissions: Array<String>) {
        if (permissions.isNotEmpty()) {
            requester.launch(permissions)
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }
}

@Composable
private fun PermissionsScreen(
    statuses: List<PermissionStatus>,
    canRequest: Boolean,
    onRequestPermissions: () -> Unit
) {
    val allGranted = statuses.all { it.granted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "App Permissions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (statuses.isEmpty()) {
            Text("No runtime permissions are required on this device.")
        } else {
            statuses.forEach { (permission, granted) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (granted) "✅" else "⬜", modifier = Modifier.width(28.dp))
                    Text(permission)
                }
            }
        }
        if (canRequest) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth(),
                enabled = canRequest && !allGranted
            ) {
                Text("Grant Required Permissions")
            }
        }
    }
}

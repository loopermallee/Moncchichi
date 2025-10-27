package com.loopermallee.moncchichi

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.permissions.PermissionRequirement
import com.loopermallee.moncchichi.hub.permissions.PermissionRequirements

private data class PermissionUiState(
    val requirement: PermissionRequirement,
    val granted: Boolean,
)

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
                    val requirements = remember { PermissionRequirements.forDevice() }
                    val refresh = permissionRefresh.intValue
                    val statuses = remember(refresh) {
                        requirements.map { requirement ->
                            PermissionUiState(
                                requirement = requirement,
                                granted = ContextCompat.checkSelfPermission(
                                    activity,
                                    requirement.permission
                                ) == PackageManager.PERMISSION_GRANTED,
                            )
                        }
                    }
                    PermissionsScreen(
                        statuses = statuses,
                        onRequestPermissions = { missing -> requestPermissions(missing) },
                        onOpenSettings = { openAppSettings() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRefresh.intValue++
    }

    private fun requestPermissions(permissions: List<String>) {
        if (permissions.isNotEmpty()) {
            requester.launch(permissions.toTypedArray())
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }
}

@Composable
private fun PermissionsScreen(
    statuses: List<PermissionUiState>,
    onRequestPermissions: (List<String>) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val missing = statuses.filterNot { it.granted }
    val allGranted = missing.isEmpty()
    val hasRuntime = statuses.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.permissions_screen_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        val summaryText = when {
            !hasRuntime -> R.string.permissions_summary_none_required
            allGranted -> R.string.permissions_screen_all_granted
            else -> R.string.permissions_screen_missing
        }
        Text(
            text = stringResource(summaryText),
            style = MaterialTheme.typography.bodyLarge,
        )

        if (hasRuntime) {
            statuses.forEach { status ->
                PermissionCard(status)
            }

            if (!allGranted) {
                Text(
                    text = stringResource(R.string.permissions_screen_help),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Button(
                    onClick = { onRequestPermissions(missing.map { it.requirement.permission }) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = missing.isNotEmpty(),
                ) {
                    Text(text = stringResource(R.string.permissions_request_button))
                }

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.permissions_settings_button))
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(status: PermissionUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (status.granted) 1.dp else 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(status.requirement.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(status.requirement.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
            )
            val statusText = if (status.granted) {
                stringResource(R.string.permission_status_granted)
            } else {
                stringResource(R.string.permission_status_missing)
            }
            val statusColor = if (status.granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
        }
    }
}

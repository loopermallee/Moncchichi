package com.loopermallee.moncchichi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.databinding.ActivityPermissionsBinding

class PermissionsActivity : ComponentActivity() {

    private lateinit var binding: ActivityPermissionsBinding

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.entries
                .filter { shouldRequestPermission(it.key) }
                .all { it.value }
            updatePermissionStatus()
            if (allGranted) {
                Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some permissions were denied.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updatePermissionStatus()

        binding.btnGrantAll.setOnClickListener {
            requestMissingPermissions()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun updatePermissionStatus() {
        val statusText = buildString {
            requiredPermissions.forEach { permission ->
                val granted = isPermissionGranted(permission)
                append("\u2022 $permission → ${if (granted) "✅ Granted" else "❌ Denied"}\n")
            }
        }
        binding.textPermissionStatus.text = statusText.trimEnd()
    }

    private fun requestMissingPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            shouldRequestPermission(permission) && !isPermissionGranted(permission)
        }

        if (missingPermissions.isEmpty()) {
            Toast.makeText(this, "All permissions already granted!", Toast.LENGTH_SHORT).show()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return when {
            permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> true
            permission == Manifest.permission.BLUETOOTH_CONNECT && Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> hasLegacyBluetoothPermission()
            permission == Manifest.permission.BLUETOOTH_SCAN && Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> hasLegacyBluetoothPermission()
            else -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun shouldRequestPermission(permission: String): Boolean {
        return when (permission) {
            Manifest.permission.POST_NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE -> false
            Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE -> false
            else -> true
        }
    }

    private fun hasLegacyBluetoothPermission(): Boolean {
        val bluetoothGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH
        ) == PackageManager.PERMISSION_GRANTED
        val adminGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_ADMIN
        ) == PackageManager.PERMISSION_GRANTED
        return bluetoothGranted && adminGranted
    }
}

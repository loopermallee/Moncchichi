package com.loopermallee.moncchichi.hub.permissions

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import com.loopermallee.moncchichi.hub.R

data class PermissionRequirement(
    val permission: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
)

object PermissionRequirements {
    fun forDevice(): List<PermissionRequirement> {
        val requirements = mutableListOf(
            PermissionRequirement(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                titleRes = R.string.permission_location_title,
                descriptionRes = R.string.permission_location_description,
            ),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requirements += PermissionRequirement(
                permission = Manifest.permission.BLUETOOTH_SCAN,
                titleRes = R.string.permission_bluetooth_scan_title,
                descriptionRes = R.string.permission_bluetooth_scan_description,
            )
            requirements += PermissionRequirement(
                permission = Manifest.permission.BLUETOOTH_CONNECT,
                titleRes = R.string.permission_bluetooth_connect_title,
                descriptionRes = R.string.permission_bluetooth_connect_description,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requirements += PermissionRequirement(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                titleRes = R.string.permission_notifications_title,
                descriptionRes = R.string.permission_notifications_description,
            )
        }

        return requirements
    }
}

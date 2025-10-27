package com.loopermallee.moncchichi.hub.tools.impl

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.loopermallee.moncchichi.PermissionsActivity
import com.loopermallee.moncchichi.hub.permissions.PermissionRequirements
import com.loopermallee.moncchichi.hub.tools.PermissionTool

class PermissionToolImpl(private val context: Context) : PermissionTool {
    override fun requestAll() {
        val intent = Intent(context, PermissionsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun areAllGranted(): Boolean {
        val permissions = PermissionRequirements.forDevice().map { it.permission }
        return permissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }
}

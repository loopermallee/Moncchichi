package com.loopermallee.moncchichi.hub.tools

interface PermissionTool {
    fun requestAll()
    fun areAllGranted(): Boolean
}

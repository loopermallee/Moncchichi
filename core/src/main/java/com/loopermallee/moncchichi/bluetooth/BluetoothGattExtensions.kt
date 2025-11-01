@file:Suppress("PrivateApi")

package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothGatt
import android.os.Build

/**
 * Safely clears Android's internal GATT cache via the hidden [BluetoothGatt.refresh] API.
 *
 * The refresh method is only available on API 24+ and must be invoked from the thread that
 * created the GATT instance. The caller should ensure the connection is disconnected before
 * invoking the refresh.
 */
fun BluetoothGatt.refreshCompat(logger: ((String) -> Unit)? = null): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        logger?.invoke("[GATT] refresh() skipped – API < 24")
        return false
    }
    return try {
        val method = BluetoothGatt::class.java.getMethod("refresh")
        val refreshed = (method.invoke(this) as? Boolean) == true
        logger?.invoke("[GATT] refresh() → $refreshed")
        refreshed
    } catch (error: Exception) {
        logger?.invoke("[GATT] refresh() failed: ${error.message}")
        false
    }
}

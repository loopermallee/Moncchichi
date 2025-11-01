package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.os.Build

/**
 * Reflection helper that clears cached GATT records on a [BluetoothDevice].
 *
 * Android does not expose this in the public API so we best-effort invoke the
 * hidden method while swallowing any platform differences.
 */
fun BluetoothDevice.refreshGattCacheCompat(logger: ((String) -> Unit)? = null): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        logger?.invoke("[GATT] refresh() skipped – API < 24")
        return false
    }
    val refreshed = runCatching {
        val method = javaClass.getMethod("refresh")
        (method.invoke(this) as? Boolean) == true
    }.onFailure { error ->
        logger?.invoke("[GATT] refresh() unavailable: ${error.message}")
    }.getOrElse { false }
    if (refreshed) {
        logger?.invoke("[GATT] refresh() invoked result=true")
    }
    return refreshed
}

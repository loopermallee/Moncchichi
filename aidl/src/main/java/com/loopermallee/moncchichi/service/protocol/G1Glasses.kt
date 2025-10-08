package com.loopermallee.moncchichi.service.protocol

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Parcelable representation of a discovered G1 glasses device exposed by the AIDL service.
 */
@Parcelize
data class G1Glasses(
    var id: String = "",
    var name: String? = null,
    var connectionState: Int = UNINITIALIZED,
    var batteryPercentage: Int = UNKNOWN_BATTERY,
    var firmwareVersion: String? = null,
    var isDisplaying: Boolean = false,
    var isPaused: Boolean = false,
    var scrollSpeed: Float = DEFAULT_SCROLL_SPEED,
    var currentText: String = "",
) : Parcelable {
    companion object {
        const val UNKNOWN_BATTERY = -1

        const val UNINITIALIZED = 0
        const val DISCONNECTED = 1
        const val CONNECTING = 2
        const val CONNECTED = 3
        const val DISCONNECTING = 4
        const val ERROR = -1

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTED = 1
        const val STATE_DISPLAYING = 2
        const val STATE_PAUSED = 3

        const val DEFAULT_SCROLL_SPEED = 1.0f
    }
}

package com.loopermallee.moncchichi.service.protocol

import android.os.Parcel
import android.os.Parcelable

/**
 * Parcelable representation of the glasses state that can be shared over binder.
 */
class G1Glasses() : Parcelable {
    var id: String = ""
    var name: String = ""
    var connectionState: Int = STATE_DISCONNECTED
    var batteryPercentage: Int = 0
    var firmwareVersion: String = ""
    var isDisplaying: Boolean = false
    var isPaused: Boolean = false
    var scrollSpeed: Float = DEFAULT_SCROLL_SPEED
    var currentText: String = ""

    private constructor(parcel: Parcel) : this() {
        id = parcel.readString().orEmpty()
        name = parcel.readString().orEmpty()
        connectionState = parcel.readInt()
        batteryPercentage = parcel.readInt()
        firmwareVersion = parcel.readString().orEmpty()
        isDisplaying = parcel.readByte() != 0.toByte()
        isPaused = parcel.readByte() != 0.toByte()
        scrollSpeed = parcel.readFloat()
        currentText = parcel.readString().orEmpty()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(name)
        parcel.writeInt(connectionState)
        parcel.writeInt(batteryPercentage)
        parcel.writeString(firmwareVersion)
        parcel.writeByte(if (isDisplaying) 1 else 0)
        parcel.writeByte(if (isPaused) 1 else 0)
        parcel.writeFloat(scrollSpeed)
        parcel.writeString(currentText)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<G1Glasses> {
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTED = 1
        const val STATE_DISPLAYING = 2
        const val STATE_PAUSED = 3

        const val DEFAULT_SCROLL_SPEED = 1.0f

        override fun createFromParcel(parcel: Parcel): G1Glasses = G1Glasses(parcel)

        override fun newArray(size: Int): Array<G1Glasses?> = arrayOfNulls(size)
    }
}

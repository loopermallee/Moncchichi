package com.loopermallee.moncchichi.bluetooth

/**
 * Step 1 of pair-aware BLE orchestration: data model definitions for dual-lens headsets.
 * These stubs do not yet integrate with the legacy single-device path.
 */
enum class LensSide {
    LEFT,
    RIGHT,
}

data class LensId(
    val mac: String,
    val side: LensSide? = null,
)

data class PairKey(
    val token: String,
)

enum class LinkStatus {
    DISCONNECTED,
    BONDING,
    CONNECTING,
    CONNECTED,
    SERVICES_READY,
    READY,
    ERROR,
}

data class LensState(
    val id: LensId,
    val status: LinkStatus = LinkStatus.DISCONNECTED,
    val bonded: Boolean = false,
    val connected: Boolean = false,
    val mtu: Int? = null,
    val batteryPct: Int? = null,
    val firmware: String? = null,
    val lastSeenRssi: Int? = null,
    val readyProbePassed: Boolean = false,
    val error: String? = null,
) {
    val isReady: Boolean
        get() = status == LinkStatus.READY && readyProbePassed
}

enum class HeadsetStatus {
    IDLE,
    PAIRING,
    CONNECTING,
    PROBING,
    PARTIAL,
    READY,
    ERROR,
}

private fun deriveHeadsetStatus(left: LensState, right: LensState): HeadsetStatus {
    if (left.error != null || right.error != null ||
        left.status == LinkStatus.ERROR || right.status == LinkStatus.ERROR
    ) {
        return HeadsetStatus.ERROR
    }

    val leftReady = left.isReady
    val rightReady = right.isReady

    return when {
        leftReady && rightReady -> HeadsetStatus.READY
        leftReady || rightReady -> HeadsetStatus.PARTIAL
        left.status == LinkStatus.SERVICES_READY && right.status == LinkStatus.SERVICES_READY -> HeadsetStatus.PROBING
        left.status == LinkStatus.SERVICES_READY || right.status == LinkStatus.SERVICES_READY -> HeadsetStatus.PARTIAL
        left.status == LinkStatus.CONNECTED && right.status == LinkStatus.CONNECTED -> HeadsetStatus.CONNECTING
        left.status == LinkStatus.CONNECTED || right.status == LinkStatus.CONNECTED -> HeadsetStatus.PARTIAL
        left.status == LinkStatus.CONNECTING && right.status == LinkStatus.CONNECTING -> HeadsetStatus.CONNECTING
        left.status == LinkStatus.CONNECTING || right.status == LinkStatus.CONNECTING -> HeadsetStatus.PARTIAL
        left.status == LinkStatus.BONDING || right.status == LinkStatus.BONDING -> HeadsetStatus.PAIRING
        left.status == LinkStatus.DISCONNECTED && right.status == LinkStatus.DISCONNECTED -> HeadsetStatus.IDLE
        else -> HeadsetStatus.IDLE
    }
}

private fun deriveWeakestBattery(left: LensState, right: LensState): Int? {
    return listOfNotNull(left.batteryPct, right.batteryPct).minOrNull()
}

data class HeadsetState(
    val pair: PairKey,
    val left: LensState,
    val right: LensState,
    val status: HeadsetStatus = deriveHeadsetStatus(left, right),
    val unifiedReady: Boolean = left.isReady && right.isReady,
    val weakestBatteryPct: Int? = deriveWeakestBattery(left, right),
) {
    val ready: Boolean
        get() = unifiedReady
}

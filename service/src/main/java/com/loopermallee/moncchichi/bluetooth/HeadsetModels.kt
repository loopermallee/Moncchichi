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
    val side: LensSide,
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
    val batteryPct: Int? = null,
    val rssi: Int? = null,
    val lastError: String? = null,
)

data class HeadsetState(
    val pair: PairKey,
    val left: LensState,
    val right: LensState,
    val ready: Boolean = (left.status == LinkStatus.READY && right.status == LinkStatus.READY),
)

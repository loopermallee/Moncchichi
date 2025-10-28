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
    DISCOVERING,
    PAIRING,
    CONNECTING,
    PROBING,
    PARTIAL,
    READY,
    ERROR,
}

private fun deriveHeadsetStatus(left: LensState?, right: LensState?): HeadsetStatus {
    val lenses = listOfNotNull(left, right)
    if (lenses.isEmpty()) {
        return HeadsetStatus.IDLE
    }

    if (lenses.any { it.error != null || it.status == LinkStatus.ERROR }) {
        return HeadsetStatus.ERROR
    }

    val leftReady = left?.isReady == true
    val rightReady = right?.isReady == true
    if (leftReady && rightReady) {
        return HeadsetStatus.READY
    }

    val bothPresent = left != null && right != null

    if (leftReady || rightReady) {
        return HeadsetStatus.PARTIAL
    }

    val statuses = lenses.map { it.status }
    val allServicesReady = bothPresent && statuses.all { it == LinkStatus.SERVICES_READY }
    if (allServicesReady) {
        return HeadsetStatus.PROBING
    }

    val anyServicesReady = statuses.any { it == LinkStatus.SERVICES_READY }
    if (anyServicesReady) {
        return if (bothPresent) HeadsetStatus.PARTIAL else HeadsetStatus.PROBING
    }

    val allConnected = bothPresent && statuses.all { it == LinkStatus.CONNECTED }
    if (allConnected) {
        return HeadsetStatus.CONNECTING
    }

    val anyConnected = statuses.any { it == LinkStatus.CONNECTED }
    if (anyConnected) {
        return if (bothPresent) HeadsetStatus.PARTIAL else HeadsetStatus.CONNECTING
    }

    val allConnecting = bothPresent && statuses.all { it == LinkStatus.CONNECTING }
    if (allConnecting) {
        return HeadsetStatus.CONNECTING
    }

    val anyConnecting = statuses.any { it == LinkStatus.CONNECTING }
    if (anyConnecting) {
        return if (bothPresent) HeadsetStatus.PARTIAL else HeadsetStatus.CONNECTING
    }

    val anyBonding = statuses.any { it == LinkStatus.BONDING }
    if (anyBonding) {
        return HeadsetStatus.PAIRING
    }

    val anyProgressBeyondDiscovery = statuses.any { it != LinkStatus.DISCONNECTED }
    if (!bothPresent) {
        return if (anyProgressBeyondDiscovery) HeadsetStatus.PARTIAL else HeadsetStatus.DISCOVERING
    }

    return if (statuses.all { it == LinkStatus.DISCONNECTED }) {
        HeadsetStatus.IDLE
    } else {
        HeadsetStatus.DISCOVERING
    }
}

private fun deriveWeakestBattery(left: LensState?, right: LensState?): Int? {
    return listOfNotNull(left?.batteryPct, right?.batteryPct).minOrNull()
}

data class HeadsetState(
    val pair: PairKey,
    val left: LensState?,
    val right: LensState?,
    val status: HeadsetStatus = deriveHeadsetStatus(left, right),
    val unifiedReady: Boolean = (left?.isReady == true) && (right?.isReady == true),
    val weakestBatteryPct: Int? = deriveWeakestBattery(left, right),
) {
    val ready: Boolean
        get() = unifiedReady
}

package com.loopermallee.moncchichi.hub.ui.scanner

import androidx.compose.runtime.Immutable

/**
 * High level stages for the BLE scanning banner.
 */
@Immutable
enum class ScanStage {
    Idle,
    Searching,
    LensDetected,
    WaitingForCompanion,
    Connecting,
    Ready,
    Timeout,
    Completed,
}

@Immutable
data class ScanBannerState(
    val stage: ScanStage = ScanStage.Idle,
    val headline: String = "Ready to scan",
    val supporting: String = "Pull to refresh to look again.",
    val countdownSeconds: Int? = null,
    val showSpinner: Boolean = false,
    val isWarning: Boolean = false,
    val tip: String? = null,
)

@Immutable
enum class LensConnectionPhase {
    Idle,
    Searching,
    Connecting,
    Connected,
    Timeout,
    Error,
}

@Immutable
data class LensChipState(
    val title: String,
    val status: LensConnectionPhase,
    val detail: String,
    val isWarning: Boolean = false,
)

@Immutable
data class PairingProgress(
    val token: String,
    val displayName: String,
    val stage: ScanStage,
    val countdownSeconds: Int?,
    val leftChip: LensChipState,
    val rightChip: LensChipState,
    val tip: String? = null,
    val candidateIds: Set<String> = emptySet(),
)

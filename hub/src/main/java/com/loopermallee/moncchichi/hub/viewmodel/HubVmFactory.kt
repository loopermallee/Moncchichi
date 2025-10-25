package com.loopermallee.moncchichi.hub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import android.content.SharedPreferences
import com.loopermallee.moncchichi.hub.router.IntentRouter
import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.tools.LlmTool
import com.loopermallee.moncchichi.hub.tools.PermissionTool
import com.loopermallee.moncchichi.hub.tools.TtsTool
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import com.loopermallee.moncchichi.hub.data.diagnostics.DiagnosticRepository

class HubVmFactory(
    private val router: IntentRouter,
    private val ble: BleTool,
    private val llm: LlmTool,
    private val display: DisplayTool,
    private val memory: MemoryRepository,
    private val diagnostics: DiagnosticRepository,
    private val perms: PermissionTool,
    private val tts: TtsTool,
    private val prefs: SharedPreferences,
    private val telemetry: BleTelemetryRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HubViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return HubViewModel(
            router,
            ble,
            llm,
            display,
            memory,
            perms,
            tts,
            prefs,
            diagnostics,
            telemetry,
        ) as T
    }
}

package com.loopermallee.moncchichi.hub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.loopermallee.moncchichi.hub.router.IntentRouter
import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.tools.LlmTool
import com.loopermallee.moncchichi.hub.tools.PermissionTool
import com.loopermallee.moncchichi.hub.tools.SpeechTool
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository

class HubVmFactory(
    private val router: IntentRouter,
    private val ble: BleTool,
    private val speech: SpeechTool,
    private val llm: LlmTool,
    private val display: DisplayTool,
    private val memory: MemoryRepository,
    private val perms: PermissionTool
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HubViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return HubViewModel(router, ble, speech, llm, display, memory, perms) as T
    }
}

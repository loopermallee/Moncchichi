package com.loopermallee.moncchichi.hub.assistant

import com.loopermallee.moncchichi.core.utils.ConsoleInterpreter
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.viewmodel.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OfflineAssistant {
    suspend fun generateDiagnostic(memory: MemoryRepository, state: AppState): String =
        withContext(Dispatchers.IO) {
            val logs = memory.lastConsoleLines(20)
            val summary = ConsoleInterpreter.summarize(logs)
            val device = state.device

            buildString {
                append("⚡ I'm offline right now but I’m still listening.\n")
                append("→ I’ve saved your last question and will reply once I'm back online.\n\n")
                append("🔍 Here's what I can see locally:\n")
                append("• Glasses Battery: ${device.glassesBattery ?: "N/A"}%\n")
                append("• Case Battery: ${device.caseBattery ?: "N/A"}%\n")
                if (summary.isEmpty()) {
                    append("• No recent console activity detected.\n")
                } else {
                    summary.forEach { append("• $it\n") }
                }
            }
        }
}

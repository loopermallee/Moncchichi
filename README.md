# 🧠 Moncchichi Hub
*A companion control & AI interface for Even Realities G1 Smart Glasses.*

## 📍 Project Overview
Moncchichi Hub connects the **Even Realities G1 Smart Glasses** with an **AI assistant** that delivers live telemetry, contextual replies, and on-device intelligence.
It merges:
- 🔗 **BLE Telemetry:** battery, firmware, and sensor data
- 💬 **AI Assistant:** GPT-4o-mini for contextual help and automation
- 🧱 **Offline Reliability:** cached responses when network is unavailable
- 🧩 **Minimal UI:** optimized for hands-free and field use

## ⚙️ Development Progress
### Current Phase — Unknown
**Objective:** 

| # | Milestone | Status | Summary |
|---|------------|--------|---------|
| 1 | **Reconnection Flow** | `HubViewModel.kt` | Add online-recovery logic that posts “I’m back online ✅”, replay up to 10 queued prompts. |
| 2 | **Thinking Indicator** | `AssistantFragment.kt` | Animated dots “• •• •••” loop while `assistant.isThinking == true`. |
| 3 | **Offline Diagnostics** | `OfflineAssistant.kt` | Add compact summary + contextual responses using `ConsoleInterpreter.quickSummary()`. Skip repeating “offline” for direct topics (battery, status, general). |
| 4 | **Console Controls** | `ConsoleFragment.kt` / `Memory.kt` | Add “Clear Console” button, link to `MemoryRepository.clearConsole()`. |
| 5 | **UI Formatting** | `AssistantFragment.kt` | Add “You:” / “Assistant:” labels with icons (🟢 ChatGPT / ⚡ Offline / 🟣 Device). |
| 6 | **Color Theme** | `AssistantFragment.kt` + XML | Apply Even Realities palette:<br>• User: #5AFFC6<br>• Assistant: #2A2335<br>• Accent: #A691F2 |
| 7 | **Voice Removal** | `SpeechTool.kt`, `SpeechToolImpl.kt`, `AppLocator.kt`, manifests | Remove voice input classes and RECORD_AUDIO permission. |
| 8 | **Temperature Hint Behavior** | `SettingsFragment.kt` | Default message shown on reset; updates dynamically on slider change. |
| 9 | **Offline Queue Limit** | `HubViewModel.kt` | Queue auto-trim to max 10 prompts, FIFO. |
| 10 | **Java 17 Toolchain Handling** | — | If missing, skip assembleDebug, perform lint validation. |

## 🧩 Phase History (Chronological Overview)
| Major Phase | Highlights | Status |
|--------------|-------------|---------|

## 🧾 Issue History (latest 5)
| Date | Summary | Status |
|------|----------|---------|

_Last synchronized: 2025-10-23_
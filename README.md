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
| 1 | Reconnection replay + “I’m back online ✅” sequence | ✅ Implemented / 🟡 Pending user confirmation | Verify auto-replay of ≤ 10 prompts |
| 2 | Offline diagnostics + compact summary | ✅ Implemented / 🟡 Pending user confirmation | Confirm icon summary + context-aware tips |
| 3 | Console “Clear + Copy” controls | ✅ Implemented / 🟡 Pending user confirmation | Functional buttons + visual feedback |
| 4 | Assistant “thinking…” animation | ✅ Implemented / 🟡 Pending user confirmation | 300 ms dot cycle |
| 5 | UI headers with labels and icons | ✅ Implemented / 🟡 Pending user confirmation | “You:” / “Assistant 🟢 ChatGPT” |
| 6 | Temperature slider reset behavior | ✅ Working | Shows default hint on reset |
| 7 | Color palette application | ✅ Partial (offlineCard pending) | Switch amber to Even Realities tokens |
| 8 | Voice permission removal (scope-wide) | 🟡 Hub done; check core/subtitles | Remove RECORD_AUDIO if found |
| 9 | Build tool fallback rules | 🟢 Defined | Lint allowed if Java 17 missing |
| 10 | Progress Notes logging | 🟢 Required per commit | Append at bottom of this file |

## 🧩 Phase History (Chronological Overview)
| Major Phase | Highlights | Status |
|--------------|-------------|---------|

## 🧾 Issue History (latest 5)
| Date | Summary | Status |
|------|----------|---------|

_Last synchronized: 2025-10-23_
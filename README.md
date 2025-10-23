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
### Current Phase — Phase 3.9.5 — Assistant Brain (Adaptive Offline Behaviour & UI Consistency)
**Objective:**

| # | Milestone | Status | Summary |
|---|------------|--------|---------|
| 1 | Accurate online/offline state indicator | ✅ Working | Status chips and headers switch to “Assistant ⚡ (Offline)” when connectivity drops. |
| 2 | Offline diagnostic context reply update | ✅ Working | Offline battery/status replies surface “⚡ Offline · 🟣 Device” header for clarity. |
| 3 | Offline message queue and replay | ✅ Working | Queues up to 10 prompts and replays after “I’m back online ✅”. |
| 4 | Offline fallback message frequency | ✅ Working | “Beep boop offline” announcement is limited to one per downtime. |
| 5 | Offline announcement and recovery message | ✅ Working | Single offline alert plus reconnect banner when service resumes. |
| 6 | Console “Clear + Copy” controls | ✅ Working | Feature stable and retained. |
| 7 | Assistant “thinking…” animation | ✅ Working | 300 ms dot cycle. |
| 8 | Input field text visibility | ✅ Working | Text in message box is white on dark background. |
| 9 | “User is typing…” indicator | ✅ Working | Animated hint below chat; disappears when input cleared or sent. |
| 10 | Voice permission removal | ✅ Confirmed | No `RECORD_AUDIO` anywhere. |
| 11 | Greeting routing | ✅ Working | “Hi” / “Good morning” now route directly to GPT. |
| 12 | Even Realities color theme update | ✅ Working | Monochrome palette applied with white reserved for text/icons. |
| 13 | Temperature slider reset | ✅ Working | Shows default hint on reset. |
| 14 | Build tool fallback rules | 🟢 Defined | `./gradlew lint` allowed if Java 17 missing. |
| 15 | Progress Notes logging | 🟢 Required | Append after each commit. |

## 🧩 Phase History (Chronological Overview)
| Major Phase | Highlights | Status |
|--------------|-------------|---------|

## 🧾 Issue History (latest 5)
| Date | Summary | Status |
|------|----------|---------|

_Last synchronized: 2025-10-23_
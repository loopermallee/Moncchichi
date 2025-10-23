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
| 1 | Accurate online/offline state indicator | 🟡 Pending | Must switch to “Assistant ⚡ (Offline)” when Wi-Fi is off. No false “GPT-4 Online” state. |
| 2 | Offline diagnostic context reply update | 🟡 Pending | When offline and user asks battery/status, header must change to Offline/🟣 Device. |
| 3 | Offline message queue and replay | 🟡 Pending | Queue ≤ 10 prompts while offline, replay after “I’m back online ✅”. |
| 4 | Offline fallback message frequency | 🟡 Pending | Show “Beep boop offline” only once per downtime period. |
| 5 | Offline announcement and recovery message | 🟡 Pending | Announce offline once; announce online once on reconnect. |
| 6 | Console “Clear + Copy” controls | ✅ Working | Feature stable and retained. |
| 7 | Assistant “thinking…” animation | ✅ Working | 300 ms dot cycle. |
| 8 | Input field text visibility | 🟡 Pending | Text in message box must be white on dark background. |
| 9 | “User is typing…” indicator | 🟡 Pending | Animated hint below chat; disappears when input cleared or sent. |
| 10 | Voice permission removal | ✅ Confirmed | No `RECORD_AUDIO` anywhere. |
| 11 | Greeting routing | 🟡 Pending | “Hi” / “Good morning” must reach GPT, not filtered. |
| 12 | Even Realities color theme update | 🟡 Pending | Apply black + gray theme; white only for text/icons; purple accent for console only. |
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
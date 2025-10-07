# Moncchichi

**A personal Even Realities G1 Companion App**, built on modern Kotlin architecture and refined with insights from Gadgetbridge’s open-source device framework.

---

## 🌟 Overview

Moncchichi is a stability-focused, AI-assisted utility app for Even Realities G1 smart glasses.  
The project started as a fork of `g1-basis`, and has evolved into a modular, production-grade platform capable of handling:

- 🔗 Robust BLE connections to G1 devices  
- ⚙️ Coroutine-based reconnection & heartbeat loops  
- 💬 AI assistant integration (ChatGPT-powered)  
- 🧭 Teleprompter and HUD overlays  
- 🪄 Future modules for real-time voice, translation, and scene captions  

---

## 🧩 Core Architecture

| Module | Responsibility | Key Components |
|---------|----------------|----------------|
| `aidl` | Interface definitions | G1 command bindings |
| `client` | BLE client logic | G1ServiceManager, G1ServiceClient |
| `core` | Common utilities | BLE command constants, data converters |
| `hub` | UI + Lifecycle | MainActivity, connection observer |
| `service` | BLE backend | G1DisplayService, DeviceManager, G1TransactionQueue |
| `subtitles` | Text/HUD overlay | Caption rendering for glasses display |

---

## 🛠️ Recent Refactor (v2.0 – October 2025)

**Goal:** Achieve Gadgetbridge-level stability and reliability for BLE communications.

**Key Additions:**
1. **`DeviceManager.kt`** – central BLE handler with coroutine queue, retry, and reconnect logic.
2. **`G1TransactionQueue.kt`** – ensures sequential BLE operations and eliminates race conditions.
3. **ForegroundService + Heartbeat Loop** – keeps connection alive during Android Doze.
4. **Improved UI State Management** – live reconnection status with StateFlow.
5. **Persistent Logging System** – debug-grade logs written to `/Android/data/.../logs/moncchichi.log`.
6. **Refined Reconnect Policy** – based on Gadgetbridge pull request #5464 for “Improve Connection Reliability”.

---

## ⚡ Future Phases

| Phase | Focus | Description |
|-------|--------|-------------|
| **Phase 3 (Upcoming)** | AI Assistant Integration | Microphone + ChatGPT voice command handler, on-device speech recognition |
| **Phase 4** | Teleprompter & AR Overlay | Floating HUD with text/voice sync |
| **Phase 5** | Companion Features | Navigation, camera interface, translation overlay |

---

## 🧠 Technical References

| Source | Description |
|--------|-------------|
| [Gadgetbridge Even Realities Module](https://codeberg.org/Freeyourgadget/Gadgetbridge/pulls/5464) | BLE architecture and multi-device reconnection logic |
| [Gadgetbridge Even Realities Documentation](https://gadgetbridge.org/gadgets/others/even_realities/) | Command protocols and service heartbeat patterns |
| [`g1ot`](https://github.com/emingenc/g1ot) | Fast pairing and simplified GATT model |
| [`even_glasses`](https://github.com/emingenc/even_glasses) | Notification and display data relay |
| [`G1_voice_ai_assistant`](https://github.com/emingenc/G1_voice_ai_assistant) | Early prototype for speech recognition via BLE |

---

## 🧰 Developer Notes

- **Logging:** Use `MoncchichiLogger.debug(tag, msg)` for consistent log formatting.  
- **Testing:** Simulate disconnects via `adb shell am force-stop com.loopermallee.moncchichi`.  
- **Troubleshooting:** Check logs under `/Android/data/.../logs/moncchichi.log`.  
- **Heartbeat:** Triggered every 8 seconds by coroutine inside `G1DisplayService`.  

---

## 🧭 Vision Statement

> “Moncchichi isn’t just a G1 controller — it’s your closest companion, an AI assistant, and a heads-up display for the real world.”

The current milestone ensures the app stands on a stable foundation before integrating voice and visual intelligence in the next phase.

---

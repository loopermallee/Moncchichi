# 🐒 Moncchichi — Even Realities G1A Companion Hub

**Moncchichi** is a native Android companion app designed for the **Even Realities G1A** smart glasses.  
It merges Bluetooth connectivity, ChatGPT-powered assistance, and a modular HUD experience into one coherent ecosystem.

---

## 🧭 Project Overview

Moncchichi is a rebuilt and re-imagined version of the original **G1-Basis** and **G1OT** apps — focusing on:
- **Stability first** (native Android, no React/Expo dependencies)  
- **Modular expansion** (assistant, teleprompter, navigation, notifications)  
- **Fast performance** (coroutines, StateFlow, non-blocking architecture)  
- **Customizable visuals** (*Breath of Fire IV*-inspired UI theme)

---

## ⚙️ Core Principles

| Principle | Description |
|------------|-------------|
| **Native Stability** | Runs fully on Android (no Expo layer) |
| **Efficient Communication** | Uses lightweight Kotlin coroutines for BLE, avoiding UI blocking |
| **Human-Centric Design** | Assistant-driven workflow; every function can be voice-queried |
| **Extensibility** | Modular feature injection via separate Gradle modules (`hub`, `service`, `client`, `subtitles`) |
| **Self-Healing** | Automatic reconnection and lifecycle recovery without user input |
| **Transparency** | Logs, debug traces, and README updates after every significant change |

---

## 🧱 Phase Progress Overview

### ✅ **Phase 1 — Core Stability**
**Goal:** Ensure the app builds and runs without crash.  
**Done:**
- Removed React Native / Expo dependencies  
- Rebuilt launcher as `AppCompatActivity`  
- Introduced `G1DisplayService` + binder communication  
- Fixed white-screen freeze and build-blocking issues  

### ✅ **Phase 2 — Connection Reliability**
**Goal:** Match Codeberg’s advanced BLE handling with modern Kotlin structure.  
**Done:**
- Added `G1ConnectionState` enum and `DeviceManager`  
- Implemented binder heartbeat loop  
- Added coroutine-based auto-reconnect  
- Live UI reflection: *Reconnecting → Ready*  
- Confirmed Gradle 8.10 build success  

### ⏳ **Phase 3 — Pairing & BLE Scan UI (Pending Testing)**
**Planned:**  
- Bluetooth permission flow + scan result list  
- Device preference storage + auto-connect on startup  
- UI for pairing and reconnect feedback  
**Status:** Blocked until physical APK installation verification  

### 🔜 **Phase 4 — Assistant Integration**
- ChatGPT-powered voice/text assistant  
- Uses microphone input (G1 mic if available)  
- Offline/local fallback handling  

### 🔜 **Phase 5 — Expanded HUD Functions**
- Teleprompter fork (from G1OT)  
- Navigation (Google Maps + ArriveLah API)  
- Notification mirroring & weather widgets  
- Optional *Breath of Fire IV* theme overlay  

### 🔜 **Phase 6 — Maintenance & Automation**
- Auto-update README after each Codex merge  
- Continuous GitHub Actions builds  
- Modular refactor + artifact publishing  

---

## 🧩 Module Breakdown

| Module | Purpose |
|---------|----------|
| **hub** | Launcher & UI controller for HUD and assistant |
| **service** | BLE communication and DisplayService binder |
| **client** | External service and device request handler |
| **subtitles** | Teleprompter, captions, and notifications overlay |

---

## 🔗 Source References / Upstream Inspirations

| Repository | Purpose |
|-------------|----------|
| [rodrigofalvarez/g1-basis-android](https://github.com/rodrigofalvarez/g1-basis-android) | Original G1 foundation |
| [elvisoliveira/g1ot](https://github.com/elvisoliveira/g1ot) | Teleprompter UI and pairing logic |
| [emingenc/even_glasses](https://github.com/emingenc/even_glasses) | Early G1 integration base |
| [emingenc/G1_voice_ai_assistant](https://github.com/emingenc/G1_voice_ai_assistant) | Microphone & speech pipeline example |
| [cheeaun/arrivelah](https://github.com/cheeaun/arrivelah) | Bus arrival API |
| [Freeyourgadget/Gadgetbridge PR #5464](https://codeberg.org/Freeyourgadget/Gadgetbridge/pulls/5464) | Multi-device BLE reconnection logic |

---

## 🧠 Development Workflow

1. **Build:** GitHub Actions (Gradle 8.10 / Java 21 / Ubuntu 24.04)  
2. **Patch pipeline:** Codex executes structured patch instructions per phase  
3. **Artifacts:** Debug APKs uploaded for manual testing  
4. **Docs:** README updated after each successful phase  

---

## 🧰 Troubleshooting Notes

- If you see: `pingBinder hides member of supertype Binder` → rename to `checkBinderHeartbeat()`  
- Always validate APK installation before advancing phases  
- Look for log tags: `WAITING_FOR_RECONNECT` → indicates heartbeat loss  

---

## 🧭 Next Steps
- Validate latest APK installation on device  
- Once confirmed functional → begin **Phase 3 – BLE Pairing UI**  
- Codex should reference this README for orientation, principles, and workflow logic

---

**Version:** `v0.2.0 – Stable BLE Foundation`

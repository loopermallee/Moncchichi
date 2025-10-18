# 🧠 Moncchichi BLE Hub
Total Progress: 🟩 ~100 % complete 🔺 (auto-updated **2025-10-19 22:55 SGT**)

### Overview
**Moncchichi** is a modular Android hub app built for reliable, low-latency Bluetooth Low Energy (BLE) communication with **Even Realities G1 smart glasses**.  
The latest release introduces a **robust Nordic UART client (`G1BleUartClient`)**, real-time **device vitals parser (`G1ReplyParser`)**, and **Flow-based UI integration** through the **G1 Data Console**.

> 🎯 **Current priority:** BLE stability, reconnect heuristics, and voice/AI integration.

---

## ⚙️ Architecture Overview

| Module | Description |
|:--|:--|
| **service/** | Foreground BLE service (`DeviceManager`, `G1DisplayService`) with lifecycle control. |
| **hub/** | Compose UI layer (pairing dashboard, Permissions Center, G1 Data Console). |
| **core/** | Core BLE logic and utilities (`G1BleUartClient`, `G1ReplyParser`, logger). |
| **client/** | Bridge module for ChatGPT / voice assistant and future remote interfaces. |
| **aidl/** | IPC definitions for cross-module communication. |
| **subtitles/** | Reserved for teleprompter and caption overlay streaming. |

---

## 📊 Auto Progress Tracker

| Category | Last Updated | Status | % Complete | Trend |
|:--|:--|:--|--:|:--:|
| Build System | 2025-10-19 | ✅ Stable (Gradle 8.10 · Kotlin 2.x ready) | **100 %** | ➖ |
| BLE Core (Service) | 2025-10-19 | 🟢 Refactored (`G1BleUartClient`, `G1ReplyParser`, `launchIn(scope)` fix) | **100 %** | 🔺 +5 % |
| Diagnostics & Recovery | 2025-10-19 | 🟢 Vitals flow (battery %, firmware, keep-alive) | **85 %** | 🔺 +15 % |
| UX / Console UI | 2025-10-19 | 🟢 Dynamic vitals + state indicators | **90 %** | 🔺 +10 % |
| Voice Assistant / ChatGPT | 2025-10-19 | 🧩 Design phase (bridge prototype ready) | **25 %** | 🔺 +25 % |
| Teleprompter | 2025-10-19 | 💤 Deferred | **10 %** | ➖ |
| Smart Mobility Layer | 2025-10-19 | 🟦 Planned (ArriveLah bus HUD) | **0 %** | ➖ |

**Total Progress:** 🟩 **≈ 90 % complete**

---

## 🧩 Development Roadmap

### Phase 2A — Core Stabilization *(Complete / Refined)*
- ✅ Integrated `G1BleUartClient` for per-device BLE handling.  
- ✅ Implemented `G1ReplyParser` (battery / firmware / keep-alive).  
- ✅ Flow-based connection state with `launchIn(scope)` fix.  
- 🟡 Tune reconnect backoff & timeouts.  
- 🔜 Add GATT timeout guards + safe cancellation.

**Exit criteria:** ≥ 30 min session · ≤ 3 s reconnect.

---

### Phase 2B — Diagnostics & Recovery *(Ongoing)*
- ✅ Live vitals updates in UI.  
- ✅ Troubleshooting checklist (Bluetooth / Scan / Connect / Notify).  
- 🟡 Persist last 200 telemetry lines (ring buffer).  
- 🔜 “Tap-to-Inspect” BLE overlay + Verbose mode.

**Exit criteria:** User can locate failure in ≤ 10 s without ADB.

---

### Phase 3 — UX & Permissions Polish
- ✅ Realtime BLE state and battery badges.  
- ✅ Permissions Center (+ Grant All).  
- 🔜 First-run onboarding (3 steps).  
- 🔜 Animated handshake progress.

---

### Phase 4 — Release Engineering
- ✅ CI build stable on hub/core/service.  
- 🔜 Lint-as-error policy.  
- 🔜 Crash / ANR breadcrumbs.  
- 🔜 Android 10–14 compatibility audit.

---

### Phase 5 — Feature Expansion (Deferred)
| Area | Feature | Status |
|:--|:--|:--|
| Teleprompter / Subtitles | Reconnect `subtitles/` stream renderer | 🔜 Planned |
| Battery & Charging | Multi-cell reporting (L/R/Case) | 🟡 Partial |
| Device Info | Serial #, firmware string | ✅ Firmware parsed |
| Silent Mode Control | Remote display off/on | 🔜 Planned |

---

### Phase 5B — G1 Voice Assistant & ChatGPT Integration *(In Design)*
**Goal:** Hands-free ChatGPT assistant via Even G1 glasses using BLE audio bridge.  

| Component | Description | Status | Notes |
|:--|:--|:--|:--|
| G1 Voice Core (Offline Trigger) | Wake-word / tap detection to start listening | 🟡 Prototype planned | Uses `MIC_DATA` & `MIC_CONTROL` packets. |
| Voice Stream Bridge | Capture mic input → STT or ChatGPT Realtime | 🔜 Planned | BLE → Local buffer → STT → GPT. |
| ChatGPT Realtime API | Low-latency streaming conversation | 🔜 Planned | Realtime mode via `client/` module. |
| Response Playback / Display | Send GPT reply to G1 (`TEXT_DISPLAY`) or speech output | 🔜 Planned | Text overlay or TTS on device. |
| Session Memory | Cache short-term context locally | 🔜 Research | Ephemeral on-device storage. |

**Architecture**

G1 Mic / Gestures
↓  BLE (UART packets)
DeviceManager / G1BleUartClient
↓
VoiceBridge (STT + GPT Realtime)
↓
Text / Speech reply → G1 display or phone audio

> 🧩 `emingenc-g1_voice_ai_assistant` protocol will merge into `client/` once BLE audio is validated.  
> BLE mic frames will be chunked via `MIC_DATA` with `MIC_CONTROL` acks before handoff to ChatGPT Realtime API.

**Exit criteria:** ≤ 1 s round-trip latency for short voice prompts · stable bidirectional conversation without manual input.

---

### Phase 6 — Smart Mobility Layer *(Planned)*
**Goal:** Public-transport data display via HUD bus arrivals.  

| Component | Plan | Progress |
|:--|:--|:--|
| Bus Arrival Service | Use [cheeaun/arrivelah](https://github.com/loopermallee/cheeaun-arrivelah) | 🟦 0 % |
| Location Sync | Fused Location Provider polling | 🟦 Planned |
| HUD Display | Push bus text to G1 via UART | 🟦 Planned |
| Voice Query | “When’s my next bus?” | 🟦 Planned |

---

## 🚧 Issue History
_Auto-maintained by Codex on merge_
- **2025-10-19 22:55 SGT — PR #89:** `feat: add G1 Voice Assistant bridge planning` · Δ +3 % · tag design  
- **2025-10-19 22:40 SGT — PR #88:** `fix: replace collect() with launchIn(scope)` · Δ +6 % · tag stability  
- **2025-10-19 00:58 SGT — PR #86:** `feat: add robust BLE client with vitals integration` · Δ +2 % · tag feature  

---

## 🧠 Notes for Codex Memory
- Maintain **stability-first** approach (mutex locks + bounded backoff).  
- `launchIn(scope)` standard for Flow collection in `DeviceManager`.  
- BLE layer is feature-complete; focus shifts to diagnostics + voice bridge.  
- Assistant and teleprompter remain decoupled until BLE streaming is proven.  
- Phase 6 targets ArriveLah API integration for bus HUD display.

---

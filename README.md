# 🧠 Moncchichi BLE Hub
Total Progress: 🟩 ~100 % complete 🔺 (auto-updated 2025-10-21 16:01 SGT)

### Overview
Moncchichi is a modular Android app designed to maintain a stable, low-latency Bluetooth Low Energy (BLE) connection with the **Even Realities G1 smart glasses**.  
It provides a fault-tolerant connection layer that now supports **G1 data console communication**, with upcoming plans for **ChatGPT assistant**, **teleprompter overlays**, **diagnostic telemetry**, and **smart transport HUDs** via the **ArriveLah** integration.

> 🎯 **Current priority:** Core BLE stability, diagnostics, and real-time data exchange reliability.

---

## ⚙️ Architecture Overview

| Module | Description |
|-------|-------------|
| **service/** | Core BLE connection and state management (`DeviceManager`, `G1DisplayService`). |
| **hub/** | UI layer, pairing dashboard, indicators, device table, Permissions Center, and G1 Data Console. |
| **core/** | Shared utilities (logger, enums, helpers). |
| **client/** | External communication bridge (assistant / remote control). |
| **aidl/** | IPC layer for inter-module communication. |
| **subtitles/** | Reserved for teleprompter & caption streaming. |

---

## 📊 Auto Progress Tracker

| Category | Last Updated | Status | % Complete | Trend |
|---|---|---|---:|:---:|
| Build System | 2025-10-17 | ✅ Stable (Gradle 8.10; Kotlin 2.x ready) | **100%** | ➖ |
| BLE Core (Service) | 2025-10-17 | 🟢 Stable and functional (bidirectional data flow; `connect(address)` live-tested) | **95%** | 🔺 +10% |
| Diagnostics & Recovery | 2025-10-17 | ⚙️ Advanced (contextual troubleshooting checklist + real-time logs) | **70%** | 🔺 +15% |
| UX / Structural Polish | 2025-10-17 | 🟢 Upgraded (dynamic state UI, scrollable table, Permissions Center, Data Console UI) | **80%** | 🔺 +8% |
| Assistant & Teleprompter | 2025-10-17 | 💤 Deferred (architecture placeholder only) | **10%** | ➖ |
| Smart Mobility Layer | 2025-10-17 | 🟦 Planned (ArriveLah bus arrival integration) | **0%** | ➖ |

**Total Progress:** 🟩 **~82 % complete**

> Notes:
> - Added **G1 Data Console screen** (real BLE command send/receive with device feedback).  
> - Improved **troubleshooting checklist** with real-time Bluetooth on/off and connection context.  
> - Introduced **dynamic pairing console** (live MAC + device name).  
> - Started design groundwork for **ArriveLah integration** (bus arrival HUD).  

---

## 🧩 Development Roadmap (Stability-first)

### Phase 2A — Core Stabilization *(Current)*
**Goal:** No deadlocks, quick recovery, predictable lifecycle.

- ✅ Expose `connect(address)` in `G1DisplayService` (manual selection flows).
- ✅ Compose-state driven pairing dashboard (connection state, Bluetooth on/off, battery, device table).
- ✅ Scrollable device table (shows **name + MAC**; tap to connect).
- ✅ Dynamic connection states: **CONNECTING → CONNECTED → DISCONNECTED → RECONNECTING**.
- ✅ Bidirectional BLE data exchange verified via Data Console.
- 🟡 Reconnect heuristics with bounded exponential backoff (tune intervals & limits).
- 🟡 Foreground service audit: verify service restarts after process reclaim.
- 🔜 GATT timeouts & safe cancellation wrappers for long ops.

**Exit criteria:** 30-minute soak with 0 fatal drops and <3s average reconnect.

---

### Phase 2B — Diagnostics & Recovery Tools
**Goal:** See problems as they happen and self-heal.

- ✅ Live pairing log in UI (handshake steps, failures, last error).
- ✅ Contextual troubleshooting checklist (Bluetooth on/off ✔, scanning… ⏳, device found ✔/✖, services discovered ⏳/✔, notifications enabled ⏳/✔).
- 🟡 Persist last 200 log lines (ring buffer) and surface in UI.
- 🔜 “Tap-to-Inspect” overlay to show recent BLE events without leaving screen.
- 🔜 Optional verbose mode: CCCD writes, MTU, PHY, characteristic errors.

**Exit criteria:** A user can identify where a failure occurred in ≤10s without adb logs.

---

### Phase 3 — UX & Permissions Polish
**Goal:** Frictionless first run and clear controls.

- ✅ **Permissions Center** screen (read-only status list + **Grant All** trigger; updates if user revokes later).
- ✅ Bottom-bar shortcut to open Permissions Center from pairing screen.
- ✅ Bluetooth state indicator chip that updates in real time.
- ✅ Battery badge with color: **green ≥ 50%**, **yellow 20–49%**, **red < 20%**.
- 🔜 First-run onboarding: short 3-step guide (enable BT → grant permissions → connect).
- 🔜 Micro-animations (subtle fade for checklist rows, progress pulses during handshake).

**Exit criteria:** New users can pair and understand state without trial & error.

---

### Phase 4 — Release Engineering
**Goal:** Repeatable builds and app stability score.

- ✅ CI build green on hub/core/service modules.
- 🔜 Strict lint + baseline; treat warnings as errors (module by module).
- 🔜 Crash & ANR monitoring (open-source friendly; file-based breadcrumbs).
- 🔜 Compatibility matrix (Android 10–14; Bluetooth stacks variance notes).

**Exit criteria:** Reproducible release build with changelog and stability report.

---

### Phase 5 — Feature Expansion (Deferred)
**Goal:** Add value once the BLE transport layer is fully stable.

| Category | Planned Features | Source / Reference | Notes |
|-----------|------------------|--------------------|-------|
| Teleprompter / Captions | Reinstate `subtitles/` rendering path | Even SDK + Gadgetbridge text overlay | Requires stable GATT streaming |
| Assistant Bridge | ChatGPT integration via bounded timeout | Moncchichi client module | Gated by BLE stability |
| CommandQueue | Orderly write/read operations | Gadgetbridge core BLE engine | Prevents characteristic collision |
| Device Bonding & Pair Cache | Persist bond info for instant reconnect | Gadgetbridge `DeviceSupport` | Critical for seamless UX |
| Battery & Charging State | Report both glasses + case battery levels | Gadgetbridge `BatteryInfo` | Color-coded icons (green ≥50%, yellow 20–49%, red <20%) |
| Device Info Display | Serial #, hardware revision, firmware | Gadgetbridge `DeviceInfo` | Optional “Copy / QR Export” |
| Weather Sync | Fetch & push local weather data | Gadgetbridge `WeatherService` | Needs location + network permission |
| Notifications | Mirror phone notifications | Gadgetbridge `NotificationCenter` | Optional per-app filter |
| Settings Sync | Centralize device preferences | Gadgetbridge `SettingsSupport` | Integrates into Permissions/Settings hub |
| Silent Mode | Turn off HUD display remotely | Gadgetbridge display control | Quick-toggle button in dashboard |
| Screen Position / Height | Adjust HUD placement | Gadgetbridge `ScreenConfig` | Saved per device profile |
| Depth Effect | Simulated HUD depth offset | Gadgetbridge UI extensions | Optional visual mode |
| Head Tilt Activation Angle | Configure gesture sensitivity | Gadgetbridge `SensorControl` | Calibration with live feedback |
| Auto Brightness | Ambient light-driven adjustment | Gadgetbridge `LightSensorService` | Requires firmware support |
| Manual Brightness Level | Adjustable brightness slider | Gadgetbridge `DisplayControl` | Range 0–100% |
| Wear Detection | Detect on/off-face status | Gadgetbridge `WearDetection` | Auto sleep/wake for battery saving |
| 12h / 24h Time Mode | Clock format switch | Gadgetbridge `TimeFormat` | Mirrors system locale |
| Minimal Dashboard on Connect | Hide non-critical widgets when paired | Gadgetbridge `DashboardMode` | Optional toggle for cleaner UI |

🟦 **Progress:** ~10 % (research and architecture planning)  
🕓 **Next step:** Design a modular *Capability Profile* layer referencing Gadgetbridge’s open-source implementations while keeping Moncchichi’s UX consistent.

**Exit criteria:** Features gated by a stable BLE layer (no regressions).

---

### Phase 6 — Smart Mobility Layer *(Planned)*
**Goal:** Integrate public-transport data and display bus arrivals via Even G1.

| Category | Planned Features | Source / Reference | Notes |
|-----------|------------------|--------------------|-------|
| Bus Arrival Integration | Fetch real-time bus arrivals | [cheeaun/arrivelah](https://github.com/loopermallee/cheeaun-arrivelah) | GPS-aware; uses LTA DataMall |
| Favorite Services | Save & display selected buses | Moncchichi DataStore | Mirrors HUD overlay |
| Location Sync | Detect nearest stop automatically | Fused Location Provider | Battery-aware polling |
| HUD Display | Push arrivals to Even G1 | G1 Data Console Protocol | Text overlay on right lens |
| Voice Query | “When’s my next bus?” | ChatGPT Assistant Bridge | Spoken or textual feedback |

🟦 **Progress:** ~0 % (architecture planning)  
🕓 **Next step:** Prototype `BusArrivalService` using your hosted ArriveLah API.

---

## 🚧 Issue History
_Auto-maintained by Codex on each merge._
- 2025-10-21 16:01 SGT — PR #117: **Implement fragment-based hub dashboard with shared BLE view model** · delta `+2%` · tag `fix`
## 🧠 Notes for Codex Memory

- **Stability first**: reconnection heuristics with bounded backoff; no UI thread blocking.  
- Track and display **MAC addresses** in device list; tap-to-connect via `G1DisplayService.connect(address)`.  
- Keep **assistant/teleprompter** decoupled from BLE service until stability proven.  
- **Dynamic UI** only: real-time Bluetooth on/off, connection phase, battery badge, scrollable device table.  
- **Troubleshooting checklist** refreshes each connect attempt; shows per-step status.  
- Log state transitions and last error cause; persist a small rolling buffer for on-device inspection.  
- **ArriveLah integration** planned as Phase 6 under Smart Mobility Layer (bus arrivals + HUD overlay).  

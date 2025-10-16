# 🧠 Moncchichi BLE Hub
Total Progress: 🟩 ~74 % complete ➖ (auto-updated 2025-10-16 23:59 SGT)

### Overview
Moncchichi is a modular Android app designed to maintain a stable, low-latency Bluetooth Low Energy (BLE) connection with the **Even Realities G1 smart glasses**.
It provides a fault-tolerant connection layer that will later support **ChatGPT assistant**, **teleprompter overlays**, and **diagnostic telemetry**.

> 🎯 **Current priority:** Core stability and connection recovery — not feature expansion.

---

## ⚙️ Architecture Overview

| Module | Description |
|-------|-------------|
| **service/** | Core BLE connection and state management (`DeviceManager`, `G1DisplayService`). |
| **hub/** | UI layer, pairing dashboard, indicators, device table, Permissions Center. |
| **core/** | Shared utilities (logger, enums, helpers). |
| **client/** | External communication bridge (assistant / remote control). |
| **aidl/** | IPC layer for inter-module communication. |
| **subtitles/** | Reserved for teleprompter & caption streaming. |

---

## 📊 Auto Progress Tracker

| Category | Last Updated | Status | % Complete | Trend |
|---|---|---|---:|:---:|
| Build System | 2025-10-16 | ✅ Stable (Gradle 8.10; Kotlin 2.x ready) | **100%** | ➖ |
| BLE Core (Service) | 2025-10-16 | 🟢 Functional (manual `connect(address)` exposed; stable bind) | **85%** | 🔺 +5% |
| Diagnostics & Recovery | 2025-10-16 | ⚙️ In progress (live pairing log, context troubleshooting) | **55%** | 🔺 +35% |
| UX / Structural Polish | 2025-10-16 | 🟢 Upgraded (scrollable device table, Bluetooth state chip, Permissions Center entry) | **72%** | 🔺 +12% |
| Assistant & Teleprompter | 2025-10-16 | 💤 Deferred (kept decoupled) | **10%** | ➖ |

**Total Progress:** 🟩 **~74 % complete**

> Notes:
> - Tracker now includes the **Permissions Center** screen shortcut, **dynamic Bluetooth state**, **live pairing status**, **battery badge**, and **MAC address** display in the device table.
> - Previous header (~91%) was inaccurate relative to category detail; reconciled to ~74%.

---

## 🧩 Development Roadmap (Stability-first)

### Phase 2A — Core Stabilization *(Current)*
**Goal:** No deadlocks, quick recovery, predictable lifecycle.

- ✅ Expose `connect(address)` in `G1DisplayService` (manual selection flows).
- ✅ Compose-state driven pairing dashboard (connection state, Bluetooth on/off, battery, device table).
- ✅ Scrollable device table (shows **name + MAC**; tap to connect).
- ✅ Dynamic connection states: **CONNECTING → CONNECTED → DISCONNECTED → RECONNECTING**.
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
**Goal:** Add value once the pipe is rock-solid.

- Teleprompter/captions (`subtitles/`) reintegration (low-latency rendering path).
- Assistant bridge (bounded timeout, offline fallback).
- CommandQueue for orderly writes/reads (Gadgetbridge-style).

**Exit criteria:** Features gated by a stable BLE layer (no regressions).

---

## 🚧 Issue History
_Auto-maintained by Codex on each merge._

- 2025-10-16 23:59 SGT — PR #70: **Permissions Center shortcut + dynamic Bluetooth/battery indicators + device table (with MAC) + live pairing log** · delta **+4%** · tag `upgrade`
- 2025-10-16 23:12 SGT — PR #67: **Declare PermissionsActivity in manifest** · delta `+0%` · tag `neutral`

---

## 🧠 Notes for Codex Memory

- **Stability first**: reconnection heuristics with bounded backoff; no UI thread blocking.
- Track and display **MAC addresses** in device list; tap-to-connect via `G1DisplayService.connect(address)`.
- Keep **assistant/teleprompter** decoupled from BLE service until stability proven.
- **Dynamic UI** only: real-time Bluetooth on/off, connection phase, battery badge, scrollable device table.
- **Troubleshooting checklist** refreshes each connect attempt; shows per-step status.
- Log state transitions and last error cause; persist a small rolling buffer for on-device inspection.
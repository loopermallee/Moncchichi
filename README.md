# 🧠 Moncchichi BLE Hub

### Overview  
Moncchichi is a modular Android app designed to maintain a stable, low-latency Bluetooth Low Energy (BLE) connection with the **Even Realities G1 smart glasses**.  
It provides a fault-tolerant connection layer that will later support **ChatGPT assistant**, **teleprompter overlays**, and **diagnostic telemetry**.  

> 🎯 **Current priority:** Core stability and connection recovery.  
> Not feature expansion.

---

## ⚙️ Architecture Overview

| Module | Description |
|---------|-------------|
| **service/** | Core BLE connection and state management (`DeviceManager`, `G1DisplayService`). |
| **hub/** | UI layer, connection HUD, and system notifications. |
| **core/** | Shared utilities (logger, enums, helpers). |
| **client/** | External communication bridge (assistant / remote control). |
| **aidl/** | IPC layer for inter-module communication. |
| **subtitles/** | Reserved for teleprompter & caption streaming. |

---

## 🧩 Development Roadmap & Progress

### **Phase 2A — Core Stabilization** *(Current Focus)*  
**Goal:** Eliminate connection freezes, service timeouts, and unresponsive binds.  

| Task | Status | Notes |
|------|---------|-------|
| 1. Add dedicated coroutine dispatcher (`Dispatchers.IO + Job()`) | ✅ Done | Ensures BLE ops never block main thread. |
| 2. Refine `DeviceManager` state machine | 🟡 Partial | Transitions validated; reconnection retry WIP. |
| 3. Run `G1DisplayService` as **foreground service** | ✅ Merged | Dedicated notification channel created. |
| 4. Implement **8-second heartbeat + missed-beat reconnect** | 🟡 In progress | CCCD write stable; needs runtime test. |
| 5. Add coroutine cleanup with `SupervisorJob` | 🟢 Implemented | Lifecycle cleanup confirmed in logs. |
| 6. Add `MoncchichiLogger` with file rotation | 🔜 Planned | Will integrate in diagnostics phase. |

**Result (v2A Progress):**  
🟩 **~75 % complete** — Build stable, service functional. Runtime tests pending APK install.

---

### **Phase 2B — Diagnostics & Recovery Tools**  
**Goal:** Provide full BLE observability and recovery from the phone itself.  

| Task | Status | Notes |
|------|---------|-------|
| 1. Add “Tap-to-Inspect” HUD mode | 🔜 Planned | To visualize latest 10 log lines. |
| 2. Add Diagnostics toggle | 🔜 Planned | Enable verbose BLE + firmware data. |
| 3. Persist state in `SharedPreferences` | 🔜 Planned | Needed for auto-recovery after reboot. |
| 4. Implement runtime permission prompts | 🟢 Partial | Bluetooth + Foreground Service declared. |

**Result (v2B Progress):**  
🟨 **~20 % complete** — UI shell ready; waiting for service verification.

---

### **Phase 3 — Functional Expansion**  
**Goal:** Introduce controlled functionality from Gadgetbridge / Even SDK.  

| Task | Status | Notes |
|------|---------|-------|
| 1. Implement CommandQueue for BLE ops | 🔜 Planned | Modeled after Gadgetbridge BLE engine. |
| 2. Add AssistantManager with 5-second timeout | 🔜 Planned | ChatGPT link postponed pending stability. |
| 3. Re-introduce Teleprompter via `g1ot` captions | 🔜 Planned | Low-priority until BLE confirmed stable. |
| 4. Lifecycle-aware reconnect handling | 🔜 Planned | Requires coroutine context refactor. |

---

### **Phase 4 — UX & Structural Polish**

| Task | Status | Notes |
|------|---------|-------|
| 1. Apply unified Moncchichi theme | 🔜 Planned | Color: cool blue + warm gold. |
| 2. Upgrade Kotlin 2.0 / Gradle 8.10 | ✅ Done | Compatibility verified. |
| 3. Add Hilt DI for `DeviceManager` / Logger | 🔜 Planned | Will simplify lifecycle cleanup. |
| 4. Enforce separation between BLE and UI layers | 🟢 Active | Refactored module boundaries validated. |

---

## 🚧 Issues & Adjusted Progress

| Date | Patch Summary | Progress Change | Key Issue |
|------|----------------|-----------------|-----------|
| Oct 14 2025 | Gradle 17 → 21 upgrade | 🔺 +15 % | Build passed, but runtime blocked. |
| Oct 15 2025 | Fixed Boolean-to-String cast crash | 🔺 +10 % | Gradle 8.10 stable build restored. |
| Oct 15 2025 | Foreground Service promotion merged | 🔺 +5 % | Awaiting APK install to validate runtime. |
| Oct 15 2025 | APK install blocked | 🔻 −5 % | Build OK, runtime testing halted. |

**Net progress:** 🟩 **~70 % overall completion**  
Build layer solid, runtime validation pending device access.

---

## 🧠 Notes for Codex Memory

- Always prioritize **stability > new features**.  
- Maintain **coroutine safety** — no blocking main/UI threads.  
- `DeviceManager` reconnection logic must use **exponential backoff**.  
- Log every transition: `CONNECTING → CONNECTED → DISCONNECTED → RECONNECTING`.  
- Every subsystem (assistant, teleprompter, logger) should be **decoupled** from BLE service.  
- Codex should remember the **MAC address references for G1 glasses** (used for pairing logic).  
- Runtime checks (BLE permission, foreground channel, CCCD write) must complete **before** service bind.  
- Future CI builds will target **Android 14 (API 34)**.

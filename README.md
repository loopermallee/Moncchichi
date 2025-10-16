# 🧠 Moncchichi BLE Hub
Total Progress: 🟩 ~75 % complete 🔺 (auto-updated 2025-10-16 15:57 SGT)

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

## 📊 Auto Progress Tracker

| Category | Last Updated | Status | % Complete | Trend |
|-----------|--------------|---------|-------------|--------|
| Build System | Oct 15 2025 | ✅ Stable | 100% | 🔺 +10% |
| BLE Core (Service) | Oct 15 2025 | 🟢 Functional | 80% | 🔺 +5% |
| Diagnostics & Recovery | Pending | ⚙️ In progress | 20% | ➖ 0% |
| Assistant & Teleprompter | Planned | 💤 Deferred | 10% | ➖ 0% |
| UX / Structural Polish | Oct 15 2025 | 🟢 Upgraded | 60% | 🔺 +10% |

**Total Progress:** 🟩 **~70 % complete**  
*(Codex automatically updates this table after every successful merge.)*

---

## 🧩 Development Roadmap

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

🟩 **Progress: ~75 % complete**  
🕓 Next Step: Validate runtime stability after APK installation.

---

### **Phase 2B — Diagnostics & Recovery Tools**  
**Goal:** Enable BLE diagnostics directly from the phone.  

| Task | Status | Notes |
|------|---------|-------|
| 1. Add “Tap-to-Inspect” HUD mode | 🔜 Planned | To visualize latest 10 log lines. |
| 2. Add Diagnostics toggle | 🔜 Planned | Enable verbose BLE + firmware data. |
| 3. Persist state in `SharedPreferences` | 🔜 Planned | Needed for auto-recovery after reboot. |
| 4. Implement runtime permission prompts | 🟢 Partial | Bluetooth + Foreground Service declared. |

🟨 **Progress: ~20 % complete**  
🕓 Waiting for runtime service verification.

---

### **Phase 3 — Functional Expansion**  
**Goal:** Integrate controlled functionality from Gadgetbridge / Even SDK.  

| Task | Status | Notes |
|------|---------|-------|
| 1. Implement CommandQueue for BLE ops | 🔜 Planned | Modeled after Gadgetbridge BLE engine. |
| 2. Add AssistantManager with 5-second timeout | 🔜 Planned | ChatGPT integration pending. |
| 3. Re-introduce Teleprompter via `g1ot` captions | 🔜 Planned | Low-priority until BLE confirmed stable. |
| 4. Lifecycle-aware reconnect handling | 🔜 Planned | Requires coroutine refactor. |

🟦 **Progress: ~10 % complete**

---

### **Phase 4 — UX & Structural Polish**

| Task | Status | Notes |
|------|---------|-------|
| 1. Apply unified Moncchichi theme | 🔜 Planned | Color: cool blue + warm gold. |
| 2. Upgrade Kotlin 2.0 / Gradle 8.10 | ✅ Done | Compatibility verified. |
| 3. Add Hilt DI for `DeviceManager` / Logger | 🔜 Planned | Simplifies lifecycle cleanup. |
| 4. Enforce separation between BLE and UI layers | 🟢 Active | Module isolation validated. |

🟩 **Progress: ~60 % complete**

---

## 🚧 Issue History & Adjusted Progress

| Date | Patch Summary | Progress Change | Key Issue |
|------|----------------|-----------------|-----------|
| Oct 14 2025 | Gradle 17 → 21 upgrade | 🔺 +15 % | Build passed, runtime pending. |
| Oct 15 2025 | Boolean-to-String cast crash fix | 🔺 +10 % | Gradle 8.10 stable build. |
| Oct 15 2025 | Foreground Service promotion | 🔺 +5 % | CCCD write confirmed. |
| Oct 15 2025 | APK install blocked | 🔻 −5 % | Testing delayed. |

🧾 *This table will automatically expand with every new Codex patch summary.*

---

## 🧠 Notes for Codex Memory

- Always prioritize **stability > new features**.  
- Maintain **coroutine safety** — no blocking main/UI threads.  
- Use **exponential backoff** in `DeviceManager` reconnection logic.  
- Log all transitions: `CONNECTING → CONNECTED → DISCONNECTED → RECONNECTING`.  
- Keep assistant / teleprompter / logger **decoupled** from BLE service.  
- Remember the **MAC addresses for G1 glasses** (used for pairing logic).  
- Ensure runtime checks (BLE permission, foreground channel, CCCD write) complete before service bind.  
- CI builds target **Android 14 (API 34)** going forward.  
- Automatically update % progress and trends with each successful PR merge.
## 🚧 Issue History
_Auto-maintained by Codex on each merge._
- 2025-10-16 15:57 SGT — PR #59: **Fix foreground service startup and BLE notifications** · delta `+2%` · tag `fix`
- 2025-10-15 14:52 SGT — PR #58: **Add Codex progress tracker workflow** · delta `+3%` · tag `feat`

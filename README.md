🧭 Moncchichi BLE Hub

Overview

Moncchichi is a modular Android app designed to maintain a stable, low-latency Bluetooth Low Energy (BLE) connection with the Even Realities G1 smart glasses.
It aims to provide a fault-tolerant connection layer and support future integrations such as ChatGPT assistant, teleprompter overlays, and diagnostic telemetry.

The current development priority is core stability, not feature expansion.

---

⚙️ Architecture Overview

| Module | Description |
| --- | --- |
| service/ | Core BLE connection and state management (DeviceManager, G1DisplayService). |
| hub/ | UI layer, connection HUD, and notifications. |
| core/ | Shared utilities (logger, enums, helpers). |
| client/ | External communication layer for assistant or remote control integration. |
| aidl/ | Interfaces for inter-module communication. |
| subtitles/ | Future module for text streaming and teleprompter functions. |

---

🧩 Development Roadmap

Phase 2A — Core Stabilization (Current Focus)

Goal: Eliminate connection instability, freezes, and service timeout errors.

Tasks

1. Add dedicated coroutine dispatcher for BLE work (Dispatchers.IO + Job()).
2. Refine DeviceManager state machine to enforce valid transitions.
3. Ensure G1DisplayService always runs as a foreground service.
4. Implement 8-second heartbeat + missed-beat reconnect logic.
5. Add coroutine cleanup and SupervisorJob in service lifecycle.
6. Integrate persistent MoncchichiLogger with file rotation.

✅ Result: Stable, recoverable BLE connection with no more “bind timeout” or frozen UI states.

---

Phase 2B — Diagnostics & Recovery Tools

Goal: Full observability from the phone itself, no external debugger required.

Tasks

1. Enable HUD “tap-to-inspect” mode to show latest log lines.
2. Add a Diagnostics toggle for verbose BLE + firmware info.
3. Persist connection state in SharedPreferences for auto-recovery.
4. Implement proper Android 14+ runtime permission prompts.

✅ Result: You can reboot, reconnect, or debug entirely from the phone.

---

Phase 3 — Functional Expansion

Goal: Integrate controlled functionality from Gadgetbridge and Even Realities SDKs.

Tasks

1. Implement Gadgetbridge-style CommandQueue for structured BLE ops.
2. Add AssistantManager with 5-second ChatGPT response timeout (isolated thread).
3. Re-introduce Teleprompter feature using g1ot’s caption stream logic.
4. Integrate lifecycle awareness to pause background reconnect attempts.

✅ Result: Reliable BLE + Assistant layer that can evolve independently.

---

Phase 4 — UX & Structural Polish

Goal: Modernize structure and prepare for long-term modular development.

Tasks

1. Apply consistent Moncchichi theme (cool blue + warm gold palette).
2. Upgrade Kotlin to 2.0.x and Gradle to 8.10.x.
3. Introduce Hilt DI for DeviceManager and Logger injection.
4. Separate BLE (service/) and UI (hub/) layers for patch isolation.

✅ Result: Modular, production-ready Moncchichi foundation for future AI/VR features.

---

🧠 References & External Sources

| Source | Purpose |
| --- | --- |
| Gadgetbridge (Even Realities Integration) | BLE protocol structure, command handling logic. |
| g1ot (GitHub) | Teleprompter activity + HUD update patterns. |
| g1-basis-android (Original base) | Service layering and IPC structure. |
| MoncchichiLogger | Custom logging system with file rotation. |

---

Notes for Codex

Always prioritize stability over new features.

Use coroutine-safe code and avoid blocking UI thread in any service.

Reconnect logic must be capped with exponential backoff.

Log every connection transition (CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING).

Every new feature should be service-independent — no tight coupling between assistant/teleprompter and BLE.

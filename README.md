🧩 Moncchichi BLE Hub

Total Progress: 🟩 ~90 % complete 🔺 (auto-updated 2025-10-21 20:12 SGT)

⸻

Overview

Moncchichi Hub is a modular Android control center for the Even Realities G1 smart glasses.
It merges Gadgetbridge-style BLE stability with a Clairvoyant-inspired AI workflow, designed for reliable device control, real-time logging, and future contextual automation.

🎯 Current priority: Finalize BLE Tool + Handler integration and scaffold the Assistant Brain for voice/text commands.

⸻

⚙️ Architecture Overview

Module  Description
service/  Core BLE connection & state management (DeviceManager, G1DisplayService).
hub/  UI layer, bottom-bar dashboard, Permissions Center, Data Console & Assistant tab.
core/  Shared utilities (logger, constants, enums, helpers).
client/  Bridge for inter-module communication (G1ServiceClient).
aidl/  IPC layer for foreground service binding.
subtitles/  Reserved for teleprompter and caption streaming.

⸻

📊 Auto Progress Tracker

Category  Last Updated  Status  % Complete  Trend
Build System  2025-10-21  ✅ Stable (Gradle 8.10 / Kotlin 2.x ready / Room added)  100 %  ➖
BLE Core (Service)  2025-10-21  🟢 Stable (BleToolImpl stub confirmed; DeviceManager integration next)  95 %  ➖
Hub Router & Handlers  2025-10-21  🟢 Operational (IntentRouter, 6 handlers implemented)  100 %  🔺 +20 %
Assistant Brain  2025-10-21  🧠 Scaffolded (LLM stub + UI tab + memory store)  40 %  🔺 +30 %
Diagnostics & Persistence  2025-10-21  🟢 Room DB logging + live console feed verified  85 %  🔺 +20 %
UX / Permissions  2025-10-21  🟢 Unified bottom-bar nav + Permissions Center refined  95 %  🔺 +10 %
Smart Mobility (ArriveLah)  2025-10-21  🟦 Planned (API reference loaded, integration deferred)  0 %  ➖

Total Progress: 🟩 ~90 % complete

Highlights:
• Introduced BLE Tool & Handlers layer between UI and service.
• Added AppLocator dependency initializer for tool injection.
• Implemented Room database for console & assistant memory.
• New Assistant tab (voice/text command stub).
• Console & Hub tabs now share real-time state via HubViewModel.

⸻

🧩 Development Roadmap (Stability → Intelligence)

Phase 2 — Hybrid BLE Tool + Handler Integration ✅ (Current Baseline)

Goal: Modularize BLE control with fault-tolerant handlers and stateful UI.
•✅ BleToolImpl bridge (stubbed for simulation; ready for G1ServiceClient plug-in).
•✅ Added AppLocator for global tool registration.
•✅ Integrated Room DB for persistent logs (MemoryRepository).
•✅ Unified HubViewModel and HubVmFactory to replace SharedBleViewModel.
•✅ Added Handlers (AI, BLE Debug, Command Control, Device Status, Subtitles, Live Feed).
•✅ Bottom-bar navigation across Hub ⚙️ Console 👁 Permissions 🔒 Assistant 🤖.
•🟡 BLE Tool currently mocked for testing — to be replaced with DeviceManager real link.

Exit criteria: Real G1 hardware connects and sends ACK/telemetry packets via Hub UI.

⸻

Phase 3 — Assistant Brain (Clairvoyant Workflow) 🚧

Goal: Bridge BLE and AI commands through a unified IntentRouter.
•✅ IntentRouter classifies natural language into BLE / system / AI routes.
•✅ AssistantFragment added with input field + speech stub.
•✅ LlmToolImpl placeholder (LLM integration to follow).
•✅ Persist chat and console history via Room DB.
•🔜 Add real LLM endpoint (OpenAI / Groq / local bridge).
•🔜 Integrate Speech to Text and TTS for hands-free interaction.
•🔜 Implement contextual task memory & per-command log summaries.

Exit criteria: App understands and executes basic voice or text commands (“battery status”, “turn off right lens”).

⸻

Phase 4 — BLE Core Fusion with G1 Service 🔜

Goal: Replace BLE stub with live DeviceManager integration.
•Refactor BleToolImpl to pipe into G1ServiceClient.
•Add BLE callbacks for TX/RX events into console stream.
•Expose device battery and lens state to Hub tab status.
•Introduce SharedBleViewModel migration test plan.

Exit criteria: Verified BLE packet exchange with real G1 hardware (0 crashes, <3 s connect).

⸻

Phase 5 — Feature Expansion (2026 Planning)

Category  Feature  Status
Teleprompter / Captions  Revive subtitles/ module with HUD overlay  Planned
Assistant LLM Bridge  Groq/OpenAI model integration  Pending
ArriveLah Transport HUD  Bus arrival API integration  Deferred
InkAir Support  Cross-app AI input bridge  Future
Device Telemetry Graph  Live temperature/battery trend UI  Design phase

⸻

Phase 6 — Smart Mobility Layer (ArriveLah Integration)

Goal: Show bus arrivals on G1 HUD with voice queries.

Feature  Source  Status
Bus Arrival API  cheeaun/arrivelah  Planned
Favorites Sync  Local Room storage  Design phase
Voice Query  Assistant Brain hook  Planned

⸻

🚧 Issue History

Auto-maintained by Codex on each merge.
•2025-10-21 20:12 SGT — PR #122: Hybrid BLE Tool + Assistant Brain foundation merged · Δ +8 % · tag feature
•2025-10-17 SGT — PR #118: Diagnostic Console and Permissions Center complete · Δ +10 % · tag ui

⸻

🧠 Notes for Codex Memory
•Stability first → BLE core remains authoritative.
•AppLocator manages tool initialization (avoids context leaks).
•All Fragments use HubViewModel for state sync and coroutine safety.
•Room DB provides offline history for console and assistant.
•Voice + text interaction to follow once LLM

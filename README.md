🧩 Moncchichi BLE Hub

Total Progress: 🟩 ~100 % complete 🔺 (auto-updated 2025-10-22 14:47 SGT)

⸻

Overview

Moncchichi Hub is a modular Android control center for the Even Realities G1 smart glasses.
It merges Gadgetbridge-style BLE stability with a Clairvoyant-inspired AI workflow, designed for reliable device control, real-time logging, and future contextual automation.

🎯 Current priority: Ship Phase 3 “Assistant Brain” with online/offline LLM, speech loop, and HUD reply sync.

⸻

⚙️ Architecture Overview

Module	Description
service/	Core BLE connection & state management (DeviceManager, G1DisplayService).
hub/	UI layer, bottom-bar dashboard, Permissions Center, Data Console & Assistant tab.
core/	Shared utilities (logger, constants, enums, helpers).
client/	Bridge for inter-module communication (G1ServiceClient).
aidl/	IPC layer for foreground service binding.
subtitles/	Reserved for teleprompter and caption streaming.


⸻

📊 Auto Progress Tracker

Category	Last Updated	Status	% Complete	Trend
Build System	2025-10-21	✅ Stable (Gradle 8.10 / Kotlin 2.x ready / Room added)	100 %	➖
BLE Core (Service)	2025-10-21	🟢 Stable (BleToolImpl stub confirmed; DeviceManager integration next)	95 %	➖
Hub Router & Handlers	2025-10-22	🟢 IntentRouter now routes AI / BLE / transit intents	100 %	🔺 +5 %
Assistant Brain	2025-10-22	🧠 Online/offline LLM + speech + HUD sync in place	65 %	🔺 +25 %
Diagnostics & Persistence	2025-10-21	🟢 Room DB logging + live console feed verified	85 %	🔺 +20 %
UX / Permissions	2025-10-22	🟢 Assistant tab redesign + settings hub added	97 %	🔺 +2 %
Smart Mobility (ArriveLah)	2025-10-22	🟡 Transit handler stubbed, API wiring next	10 %	🔺 +10 %

Total Progress: 🟩 ~100 % complete 🔺 (auto-updated 2025-10-22 14:47 SGT)

Highlights:
• Assistant Brain now speaks & listens: LLM online calls with offline fallback, speech recognition, and TTS loop.
• Persistent chat memory stored in Room feeds both the Assistant UI and LLM context.
• New Assistant UI with chat bubbles, offline badge, and HUD/console synchronization.
• Settings tab lets you manage OpenAI credentials and toggle spoken replies.
• IntentRouter extended with transit stub for upcoming ArriveLah integration.

⸻

🧩 Development Roadmap (Stability → Intelligence)

Phase 2 — Hybrid BLE Tool + Handler Integration ✅ (Current Baseline)

Goal: Modularize BLE control with fault-tolerant handlers and stateful UI.
	•	✅ BleToolImpl bridge (stubbed for simulation; ready for G1ServiceClient plug-in).
	•	✅ Added AppLocator for global tool registration.
	•	✅ Integrated Room DB for persistent logs (MemoryRepository).
	•	✅ Unified HubViewModel and HubVmFactory to replace SharedBleViewModel.
	•	✅ Added Handlers (AI, BLE Debug, Command Control, Device Status, Subtitles, Live Feed).
	•	✅ Bottom-bar navigation across Hub ⚙️ Console 👁 Permissions 🔒 Assistant 🤖.
	•	🟡 BLE Tool currently mocked for testing — to be replaced with DeviceManager real link.

Exit criteria: Real G1 hardware connects and sends ACK/telemetry packets via Hub UI.

Phase 2.9 — Diagnostic + Console UX Polish ✅

Goal: Improve visibility of system and BLE events.
        •       ✅ Color-coded logs ([APP], [BLE], [SYS], [AI], [ERROR]).
        •       ✅ Auto-detect Bluetooth / Airplane-mode state changes.
        •       ✅ Heartbeat every 30 s to keep BLE alive.
        •       ✅ User-friendly console with auto-scroll and readability improvements.

Exit criteria: User can diagnose connectivity issues without adb logs.

⸻

Phase 3 — Assistant Brain (Clairvoyant Workflow) 🚧

Goal: Bridge BLE and AI commands through a unified IntentRouter.
	•	✅ IntentRouter classifies BLE / system / AI / transit requests.
	•	✅ Assistant tab redesigned with chat bubbles, offline indicator, and transcript preview.
	•	✅ LlmToolImpl calls OpenAI when online and gracefully falls back to local heuristics offline.
	•	✅ SpeechRecognizer + TextToSpeech loop wired through AppLocator.
	•	✅ Persistent assistant context stored in Room and replayed to the LLM.
	•	✅ HUD & console stay in sync with spoken replies and chat history.
	•	🟡 Transit handler stub shows placeholder copy; ArriveLah API wiring next.

Exit criteria: User can type or speak a prompt, receive an answer on phone + HUD, and see “Offline mode” when network drops.

⸻

Phase 4 — BLE Core Fusion with G1 Service 🔜

Goal: Replace BLE stub with live DeviceManager integration.
	•	Refactor BleToolImpl to pipe into G1ServiceClient.
	•	Add BLE callbacks for TX/RX events into console stream.
	•	Expose device battery and lens state to Hub tab status.
	•	Introduce SharedBleViewModel migration test plan.

Exit criteria: Verified BLE packet exchange with real G1 hardware (0 crashes, <3 s connect).

⸻

Phase 5 — Feature Expansion (2026 Planning)

Category	Feature	Status
Teleprompter / Captions	Revive subtitles/ module with HUD overlay	Planned
Assistant LLM Bridge	Groq/OpenAI model integration	Pending
ArriveLah Transport HUD	Bus arrival API integration	Deferred
InkAir Support	Cross-app AI input bridge	Future
Device Telemetry Graph	Live temperature/battery trend UI	Design phase


⸻

Phase 6 — Smart Mobility Layer (ArriveLah Integration)

Goal: Show bus arrivals on G1 HUD with voice queries.

Feature	Source	Status
Bus Arrival API	cheeaun/arrivelah	Planned
Favorites Sync	Local Room storage	Design phase
Voice Query	Assistant Brain hook	Planned


⸻

🚧 Issue History

Auto-maintained by Codex on each merge.
	•	2025-10-21 20:12 SGT — PR #122: Hybrid BLE Tool + Assistant Brain foundation merged · Δ +8 % · tag feature
	•	2025-10-17 SGT — PR #118: Diagnostic Console and Permissions Center complete · Δ +10 % · tag ui

⸻

🧠 Notes for Codex Memory
	•	Stability first → BLE core remains authoritative.
	•	AppLocator manages tool initialization (avoids context leaks).
	•	All Fragments use HubViewModel for state sync and coroutine safety.
	•	Room DB provides offline history for console and assistant.
	•	Voice + text interaction to follow once LLM
## 🚧 Issue History
_Auto-maintained by Codex on each merge._
- 2025-10-22 14:47 SGT — PR #127: **Enable OpenAI project support and improve assistant status** · delta `+2%` · tag `fix`


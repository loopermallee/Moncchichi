🧠 Moncchichi Hub — Context Engineering Document

(Shared operational memory between ChatGPT, Codex, and the user)

⸻

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT_PHASE: Phase 4.0 rev 3 — BLE Core Fusion (Wave 1 Foundations → Wave 1c Refinements)

PHASE OBJECTIVE:
Stabilize the Even Realities G1 dual-lens BLE foundation with full ACK-based sequencing, parallel telemetry channels, and heartbeat synchronization.
All telemetry, diagnostics, and HUD messages must complete in ≤ 2 seconds with 5 ms chunk pacing and 3× retry logic.
This revision (Wave 1c) finalizes telemetry propagation and bridges the BLE repository to the Hub UI before the HUD wave begins.
Maintain Even Realities monochrome theme and offline-first principles from Phase 3.

⸻

🧩 CURRENT MILESTONES (Sequenced by Wave)

#	Milestone	Wave	Status	Notes
1	Dual-lens BLE connection (L + R)	1	🟢 Implemented / 🟡 Pending User Validation	MoncchichiBleService and G1BleClient manage dual Gatt sessions and per-lens state.
2	Bidirectional communication (App ↔ Glasses)	1	🟢 Implemented / 🟡 Pending User Validation	ACK-aware send pipeline with mutex locking and 5 ms stagger between lenses.
3	BLE telemetry (battery %, firmware, RSSI)	1	🟢 Implemented / 🟡 Pending Validation	BleTelemetryRepository captures 0x2C (battery), 0x37 (uptime), 0x11 (firmware) frames → auto-reset on disconnect + RSSI planned in r1c.
4	Heartbeat (keepalive every 30 s)	1	🟢 Implemented / 🟡 Pending User Validation	Automatic seq-based heartbeat per lens (0x25 ↔ 0x25 0x04 ACK).
5	HUD messaging API	2	⚫ Not Started	Wave 2 feature – sendHudMessage() broadcast + ack feedback.
6	Event decoding (touch, case open)	2	⚫ Not Started	Reserved for Phase 4.1.
7	Diagnostic console integration	2	🟢 Partially Implemented / 🟡 Pending User Validation	ConsoleInterpreter summarizes BLE/network/API health. To align with firmware and case telemetry in r1c.
8	Assistant diagnostic bridge	3	⚫ Not Started	Deferred to Wave 3.
9	Monochrome theme consistency	—	🟢 Implemented	BleStatusView uses black/gray surface + white text/icons + status colors.
10	Documentation + progress notes	—	🟢 Updated	[4.0-r1] and [4.0-r1b] logged in progress-notes.md.


⸻

🧠 CODEX IMPLEMENTATION GUIDELINES (Wave 1 → 1c)

1️⃣ Context Scope

Wave 1c focuses on:
	•	Completing telemetry integration between BleTelemetryRepository and Hub UI.
	•	Propagating RSSI and case battery readings into the repository.
	•	Ensuring UTF-8 fallback for firmware payloads.
	•	Adding reset logging and event throttling (distinctUntilChanged).
	•	Binding repository flows to AppLocator and HubViewModel.
	•	Extending ConsoleInterpreter summaries for firmware and case data.

Wave 2 (HUD + Diagnostics visuals) will begin only after Wave 1c validation completes.

⸻

2️⃣ BLE Service Architecture (Status – Delivered)

MoncchichiBleService
	•	Manages Left and Right Gatt clients with ACK sequencing and RSSI tracking.
	•	Serial write queue (5 ms stagger) prevents RF collisions.
	•	Heartbeat loop maintains seq ACK and auto-reconnect on timeout.
	•	disconnectAll() and per-lens state resets now supported.

✅ Firmware opcode (0x11) and case telemetry parsing added in r1b.
🟡 RSSI propagation to UI pending for r1c.

⸻

3️⃣ Telemetry Repository (Status – Delivered / Pending Refinement)

BleTelemetryRepository
	•	Parses battery (0x2C), uptime (0x37), and firmware (0x11).
	•	Maintains left/right snapshots + firmware version + auto-reset on disconnect.
	•	Includes bindToService() / unbind() helpers for coroutine scope binding.
	•	🟡 Planned for r1c:
	•	Add RSSI to LensTelemetry.
	•	Verify caseBatteryPercent mapping (byte [3]).
	•	Fallback to hex string if firmware payload is non-UTF-8.
	•	Emit distinct telemetry updates only on change.
	•	Log [BLE][DIAG] reset after disconnect.

⸻

4️⃣ Console Interpreter (Status – Delivered / Wave 1c Alignment)

ConsoleInterpreter
	•	Generates assistant-readable summaries (BLE / network / API / LLM).
	•	Detects ack timeouts, write failures, and reconnects.
	•	🟡 Planned in r1c: extend BLE section to include firmware and case battery readings.

⸻

5️⃣ UI – Monochrome BLE Status View (Status – Delivered / Minor Enhancement)

BleStatusView
	•	Displays Left/Right connection states, RSSI, and last ACK.
	•	Strict monochrome palette: #000000 bg · #1A1A1A surface · #FFFFFF text.
	•	🟡 For r1c: Bind live telemetry flow to update RSSI and battery % in real-time.

⸻

6️⃣ Reliability Matrix (Wave 1 → 1c Baseline)

Constraint	Guideline
Chunk delay	≈ 5 ms
ACK expectation	0xC9 before next command
Retry policy	3× then mark degraded
Heartbeat interval	30 s
Round-trip goal	≤ 2 s
Connection timeout	20 s per lens
Telemetry refresh	≤ 60 s interval
Auto-reset trigger	Both lenses disconnected → reset()


⸻

7️⃣ Wave 1 Acceptance Tests (Post-r1c Validation)

Scenario	Expected Behaviour
Dual connection + ACK	🟢 UI shows “Connected L/R”; console logs Keepalive ACK.
Telemetry updates	[DIAG] battery=xx% case=yy% firmware=vX.Y.Z.
Disconnect reset	Logs [BLE][DIAG] reset after disconnect; UI clears values.
RSSI display	BleStatusView shows e.g. –55 dBm for each lens.
Duplicate frames	No spam in console; updates only on value change.
Latency	≤ 2 s round-trip.


⸻

8️⃣ Files Implemented / Modified Through Wave 1b → 1c

File	Purpose
core/bluetooth/G1BleClient.kt	Per-lens ACK tracking and sequenced writes.
core/bluetooth/MoncchichiBleService.kt	Dual-lens BLE manager + heartbeat loop.
hub/data/telemetry/BleTelemetryRepository.kt	Telemetry aggregation (0x2C/0x37/0x11) + RSSI + reset logic.
hub/bluetooth/G1Protocol.kt	Packet helpers for battery, firmware, ping, brightness, reboot.
hub/console/ConsoleInterpreter.kt	Diagnostics summaries (extend with firmware + case telemetry).
hub/ui/components/BleStatusView.kt	Monochrome UI status + RSSI binding planned r1c.
hub/data/diagnostics/DiagnosticRepository.kt	Ref to new console path.
hub/assistant/OfflineAssistant.kt	Ref to new console path.


⸻

9️⃣ Known Gaps / Next Patch Objectives ( Wave 1c )

Area	Task	Status / Planned Action
Telemetry UI wiring	Bind BleTelemetryRepository.snapshot to Hub UI via AppLocator scope.	🟡 Pending implementation in r1c.
RSSI propagation	Extend repository to store per-lens RSSI from MoncchichiBleService.state.	🟡 Pending r1c.
Firmware payload handling	UTF-8 fallback → hex if invalid text.	🟡 Pending r1c.
Case battery telemetry	Confirm byte [3] index maps correctly to case battery %.	🟡 Pending verification.
Console alignment	Surface firmware + case data in diagnostic summary.	🟡 Planned r1c.
Telemetry reset log	Emit [BLE][DIAG] reset after disconnect message on repo.reset().	🟡 Planned r1c.
Duplicate event filter	Add distinctUntilChanged() to telemetry flow to reduce spam.	🟡 Planned r1c.


⸻

🧾 PROGRESS NOTES

[4.0-r1] ✅ Wave 1 foundations – dual-Gatt BLE service, ack-aware client, telemetry repository, monochrome UI.  
[4.0-r1b] ✅ Firmware telemetry parsing, disconnect reset, ping/brightness/reboot helpers.  
[4.0-r1c] 🟡 Planned – RSSI propagation, UTF-8 firmware fallback, UI binding (AppLocator scope), console alignment, reset logs, distinct telemetry updates.  


⸻

✅ Summary

Wave 1a → 1b completed core BLE foundations and telemetry framework.
Wave 1c focuses on bridging real-time data flows (RSSI / telemetry → UI / console) and finalizing stability behaviours before HUD and gesture features in Wave 2.
All Wave 1c changes will be validated through runtime tests and user confirmation before advancing to HUD integration.

⸻

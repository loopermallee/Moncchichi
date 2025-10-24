🧠 Moncchichi Hub — Context Engineering Document

(Shared operational memory between ChatGPT, Codex, and the user)

⸻

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT_PHASE: Phase 4.0 rev 3 — BLE Core Fusion (Wave 1 Foundations)

PHASE OBJECTIVE:
Build the Even Realities G1 dual-lens BLE foundation with full ACK-based sequencing, parallel telemetry channels, and heartbeat synchronization.
All telemetry, diagnostics, and HUD messages must travel in ≤ 2 seconds with 5 ms chunk pacing and 3× retry logic.
This Wave focuses only on connectivity, ack tracking, and telemetry parsing — no HUD or assistant logic yet.
Maintain Even Realities monochrome theme and offline-first principles from Phase 3.

⸻

🧩 CURRENT MILESTONES (Sequenced by Wave)

#	Milestone	Wave	Status	Notes
1	Dual-lens BLE connection (L + R)	1	🟢 Implemented / 🟡 Pending User Confirmation	MoncchichiBleService and G1BleClient handle dual Gatt sessions and per-lens state.
2	Bidirectional communication (App ↔ Glasses)	1	🟢 Implemented / 🟡 Pending User Confirmation	ACK-aware send pipeline with mutex locking and 5 ms stagger between lenses.
3	BLE telemetry (battery %, firmware, RSSI)	1	🟢 Partially Implemented / 🟡 Pending User Confirmation	BleTelemetryRepository captures 0x2C/0x37 frames → battery & uptime. Firmware opcode still pending.
4	Heartbeat (keepalive every 30 s)	1	🟢 Implemented / 🟡 Pending User Confirmation	Automatic seq-based heartbeat loop per lens (0x25 ↔ 0x25 0x04 ACK).
5	HUD messaging API	2	⚫ Not Started	Next wave feature (add sendHudMessage & display feedback).
6	Event decoding (touch, case open)	2	⚫ Not Started	Reserved for Phase 4.1.
7	Diagnostic console integration	2	🟢 Partially Implemented / 🟡 Pending User Confirmation	New ConsoleInterpreter added — interprets BLE logs and assistant health state.
8	Assistant diagnostic bridge	3	⚫ Not Started	Deferred to Wave 3.
9	Monochrome theme consistency	—	🟢 Implemented	BleStatusView uses black/gray surface + white text/icons + status colors.
10	Documentation + progress notes	—	🟢 Updated	[4.0-r1] Wave 1 foundations committed in progress-notes.md.


⸻

🧠 CODEX IMPLEMENTATION GUIDELINES (Wave 1)

1️⃣ Context Scope

Wave 1 includes:
	•	MoncchichiBleService.kt – dual Gatt sessions & heartbeat loop.
	•	G1BleClient.kt – per-lens ACK tracking & sequenced send.
	•	BleTelemetryRepository.kt – battery / uptime parsing framework.
	•	BleStatusView.kt – monochrome status UI with left/right lens state.
	•	ConsoleInterpreter.kt – diagnostic summary for assistant.

Next Wave will extend these modules with HUD + events support.

⸻

2️⃣ BLE Service Architecture (Status – Delivered)

MoncchichiBleService
	•	Manages both Left and Right lens Gatt connections.
	•	Each client (G1BleClient) handles ack sequencing and RSSI monitoring.
	•	Serial send queue prevents RF collision (5 ms stagger).
	•	Heartbeat loop auto-reconnects and marks lens “degraded” on ACK failure.

⚠️ Firmware opcode and case telemetry to be added in next revision.

⸻

3️⃣ Telemetry Repository (Status – Delivered / Partial)

BleTelemetryRepository
	•	Handles battery (0x2C) and uptime (0x37) frames.
	•	Stores left/right telemetry snapshots + last ACK timestamp.
	•	Exposes StateFlow<Snapshot> to UI and assistant.
	•	Missing firmware opcode parsing (⚠️ pending for Wave 1b).

⸻

4️⃣ Console Interpreter (Status – Delivered)

ConsoleInterpreter
	•	Parses recent logs → health summary ( BLE / network / API / LLM ).
	•	Detects timeouts, ACK failures, and rate-limit events.
	•	Supports assistant text summary and emoji overview.

⸻

5️⃣ UI – Monochrome BLE Status View (Status – Delivered)

BleStatusView
	•	Visual indicator for Left / Right lens status (Good / Degraded / Disconnected).
	•	Shows RSSI + last ACK timestamp.
	•	Strict Even Realities palette ( black #000000 , gray #1A1A1A , white #FFFFFF ).

⸻

6️⃣ Reliability Matrix (Wave 1 Baseline)

Constraint	Guideline
Chunk delay	≈ 5 ms
Ack expectation	0xC9 before next command
Retry policy	3× then mark degraded
Heartbeat interval	30 s
Round-trip goal	≤ 2 s
Connection timeout	20 s per lens


⸻

7️⃣ Wave 1 Acceptance Tests (Pending User Validation)

Scenario	Expected Behaviour
Connect L + R	🟢 Status “Connected L/R” appears in UI (BleStatusView).
Heartbeat loop	Console → [BLE] ❤️ Keepalive ACK.
Battery query	[DIAG] left/right battery=% in logs.
Disconnect one lens	UI shows ⚠️ Degraded status.
Reconnect	Status resets to 🟢 Connected.
Ack timeout	Console logs “ACK timeout” + degraded flag.


⸻

8️⃣ Files Implemented in This Wave

File	Purpose
core/bluetooth/G1BleClient.kt	Per-lens ACK tracking and sequenced writes ( new ).
core/bluetooth/MoncchichiBleService.kt	Dual-lens BLE manager + heartbeat ( new ).
hub/data/telemetry/BleTelemetryRepository.kt	Aggregates 0x2C/0x37 packets → telemetry ( new ).
hub/console/ConsoleInterpreter.kt	Analyzes console logs → health summary ( new ).
hub/ui/components/BleStatusView.kt	Monochrome BLE status UI ( new ).
hub/data/diagnostics/DiagnosticRepository.kt	Ref updated to new ConsoleInterpreter path.
hub/assistant/OfflineAssistant.kt	Ref updated to new ConsoleInterpreter path.


⸻

9️⃣ Known Gaps / Next Patch Objectives (Wave 1b)

Area	Task	Planned Action
Firmware telemetry	0x11 opcode parsing	Extend BleTelemetryRepository to capture firmware version.
Case battery telemetry	Incomplete	Map frame byte[3] → caseBatteryPercent.
Reset on disconnect	Missing	Invoke repo.reset() when both lenses disconnected.
Command builder expansion	Partial	Add G1Packets helpers for PING / BRIGHTNESS / REBOOT.
Telemetry flow integration	Pending	Wire live flow into Hub UI via AppLocator scope.


⸻

🧾 PROGRESS NOTES

[4.0-r1] ✅ Wave 1 foundations: dual-lens BLE service, ack-aware client, telemetry repository, and monochrome status UI.
[4.0-r1b] 🟡 Planned: firmware telemetry, disconnect reset, extended command builders.


⸻

✅ Summary:
Wave 1 core architecture is now in place but awaiting user validation for connectivity and ACK timing.
Next patch ( **4.0-r1b **) will finalize telemetry coverage and reset logic before advancing to Wave 2 (HUD + Diagnostics visuals).
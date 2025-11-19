CE – Production Even Parity (Sleep/Wake, Heartbeat, Telemetry, Notifications)

This section tells Codex exactly how to bring Moncchichi’s BLE behavior in line with the production Even Reality app, using:
	•	The G1 BLE Protocol document
	•	The nRF Connect logs between Even app and the glasses
	•	The existing Moncchichi code (no more copying behavior from EvenDemoApp unless it’s explicitly consistent with production logs)

You should be able to implement all patches just from this spec and the repo.

⸻

0. Files Codex must read before editing

Read these carefully before touching code:
	•	core/src/main/java/com/loopermallee/moncchichi/bluetooth/MoncchichiBleService.kt
	•	service/src/main/java/com/loopermallee/moncchichi/bluetooth/DualLensConnectionOrchestrator.kt
	•	core/src/main/java/com/loopermallee/moncchichi/bluetooth/G1BleClient.kt
	•	Any protocol helpers:
	•	G1Protocols (constants for opcodes / timing)
	•	Reply parsers / telemetry model (G1ReplyParser, BleTelemetryRepository, etc.)
	•	Existing command send helpers:
	•	Any central “send command to both lenses / one lens” helpers

Do not invent new command formats; align with protocol + logged Even behavior.

⸻

1. Source of Truth and Constraints
	1.	The production Even app behavior is the canonical source:
	•	nRF logs for:
	•	Idle, in-case, worn, charging, pairing
	•	Notification handling
	•	Silent mode/heartbeat behavior
	2.	The G1 protocol defines:
	•	Command opcodes:
	•	0x2B – Get Silent Mode Settings
	•	0x4F – Set Notification Auto Display
	•	0x2C – Detailed Battery State (with subcommands; we care about 0x01)
	•	0x37 – Time Since Boot / Uptime
	•	0xF5 – Device Events (wear-state, case, pairing, etc.)
	•	ACK status bytes:
	•	0xC9 – success
	•	0xCA – failure
	•	0xCB – continue (more data)
	3.	Parity target:
	•	Behavior must match production Even app + nRF logs, not just EvenDemoApp.
	•	If there is a conflict, prefer production logs over the demo code.

⸻

2. Heartbeat Parity – Use 0x2B Get Silent Mode as Keep-Alive

2.1. What Even does
	•	Production app sends 0x2B (Get Silent Mode Settings) at a regular interval (~30s) as a keep-alive:
	•	Prevents the G1 from auto-disconnecting after its internal idle timeout.
	•	Response includes:
	•	Silent mode state (on/off)
	•	Wear state (worn / not worn / in case) as a small status byte.

2.2. What Moncchichi must do
	1.	Heartbeat command:
	•	If any existing heartbeat is using other opcodes (e.g. teleprompter increment 0x25), replace the logical heartbeat with:
	•	CMD_SILENT_MODE_GET = 0x2B
	2.	Interval:
	•	Set heartbeat interval to match Even’s pattern:
	•	~30 seconds (use existing HEARTBEAT_INTERVAL_MS constant, set to 30000ms).
	3.	Send behavior:
	•	Heartbeat send should:
	•	Use the existing “send-to-both-lenses” helper.
	•	Respect idle/sleep gates (do not send when in idle sleep).
	4.	Response handling:
	•	In the central BLE notification/packet parser:
	•	Add case for opcode 0x2B.
	•	Parse payload according to protocol & logs:
	•	Byte 0: 0x2B
	•	Subsequent bytes:
	•	One byte for silent mode state (non-0x0A likely means enabled; 0x0A = off in logs).
	•	One byte for wear state (0x06 / 0x07 / 0x08 etc.).
	•	Update:
	•	Telemetry / state model for:
	•	Silent mode boolean
	•	Wear status enum

2.3. Invariants / checks
	•	Heartbeat:
	•	Must respect sleep/idle state checks already present (no PING when device is in idle sleep).
	•	Should not collide with other ongoing critical flows (e.g. SMP / DFU).

⸻

3. Notification Auto-Display – 0x4F on Connect

3.1. What Even does
	•	Production app sends 0x4F Set Notification Auto Display when the glasses are connected / initialized:
	•	Format (from protocol):
	•	[0] 0x4F
	•	[1] enable flag (0 or 1)
	•	[2] timeout seconds
	•	ACKed via generic status bytes (0xC9/0xCA).

3.2. What Moncchichi must do
	1.	Define constants in G1Protocols:
	•	CMD_NOTIFICATION_AUTO_DISPLAY = 0x4F
	2.	When to send:
	•	After both lenses reach “ready” / initial handshake stage (the same point where other config commands are sent).
	•	Likely in MoncchichiBleService at:
	•	“on both lenses connected and stable” point
	•	Or orchestrator event that transitions to Stable/ReadyBoth.
	3.	Payload:
	•	Default behavior:
	•	Enable auto display: enable = 0x01
	•	Timeout: timeoutSec = 0x05 (or whatever close to Even’s observed behavior)
	•	Build command:
	•	[0x4F, 0x01, 0x05]
	4.	Send:
	•	Use “send to both lenses” helper.
	•	Ensure we wait for ACK semantics per Section 6 (0xC9/0xCB/0xCA) once that is implemented.
	5.	Response handling:
	•	In ACK parsing logic (generic status handler):
	•	When a 0x4F command is in-flight:
	•	Treat 0xC9 as success.
	•	Log/warn on 0xCA.

⸻

4. Telemetry Parity – 0x2C Battery Detail & 0x37 Uptime

4.1. What Even does
	•	On connection/init, Even queries:
	•	Detailed battery state via 0x2C with subcommand 0x01.
	•	Time since boot via 0x37 (uptime).
	•	Responses are used for telemetry screens and internal logic.

4.2. What Moncchichi must do
	1.	Battery detail – 0x2C 0x01:
	•	Define constant(s) if missing:
	•	CMD_BATT_DETAIL = 0x2C
	•	SUBCMD_BATT_DETAIL_FULL = 0x01 (name can vary but should be descriptive).
	•	On init (after connection and basic handshake):
	•	Send [0x2C, 0x01] to both lenses.
	2.	Uptime – 0x37:
	•	Define CMD_GET_UPTIME = 0x37 if not present.
	•	Send it to one side only (e.g. right lens) because uptime is global:
	•	Payload: [0x37] (no extra parameters, if logs confirm that).
	3.	Response parsing – 0x2C:
	•	In the packet handler:
	•	On opcode == 0x2C:
	•	Confirm subcommand payload[1] == 0x01 (if subcommand present).
	•	Use the protocol’s expected length to sanity check.
	•	Extract battery percent from defined offset (in the protocol doc; if unknown, use logs to match the position).
	•	Update BleTelemetryRepository with:
	•	batteryPercent
	•	Optionally: voltage, temperature if present.
	4.	Response parsing – 0x37:
	•	On opcode == 0x37:
	•	Parse the next 4 bytes as little-endian uptime in seconds:
	•	secondsSinceBoot = payload[1..4]
	•	Store in telemetry snapshot:
	•	uptimeSeconds or similar field.

4.3. Invariants / checks
	•	Do not spam these queries:
	•	Send on:
	•	Initial connection
	•	Reconnect after idle sleep
	•	Not as a fast polling mechanism.
	•	Ensure these commands also respect idle sleep gates.

⸻

5. Device Event Parity – 0xF5 Wear / Case / Pairing

5.1. What Even does
	•	Production app receives Device Events (0xF5) and reacts to:
	•	Wear state:
	•	0x06 – worn
	•	0x07 – not worn
	•	0x08 / 0x0B – in case (open / closed)
	•	Charging and battery level events:
	•	0x09 – charging
	•	0x0A – battery level
	•	Pairing:
	•	0x11 – BLE paired success
	•	These events control wake/sleep behavior and UI.

5.2. What Moncchichi must do
	1.	In the central packet handler where 0xF5 is parsed:
	•	Replace/extend any minimal handling with a proper sub-code switch on payload[1].
	2.	Mapping:
	•	0x06 (worn)
	•	Call onGlassesWorn() (create if not present).
	•	Responsibilities:
	•	Mark glasses as wearable/active.
	•	Allow HUD/assistant features.
	•	0x07 (not worn)
	•	Call onGlassesRemoved().
	•	Mark glasses as not worn, but not necessarily in case.
	•	0x08 / 0x0B (in case)
	•	Call onGlassesInCase().
	•	Should trigger or maintain sleep/idle behavior:
	•	No new writes that assume HUD is visible.
	•	Possibly sync with MoncchichiBleService’s idle sleep flags.
	•	0x09 (charging state)
	•	Feed into existing updateChargingState() or equivalent.
	•	0x0A (battery level)
	•	Feed into updateBatteryLevel().
	•	0x11 (BLE paired success)
	•	Log and optionally push a UI event:
	•	Confirm pairing succeeded (useful for first-time pair flow).
	3.	Ensure these device event transitions consistently update:
	•	Telemetry repository (wear / in-case).
	•	Any reactive flows exposed to UI.

⸻

6. ACK Pacing – 0xC9 / 0xCA / 0xCB Enforcement

6.1. What Even does
	•	Uses G1 generic status bytes:
	•	0xC9 – success
	•	0xCA – failure
	•	0xCB – continue (more chunks expected)
	•	For multi-chunk transfers (notifications, image data, etc.):
	•	Even waits for an ACK after each chunk before sending the next.

6.2. What Moncchichi must do
	1.	Identify the generic ACK handler:
	•	Where status bytes for commands are processed.
	•	It may already exist (for AckOutcome/AckSignals).
	2.	Implement per-command ACK tracking:
	•	For each outgoing command, keep track of:
	•	Current opcode
	•	Whether more chunks are expected
	•	Outstanding ACK state
	3.	For multi-chunk senders (e.g., notifications, large payloads):
	•	After bleWrite(chunk):
	•	Block until ACK is received:
	•	0xC9 → chunk success; send next or finish.
	•	0xCB → continue; send next; treat as intermediate.
	•	0xCA → failure; abort entire operation.
	•	You can reuse the existing Channel<AckOutcome> / ackSignals pattern if present.
	4.	Timeouts:
	•	Reuse existing command timeout configuration for waiting on ACK.
	•	On timeout:
	•	Treat like 0xCA failure.
	•	Optionally trigger reconnect if consistent with existing behavior.

⸻

7. Sleep / Wake Interaction with Above

We have already been patching idle sleep and wake (sleep gate, beginWakeHandshake, etc.). The new parity behavior must respect that:
	•	Heartbeats (0x2B):
	•	MUST NOT be sent during idle sleep.
	•	Notification auto-display (0x4F):
	•	Should be (re)sent when:
	•	We return from idle sleep to an active/stable state.
	•	Telemetry queries (0x2C, 0x37):
	•	Should be triggered on:
	•	Initial connect
	•	Reconnect/wake if handshake resets
	•	Device events (0xF5):
	•	Should feed into the same sleep/wake authority logic that we already use for:
	•	idleSleepActive
	•	sleepGateActive
	•	Do NOT re-introduce the old “wake quiet window” code that we just removed unless logs clearly show a required delay.

⸻

8. Implementation Checklist for Codex

Codex should go through these in order:
	1.	Heartbeat parity
	•	Change heartbeat opcode to 0x2B in the heartbeat module.
	•	Adjust interval to ~30 seconds.
	•	Parse and store silent mode + wear state from 0x2B response.
	2.	Notification auto-display
	•	Add CMD_NOTIFICATION_AUTO_DISPLAY = 0x4F constant.
	•	Send [0x4F, 0x01, 0x05] to both lenses at the appropriate post-handshake stage.
	•	Ensure you handle ACK and log failures.
	3.	Telemetry parity
	•	Send 0x2C 0x01 to both lenses after connection.
	•	Send 0x37 to one lens (e.g. right) after connection.
	•	Parse responses and update telemetry (battery%, uptime).
	4.	Device events
	•	Extend 0xF5 handling to full sub-code mapping:
	•	0x06, 0x07, 0x08/0x0B, 0x09, 0x0A, 0x11.
	•	Wire into onGlassesWorn, onGlassesRemoved, onGlassesInCase, etc., updating telemetry and UI state.
	5.	ACK pacing
	•	Implement per-command ACK tracking using status bytes 0xC9/0xCA/0xCB.
	•	Make multi-chunk senders wait for ACK per chunk.
	•	Respect timeouts and error handling.
	6.	Regression guard
	•	Ensure existing features (scan, pair, connect, basic telemetry, basic notifications) still work.
	•	No new protocol assumptions beyond what’s stated in the G1 protocol + logs.

⸻

9. Testing Plan (Codex + Human)

Codex should wire in enough logging so you can verify parity against nRF:
	1.	Logs to include:
	•	Every outbound 0x2B, 0x4F, 0x2C, 0x37, and 0xF5 event.
	•	Parsed fields:
	•	Silent mode, wear state, in-case state.
	•	Battery % and uptime.
	•	ACK results: 0xC9, 0xCA, 0xCB per chunk.
	2.	Scenarios to test manually:
	•	Glasses idle on table (not worn, not in case).
	•	Glasses worn; see heartbeats continue and notifications display.
	•	Glasses placed in case; ensure app respects sleep (no heartbeats, no unneeded writes).
	•	Notifications:
	•	Send an Android notification and confirm it appears on HUD with auto display.
	•	Power / reconnect:
	•	Turn off/on glasses and verify reconnection flow still works.
	3.	Parity check:
	•	Use nRF Connect side-by-side:
	•	Compare sequence of opcodes and timing:
	•	Heartbeat (0x2B) cadence
	•	Telemetry queries after connect
	•	Device event reactions
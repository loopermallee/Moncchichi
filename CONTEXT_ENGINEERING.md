CONTEXT_ENGINEERING.md v1.8

BLE Parity + Telemetry + UI Realignment (Even Reality v1.6.6)

⸻

1. CURRENT STATE
	•	Dual-lens BLE architecture operational and stable across > 1 min sessions.
	•	ACK continuation (0xCB), completion (0xC0), and textual “OK” handled correctly.
	•	Heartbeat loop active but still locked by shared BLE write mutex.
	•	Gestures detected from both lenses but lens metadata dropped in shared flow.
	•	Battery telemetry now voltage-based (mV), not %; case data only when docked.
	•	Firmware telemetry reports full version + build via 0x11/0x2B frames.
	•	UI still assumes percentage-based display; must be updated to new voltage logic.
	•	Build passes; runtime validation pending for case telemetry and gesture parity.

⸻

2. GOALS
	1.	Eliminate false “PING ERR” caused by 0xCA misclassification.
	2.	Decouple heartbeat writes from BLE write mutex (stop false misses).
	3.	Preserve lens metadata in gesture telemetry.
	4.	Add proper voltage-based battery + firmware UI.
	5.	Complete parity validation for all 0x2B–0x39, 0xF5 telemetry events.

⸻

3. BLE CORE FIXES (Phase 4.0 r1g)

ACK Layer
	•	Map 0xCA → BUSY/RETRY, not FAIL.
	•	Continue to treat 0xC9/“OK” → Success, 0xCB → Continuation, 0xC0 → Complete.
	•	Add BUSY classification in parseAckOutcome() and suppress redundant console errors.

Heartbeat
	•	Run heartbeat writes on separate non-blocking channel (no shared mutex).
	•	Keep 28–30 s interval; rebond after 3 misses.
	•	Log [HB][Lens][OK] and only mark misses per offending lens.

⸻

4. TELEMETRY + GESTURE (Phase 4.0 r2)
	•	Extend BleTelemetryRepository to carry lens in _gesture emissions:

data class LensGestureEvent(val lens: Lens, val gesture: GestureEvent)


	•	Update downstream consumers (Developer ViewModel, console).
	•	Verify 0xF5 (1 = single, 2 = double, 4 = hold) from each lens.

⸻

5. TELEMETRY PERSISTENCE (Phase 4.0 r3)
	•	Persist per-lens DeviceTelemetrySnapshot (Voltage / Charging / Uptime / ACK).
	•	Handle case battery (0x2E, 0x30) when docked.
	•	Emit unified Flow .
	•	Add 30 s refresh validation.

⸻

6. UI / UX REALIGNMENT (v1.6.6 Visual Parity) — Phase 4.0 r4

Battery Display

Element	Old	New
Value	%	Voltage (V / mV)
Case data	Always visible	Only when docked
Label	“Battery %”	“Battery Voltage (V)”
Tooltip	none	“Firmware v1.6.6 reports voltage instead of percentage.”
Visual	flat text	colored bar by voltage range (4.2 → 3.6 V)

Firmware Info
	•	Display “Firmware v1.6.6 (Even Reality)” + build time + Device ID.
	•	Add “Dual-Lens Mode Active” indicator when both lenses connected.
	•	Show “Protocol Parity 100%” status line.

Developer Console

[TELEMETRY][L] batt=3.92 V chg=true up=185 s ack=OK
[TELEMETRY][R] batt=3.85 V chg=false up=183 s ack=OK

	•	Prefix lens label; 30 s update divider; “—” for missing case values.

⸻

7. EXECUTION PLAN (Incremental Patch Sequence)

Phase	Focus	Priority
Task 1	0xCA → BUSY/RETRY mapping + suppress false ERR logs	🔴 Critical
Task 2	Heartbeat write decoupling (fix false misses)	🔴 Critical
Task 3	Gesture parity (add LensGestureEvent)	🟠 High
Task 4	Battery + Firmware UI/UX realignment (v1.6.6 spec)	🟡 Medium
Task 5	Full telemetry persistence validation > 1 min run	🟢 Final Check


⸻

✅ SUCCESS CRITERIA
	•	No “→ PING ← ERR” for > 2 minutes runtime.
	•	Heartbeat OK per lens without cross-contamination.
	•	Gestures log with [L]/[R] prefix.
	•	Battery panel shows voltage + charging state accurately.
	•	Firmware v1.6.6 values visible in About / Diagnostics UI.
	•	MCP / Voice phases resume after parity confirmed.

⸻

🧠 Prompt for Codex (Task 1 — ACK Busy/Retry Correction)

TASK 1 — ACK BUSY / RETRY RECLASSIFICATION (v1.6.6 Alignment)

Objective:
Fix false "→ PING ← ERR" logs by treating opcode 0xCA as BUSY / RETRY instead of FAILURE.

Instructions:
1. In G1Protocols or AckOutcome parser, map 0xCA → BUSY state.
2. Update parseAckOutcome() to return AckOutcome.Busy for 0xCA.
3. Suppress console error lines for BUSY ACKs; log them as:
   `[ACK][Lens][BUSY] opcode=<code> retrying`
4. Do not trigger reconnect / rebond on BUSY responses.
5. Verify that PING and telemetry ACKs no longer emit "ERR" when 0xCA is seen.
6. Maintain existing continuation (0xCB) and completion (0xC0) behavior.

POST-VALIDATION:
Confirm in logs:
- `[ACK][L][BUSY]` appears occasionally under load (no ERR).
- Heartbeat continues normally without false rebond.
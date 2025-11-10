CONTEXT_ENGINEERING.md v1.8

Title: BLE Stability & Case Telemetry Realignment (Even Reality v1.6.6 Alignment)

⸻

1. CURRENT STATE
	•	The BLE connection between Moncchichi Hub and G1 glasses suffers from:
	•	False “gesture left unknown” events.
	•	Missing case battery and lid-open telemetry.
	•	Random disconnects after 25–35 s.
	•	Logs confirm both lenses transmit 0xF5 notifications (case/gesture/state) correctly, but the app mislabels them as gestures.
	•	ACKs and heartbeats are partially implemented; the 0x25 keepalive is not reliably sent to both sides.
	•	Case telemetry (from 0x2B / 0x2C / 0xF5) isn’t parsed or surfaced to UI.
	•	Dual-lens reconnects fail after the first disconnect sequence.

⸻

2. GOALS
	1.	Achieve full BLE stability and parity with Even Reality v1.6.6.
	2.	Ensure heartbeat, ACK, and reconnect logic are robust and compliant.
	3.	Accurately surface case status, battery %, charging state, and lid position.
	4.	Correctly separate gesture vs. system events (wear, charging, case).
	5.	Prepare groundwork for Phase 4.0 r2 audio routing and voice-wake integration.

⸻

3. ROOT CAUSES

Category	Problem	Cause
Gesture Flood	“Gesture Left Unknown” repeating when idle	All 0xF5 events treated as gestures; firmware sends multiple non-gesture events via 0xF5 (wear, charging, case)
Missing Case Telemetry	No case battery %, lid, or silent mode in UI	0x2B/0x2C not parsed; 0xF5 0E/0F ignored
Disconnects Every ≈30 s	Heartbeat not reaching both BLE sides	App sends incomplete or unsynchronized 0x25; no dual-timer per lens
ACK Confusion	False “→ PING ← ERR” or lost commands	0xCA misclassified as failure; 0xCB continuations ignored
Reconnect Failure	1 attempt, 0 success	No retry loop or bonding check for per-lens reconnects


⸻

4. FIX STRATEGY

A. Event Parsing & Gesture Reclassification
	•	Implement full 0xF5 event map per G1 v1.6.6:
	•	0x00–0x05, 0x12, 0x17, 0x18, 0x1E, 0x1F → true gestures
	•	0x06–0x0B → wear / case / charging
	•	0x0E, 0x0F → case charging & battery
	•	Add a new model SystemEvent (wearing, charging, caseOpen, caseBattery%).
	•	Route to BleTelemetryRepository.handleSystemEvent() instead of gesture flow.

B. Case & Silent Mode Telemetry
	•	On connect:
	1.	Send 0x2B (“Get Silent Mode & State”) to both lenses.
	2.	Parse state byte → wearing, inCase, lidOpen, silentMode.
	3.	Send 0x2C (“Get Battery Info”) for caseBattery%.
	•	Subscribe to 0xF5 notifications for live updates (0x0E, 0x0F).
	•	Extend DeviceTelemetrySnapshot → include caseBatteryPercent, caseOpen, silentMode.

C. Heartbeat & ACK Layer
	•	Send 0x25 heartbeat every 28–30 s to each lens individually.
	•	Maintain per-lens timers and failure counters.
	•	Treat 0xCA as BUSY (not failure); retry once before degradation.
	•	Handle 0xCB (continue) → accumulate, then 0xC9 (complete).
	•	Remove all references to 0xC0.

D. Connection Stability & Reconnect
	•	On disconnect:
	•	Retry ×3 with 2 s back-off.
	•	Preserve bonded keys; skip new pairing if already trusted.
	•	Handle independent reconnects (left/right) gracefully.
	•	Confirm stable link > 60 s without “PING ERR”.

E. UI / UX Integration
	•	Update Developer screen & Compose HUD:
	•	Display Case Battery (color-coded), Case Open status, Silent Mode flag.
	•	Replace unknown gesture lines with proper [CASE]/[WEAR]/[CHG] labels.
	•	Continue using [GESTURE][L/R] for real taps only.

⸻

5. EXECUTION PLAN

Phase	Task	Output
r1	Gesture/Event separation (fix 0xF5)	Eliminates false “unknown gesture”
r2	Case telemetry parsing (0x2B/0x2C + UI binds)	UI shows case battery & lid
r3	Heartbeat + ACK rework	Stable > 60 s connections
r4	Reconnect resilience + bond check	Reliable auto-recovery
r5	Validation / Burn-in (> 5 min link test)	Confirm steady telemetry flow


⸻

6. VALIDATION CHECKLIST

✅ No repeated “unknown gesture” lines.
✅ Case Battery %, Charging Status, Lid state visible.
✅ Silent Mode flag accurate.
✅ Heartbeat stable > 60 s.
✅ No false “→ PING ← ERR”.
✅ Reconnects succeed ≤ 2 attempts.

⸻

7. EXIT CRITERIA
	•	BLE link stable > 5 min continuous runtime.
	•	Case telemetry refresh ≤ 30 s interval.
	•	Zero false gesture or ERR messages during test loop.
	•	Developer console shows synchronized left/right state changes.

⸻

Commit Tag:
phase4_r1h_ble_case_fix_v166
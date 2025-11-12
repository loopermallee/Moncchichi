CONTEXT_ENGINEERING.md – Phase 4.10 / BLE Stability Alignment


---

Goal

Stabilize BLE communication between the Moncchichi Hub app and Even G1 glasses so that pairing, telemetry, and gestures behave identically to the official Even Reality app (v1.6.6).
All known issues—dropped ACKs, “→ PING ← ERR”, phantom gestures, and missing case values—stem from command overlap, premature reconnects, and outdated ACK semantics.


---

Logic Overview

Current app is reactive: each BLE notification triggers immediate response.
Even Reality app is state-driven: commands are serialized through a queue and progress only after explicit ACK completion.
This phase introduces a deterministic state machine per lens and a unified command queue that enforces pacing, retries, and orderly handshake.


---

Task 1 – Session State Machine and Command Queue

Purpose:
Prevent overlapping writes by enforcing “one in-flight command per lens”.

Logic:

States = Idle → Handshake → Ready → Reconnecting.

Each lens maintains CommandQueue<FirmwareOp> with enqueue(), dequeue(), inFlight.

When sending a command, mark it as in-flight until ACK completion.

Only after receiving 0xC9, "OK", or 0xC0 does the next command start.


Exit criteria

No concurrent TX frames.

Dev console shows at most one “in-flight” operation per lens.

No → PING ← ERR during idle.



---

Task 2 – ACK Assembler and Retry Behavior

Purpose:
Decode multi-frame replies and handle Busy (0xCA) correctly.

Logic:

ACK	Meaning	Action

0xC9 or ASCII "OK"	Success	Complete current command
0xCA	Busy	Wait 250–300 ms (+jitter), retry up to 5 times
0xCB	Continue	Append payload; stay in-flight
0xC0	Complete	Emit assembled payload; mark done


Assembler merges 0xCB chunks until 0xC0, then delivers a single parsed object to the telemetry layer.

Exit criteria

At least one Busy retry succeeds without ERR.

Multi-frame commands (0x11/0x2C) assemble correctly across 100 runs.

No duplicate ACK logs.



---

Task 3 – Handshake Sequencing and Initialization

Purpose:
Mirror Even Reality’s connection order to avoid ignored telemetry requests.

Sequence:

1. HELLO


2. System Init (0x23)


3. Get Info (0x11) or Glasses Info (0x2C)


4. Subscribe Telemetry (0x2B–0x39)


5. Heartbeat Start (PING)



Telemetry subscription must wait for System ACK completion.

Exit criteria

No telemetry timeout after HELLO.

Case and battery values populate within 3 s of Ready state.

Handshake logs always show HELLO → OK → 23 → OK → 2C → OK.



---

Task 4 – Heartbeat and Rebond Policy

Purpose:
Prevent unnecessary disconnects by allowing tolerances.

Logic:

Heartbeat interval ≈ 30 s.

Consider connection lost only after 3 missed ACKs (≈ 90 s).

On loss, reconnect → Handshake → Telemetry.

Keep Android bond; do not unpair.


Exit criteria

Stable session ≥ 5 min with no forced reconnects.

After 3 reconnect cycles, bond still present in Bluetooth settings.

No “Bond missing ⚠️” in logs.



---

Task 5 – Protocol Expansion (Silent Mode + New Gestures)

Purpose:
Adopt latest G1 spec fields for parity with Even Reality v1.6.6.

Logic:

Case telemetry (0x2B / 0x2C) → parse silentMode, lidState.

Gesture map (0xF5) → include codes 0x1E–0x20 (dashboard, translate), 0x06–0x0B (wear/case).

System commands (0x23) → support 6C (debug), 72 (reboot), 74 (firmware info).

Display settings (0x26) → brightness, depth, preview, double-tap, long-press, mic-on-lift.

Wear detection (0x27) → enable/disable.


Exit criteria

No “unknown gesture” warnings.

Case UI shows silent mode flag.

Firmware info and display settings commands ack successfully.



---

Task 6 – Telemetry Persistence and Snapshot Update

Purpose:
Ensure battery, case, lid, uptime, RTT, ACK status persist across disconnects.

Logic:

MemoryDb v7 with leftSnapshotJson / rightSnapshotJson.

On every telemetry update, persist snapshot.

DeveloperViewModel observes flow and summarizes:
Case 80 % (Open) • L 3980 mV • R 3975 mV • FW 1.6.6 • Up 112 s.

Auto-log every 30 s.


Exit criteria

Snapshots survive reconnect and app restart.

30-s telemetry log visible in console.

Database migration v6→v7 safe.



---

Task 7 – Developer Console UX Refinement

Purpose:
Improve debug visibility for testing.

Logic:

Auto-scroll to latest log entry unless paused.

Color scheme against dark background:

Normal = light gray

Success = green

Warning = yellow

Error = red


Optional filter by tag (“PING”, “CASE”, “GESTURE”, “ACK”).

Highlight timestamp when paused.


Exit criteria

Live console scrolls automatically.

Colors readable in dark mode.

Filters apply instantly and persist.



---

Validation Checklist

✅ Stable 5-min session with no ERR PING.

✅ Case battery and silent mode visible within 3 s.

✅ Gestures correctly named.

✅ Multi-frame commands complete without overlap.

✅ Bond persists after three reconnects.

✅ Console shows FIFO queue behavior and color coded events.



---

Post-Phase Goals

Once stability confirmed:

Integrate audio routing (AudioOutManager) and Voice/Audio settings UI.

Re-enable AI/MCP hooks after BLE stack is stable.

Extend telemetry to diagnostic export for support logs.
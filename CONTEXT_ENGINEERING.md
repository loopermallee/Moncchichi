CONTEXT ENGINEERING – PHASE 5.2

Idle Sleep Parity (Even-Reality Accurate State Machine)

============================================

Purpose of This Patch

The goal of Phase 5.2 is to make Moncchichi Hub behave exactly like the official Even Reality G1 app when the glasses are:

folded + placed in case + case open + not charging + no gestures pressed + idle.

In this state, Even Reality transitions the headset into a silent idle mode, where:

No outbound BLE writes from the phone

No heartbeat

No ACK watchdog

No reconnect attempts

No state churn

Only minimal natural notifications from glasses (vitals/case)


Your app currently does not reach this state, even after earlier patches.

This document explains exactly what must change at each layer.


---

============================================

1. Deep Comparison: Even Reality vs Moncchichi Hub

============================================

1.1. Even Reality Idle NRF Profile (Ground Truth)

When folded + in case + lid open:

Even Reality BLE traffic = near-zero

No PING (0x25)

No Host ACK requests

No reconnect attempts

No CCCD writes

No outbound commands


The only packets visible are:

Vitals (0x2C) every several minutes

Case state / proximity (0x2B) when state changes


Even’s app logs:

Nothing except:

[CASE] CaseOpen → CaseOpen (when flap adjusted)

Occasional vitals parsing


Absolutely no heartbeats, no “[BLE] ❤️”, no “ERR”, and no reconnects.


---

1.2. Moncchichi Idle NRF Profile (current behavior)

When folded + in case + lid open:

Moncchichi BLE traffic = incorrect

Still sends PING (0x25)

Still expects ACKs

Still logs “Keepalive ERR”

Still runs reconnect logic

Still evaluates degraded connection

Still emits unnecessary logs


Phone app logs:

[BLE] ❤️ Keepalive

[ACK] stall

[ERR]

“Reconnecting…”

Telemetry refreshes

Developer console noise


Conclusion: App never truly enters sleep state → all subsystems continue running.


---

============================================

2. Root Cause Analysis

============================================

Moncchichi has four layers that jointly fail:


---

2.1. BleTelemetryRepository (critical root problem)

Problems:

Sleep detection logic does not match Even’s real conditions.

Missing fields:

foldState

inCase

caseOpen

charging

lastVitalsTimestamp


isLensSleeping() is wrong

isHeadsetSleeping() never returns true

No unified event stream for sleep/wake

Telemetry never triggers sleep → all other layers remain awake


This is the single most important missing component.


---

2.2. G1BleClient

Problems:

Heartbeat coroutine not suspended during sleep

ACK timers not suspended

PING writes continue

ERR/stall logs appear

Mirrors Even’s ACTIVE behavior, not IdleSleep behavior



---

2.3. MoncchichiBleService

Problems:

Receives no valid SleepEvent due to (2.1)

Still performs:

Reconnect gating

Degraded checks

Heartbeat start/stop


Logs incorrectly:

[SLEEP][HEADSET] ... instead of
"[SLEEP] Headset → IdleSleep"



MoncchichiBleService must:

Freeze everything during IdleSleep

Wake cleanly only when required



---

2.4. DualLensConnectionOrchestrator

Problems:

IdleSleep state exists but not fully enforced

Reconnect continues in background

Telemetry refresh jobs run

Command dispatch allowed

Mirror behaviour still active

Logs too noisy


Even orchestrator = totally frozen core.
Moncchichi orchestrator = half-awake.


---

============================================

3. Correct IdleSleep Definition (Based on Even Reality)

============================================

A lens enters “sleep” if:

foldState == FOLDED
inCase == true
caseOpen == true
charging == false
(lastVitalsTimestamp is older than QUIET_THRESHOLD)

Headset enters IdleSleep when:

leftLens.sleeping AND rightLens.sleeping

QUIET_THRESHOLD

Experimental from Even logs:

>= 8–10 seconds of no vitals writes


---

============================================

4. Required State Machine Changes (All Layers)

============================================

4.1. BleTelemetryRepository – MUST emit exact sleep/wake events

Add fields:

caseOpen

inCase

foldState

charging

lastVitalsTimestamp


Add helpers:

isLensSleeping(lens)

isHeadsetSleeping()

SleepEvent.SleepEntered

SleepEvent.SleepExited


Repository role = truth oracle for sleep.

Everything downstream must obey it.


---

4.2. G1BleClient – Completely silence during sleep

Suspend:

Heartbeat coroutine

ACK watchdog

ERR logs

ALL writes (mirror, refresh, commands)


Exactly like Even.

Wake = resume heartbeat only after orchestrator returns to Active.


---

4.3. MoncchichiBleService – Proper sleep/wake gate

When SleepEvent.SleepEntered fires:

[SLEEP] Headset → IdleSleep

Then:

Stop reconnect coordinator

Pause state transitions

Stop sending commands

Freeze telemetry refresh

Disallow Degraded/Err states

Disallow Keepalive start

Stop G1BleClient ACK/heartbeat


When SleepEvent.SleepExited fires:

[WAKE] Headset → Awake

Then:

Re-enable orchestrator

Refresh vitals

Restart heartbeat

Perform reconnect if needed



---

4.4. DualLensConnectionOrchestrator – Hard freeze during IdleSleep

While in IdleSleep:

Reconnect loops = OFF

Priming = OFF

Refresh = OFF

Pending mirror = PAUSED

Command dispatch = BLOCK

Incoming telemetry = allowed

No state churn

Only absorbs notifications from BLE


Wake → resume previous state (usually Stable).


---

============================================

5. Expected Correct NRF Profile (Target for Codex)

After patch, Moncchichi must show:

During Idle Sleep

No PING (0x25)

No PING ACK requests

No GATT operations

No CCCD writes

No reconnect traffic

No heartbeat traffic

Only:

vitals (0x2C)

case (0x2B) notifications


Parallel to Even Reality logs


Logs

[SLEEP] Headset → IdleSleep

Then silence except device → host events.

After Wake

[WAKE] Headset → Awake

Then:

heartbeat resumes

connection logic resumes



---

============================================

6. Acceptance Criteria

Codex must ensure Moncchichi replicates Even exactly:

Must match Even Reality idle profile

Absolute silence from phone

No heartbeats

No reconnect

No ACK stall

No writes

Zero ERR logs

Only case/vitals notifications allowed


Sleep transitions are accurate

Fires SleepEntered only when both lenses meet conditions

Wake only when fold/in-case changes or lid closes


Logs identical

[SLEEP] Headset → IdleSleep
[WAKE] Headset → Awake


---

============================================

7. File-Level Responsibilities Summary (Codex must follow this)

File	Responsibilities

BleTelemetryRepository	Sleep detection & events
G1BleClient	Pause all outbound BLE during sleep
MoncchichiBleService	Global gating of reconnect/heartbeat
DualLensConnectionOrchestrator	IdleSleep state freeze



---

============================================

8. What Codex must NOT touch

To avoid regressions:

No UI changes

No AI assistant code

No DeveloperConsole modifications

No context-engineering parsing layers

No pairing dialog changes



---

============================================

End of CE 5.2 – Idle Sleep Parity

============================================
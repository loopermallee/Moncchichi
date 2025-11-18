CONTEXT ENGINEERING – PHASE 5.2 (Rev C)
Idle Sleep Parity (Even-Reality Accurate State Machine)

============================================
Purpose of This Patch

Phase 5.2 aims to make Moncchichi Hub behave exactly like the official Even Reality G1 app when the glasses are:

folded

placed in the case

case lid open

not charging

no gestures pressed

phone left idle (no UI actions)


In this state, Even Reality transitions the headset into a silent idle mode where:

No outbound BLE writes from the phone

No heartbeats

No ACK watchdogs

No reconnect attempts

No reconnect-related GATT refreshes

No state churn in the app


Only natural device-origin notifications are allowed:

Vitals (0x2C)

Case / in-case / proximity (0x2B)


Moncchichi currently never reaches this true idle state, even after earlier patches.
This document defines exactly how to align Moncchichi with Even.


---

============================================

1. Ground Truth vs Current Behavior
============================================



1.1 Even Reality – Idle NRF Profile (Ground Truth)

Scenario:

Glasses folded, in case, lid open, not charging, no gestures, phone left idle.


Observed:

No host PING (0x25)

No host ACK requests

No reconnect attempts

No CCCD writes, no GATT refreshes

No new commands from phone


Traffic consists only of:

Infrequent vitals (0x2C) from the glasses

Occasional case / in-case / proximity notifications (0x2B) when physical state changes


App logging:

Essentially silent except for:

Case transitions (e.g. CaseOpen → CaseOpen when lid jiggles)

Vitals parsing if surfaced



No:

[BLE] ❤️ Keepalive

ERR / ACK stalled

reconnect / degraded logs


Even stays connected over GATT but does not talk unless the headset wakes or the user interacts.


---

1.2 Moncchichi – Idle NRF Profile (Current Behavior)

Same scenario (folded + in case + lid open + not charging + idle):

Moncchichi currently:

Still sends PING (0x25)

Still expects ACKs

Still logs keepalive “ERR” and stalled ACKs

Still evaluates degraded/reconnect conditions

May still schedule reconnects

May trigger GATT refreshes

Continues to push command or refresh traffic under some paths


Moncchichi logs:

[BLE] ❤️ Keepalive

[WARN][ACK] stalled or similar

Reconnect attempts and degraded-state logs

Telemetry refresh logs


Conclusion:
Moncchichi’s stack never fully transitions into true IdleSleep.
Multiple layers stay “half-awake”.


---

============================================
2. Root Cause Analysis (By Layer)

Four core layers participate in sleep/wake:

BleTelemetryRepository

G1BleClient

MoncchichiBleService

DualLensConnectionOrchestrator


Each has drift from Even.


---

2.1 BleTelemetryRepository – Sleep Truth Source (Currently Incomplete)

Problems:

Sleep detection does not match Even’s exact condition.

Missing or underused fields:

foldState

inCase

caseOpen

charging

lastVitalsTimestamp (for quiet-window detection)


isLensSleeping() and isHeadsetSleeping() either:

Never return true, or

Use conditions that do not match Even’s idle behavior.


No robust two-phase quiet detection before declaring sleep.

No single unified event stream for:

SleepEvent.SleepEntered

SleepEvent.SleepExited



Result:

Downstream stacks rarely (or never) see a valid SleepEntered event aligned with Even’s real idle.



---

2.2 G1BleClient – Heartbeat / ACK / Command Pipe

Problems:

Heartbeat coroutine is not reliably fully suspended during sleep.

ACK timers and watchdog stubs may still be active.

Host still schedules PINGs or expects ACKs in some paths.

ERR/stall logs can still occur during what should be idle sleep.

Command sending infrastructure is not fully gated by sleep state.


Result:

The phone continues to “poke” the headset when Even would be fully silent.



---

2.3 MoncchichiBleService – Global Coordination

Problems:

No strict, global IdleSleep gate around:

Heartbeats

Reconnect coordinator

Degraded detection

Telemetry refreshes

Command dispatch pipeline


Sleep/wake logs deviate from Even’s formatting and semantics:

Should be:

[SLEEP] Headset → IdleSleep

[WAKE] Headset → Awake



Still allows state transitions and degraded evaluation while headset should be asleep.


Result:

Headset appears “unstable” instead of “intentionally silent”.



---

2.4 DualLensConnectionOrchestrator – State Machine

Problems:

IdleSleep state exists but:

Reconnect loops may still be scheduled around it.

Telemetry refresh may still run.

Command dispatch / pending mirror flush may still occur.


Not strictly enforcing “frozen core” semantics during IdleSleep.

Wake transitions may immediately trigger reconnect/refresh side effects rather than deferring to service-level decisions.


Result:

The orchestrator behaves “semi-active” instead of “frozen core” like Even.



---

============================================
3. Correct IdleSleep Definition (Aligned With Even)

3.1 Lens-Level Sleep Condition

A single lens is considered “sleeping” when all of the following are true:

foldState == FOLDED

inCase == true

caseOpen == true  (lid open idle mode)

charging == false

No new vitals/telemetry beyond periodic baseline for at least:

QUIET_WINDOW_MS per phase (see two-phase quiet requirement below)



3.2 Two-Phase Quiet Detection (From Even Logs)

Even does not drop into idle on the first short quiet period.
Observed behavior suggests:

Phase 1: First quiet window

Only low-frequency vitals/case notifications from glasses.


Phase 2: Second quiet window

Continued lack of host traffic and only minimal device-origin vitals.



Requirement:

A lens must pass two consecutive quiet windows without unexpected traffic before being marked as “sleeping”.

lastVitalsTimestamp and event timestamps should be used to implement this.


This avoids false positives when:

Case is jostled

Short-term noise occurs

User briefly interacts then stops


3.3 Headset-Level IdleSleep

The headset is considered in IdleSleep when:

isLensSleeping(LEFT) == true

isLensSleeping(RIGHT) == true

Two-phase quiet condition satisfied for both lenses


Only then:

Emit SleepEvent.SleepEntered (headset-level)

Transition orchestrator to IdleSleep state

Activate global gating (see Sections 4.2–4.4)


3.4 Stay Connected Requirement

Critical: Even does NOT disconnect GATT during IdleSleep.

Therefore:

IdleSleep MUST never:

Call disconnectGatt() for sleep reasons

Drop BLE link purely because of idle

Force a reconnect cycle due to lack of host traffic



GATT link should remain:

Bonded and connected

Notifications fully armed

Ready to immediately resume activity on wake



---

============================================
4. Required Changes – All Layers

============================================
4.1 BleTelemetryRepository – Sleep/Wake Truth Engine

Responsibilities:

Track sleep-related fields with timestamps:

foldState

inCase

caseOpen

charging

lastVitalsTimestamp (updated only on vitals)

Optional: lastCaseEventTimestamp


Provide helpers:

isLensSleeping(lens: Lens): Boolean

isHeadsetSleeping(): Boolean


Implement two-phase quiet detection:

Maintain per-lens internal state for quiet phase:

ACTIVE

QUIET_PHASE_1

SLEEP_CONFIRMED


Transitions based on:

No unexpected device events

Only baseline vitals / case notifications

Elapsed time since last non-baseline event



Emit events:

SleepEvent.SleepEntered (headset-level, once when both lenses reach SLEEP_CONFIRMED)

SleepEvent.SleepExited (on any wake condition, see below)



Wake Definition (Headset-Level)

SleepEvent.SleepExited should fire when any of the following occur:

foldState changes from FOLDED → UNFOLDED for any lens

inCase changes from true → false for any lens

caseOpen changes in a way that indicates active use (e.g. close/open sequence)

charging state changes from false → true (depending on firmware semantics)

Significant new telemetries suggesting active wear / movement (as present in vitals)


After wake:

Reset quiet tracking state.

Clear SLEEP_CONFIRMED for both lenses.

isHeadsetSleeping() must return false until conditions re-satisfy.



---

============================================
4.2 G1BleClient – Full Silence During Sleep

When the headset is in IdleSleep:

Heartbeat coroutine must be fully suspended:

No scheduling of PING (0x25)

No keepalive frames sent

No new ACK expectations


ACK watchdog must be fully suspended:

No “ACK stalled” evaluation

No “ERR” logs for missing ACKs

No reconnect triggers from heartbeat failures


Command sending must be blocked for host-initiated traffic:

Any commands originating from UI, AI, or other components must be:

Either dropped with a clean debug log, or

Marked as “rejected due to sleep” (internally)


Commands MUST NOT:

Wake the headset

Force a reconnect

Attempt to “kick” the device awake with a write



Mirror/refresh commands must not run while sleeping.


Wake behavior:

Only after SleepEvent.SleepExited:

Resume heartbeat coroutine

Resume ACK watchdog

Allow commands again


No backlog replays:

Do NOT batch-send commands accumulated during sleep.

Commands during sleep should be treated as never-sent.




---

============================================
4.3 MoncchichiBleService – Global Sleep/Wake Gate

Responsibilities:

Subscribe to SleepEvents from BleTelemetryRepository.

Maintain an internal IdleSleep flag for the headset.

Enforce global gating for:

Reconnect coordinator

Degraded/ACK stall logging

Heartbeat start/stop

Telemetry refresh

Command routing



On Sleep Enter

When receiving SleepEvent.SleepEntered:

1. Log exactly:

[SLEEP] Headset → IdleSleep



2. Set headset-level IdleSleep flag = true.


3. Actions:

Pause / freeze reconnect coordinator:

No new reconnect attempts while sleeping.


Suppress degraded / ACK-stall checks:

Do not interpret absence of traffic as instability.


Instruct all G1BleClient instances to:

Suspend heartbeat and ACK logic.

Reject host commands.


Suspend or gate telemetry refresh calls:

No active case/battery refresh commands.


Keep GATT connected:

Do not disconnect just because we are sleeping.





On Sleep Exit (Wake)

When receiving SleepEvent.SleepExited:

1. Log exactly:

[WAKE] Headset → Awake



2. Clear headset-level IdleSleep flag.


3. Actions:

Resume reconnect coordinator:

If any lens is disconnected, then schedule reconnect.


Instruct G1BleClient instances to:

Resume heartbeat and ACK handling.


Trigger a single telemetry refresh:

Case, battery, wear state, firmware info.


Allow UI/assistant to issue commands again.




Notes:

Wake should come from device-origin change (fold/unfold, case, etc.), not from host-side writes.

GATT disconnections should only occur due to:

Android stack decisions

Explicit user-triggered disconnect




---

============================================
4.4 DualLensConnectionOrchestrator – Hard Idle Freeze

State machine updates:

Introduce/maintain IdleSleep state at headset level.

Transitions:

Stable → IdleSleep when SleepEvent.SleepEntered fires.

IdleSleep → Stable when SleepEvent.SleepExited fires.


If wake reveals a disconnection:

IdleSleep → RecoveringLeft / RecoveringRight (per lens) as appropriate.



While in IdleSleep state:

Disallow:

Reconnect loops

Priming / re-priming

Telemetry refresh scheduling

Pending mirror flushes

Non-user-initiated command dispatch


Allow:

Inbound telemetry from BLE:

Vitals

Case / in-case notifications



Must not:

Trigger state churn or log noise beyond:

Sleep/ wake events

Optional vitals/case logs




Commands while in IdleSleep:

Orchestrator must forward a “sleep gate” decision:

Either drop commands or mark them as rejected.

Must not cause immediate wake or reconnect.




---

============================================
5. Expected NRF Profile (Target Behavior)

After Phase 5.2 is correctly implemented:

During Idle Sleep

No host-side:

PING (0x25) writes

ACK requests or command writes

CCCD/descriptor writes

GATT refresh sequences

Reconnect attempts

Heartbeat logs or ERR logs


Only device→host notifications:

Vitals (0x2C) at low frequency

Case/in-case (0x2B) on physical changes


Logs:

One-time (per sleep episode):

[SLEEP] Headset → IdleSleep


Then only:

Optional [CASE] / [VITALS] logs echoing inbound frames


No reconnect or ACK-stall warnings.



On Wake (Unfold, case change, etc.)

Logs:

[WAKE] Headset → Awake


Behavior:

Heartbeat resumes

ACK watchdog resumes

Reconnect coordinator resumes (only if lens actually disconnected)

Telemetry refresh runs once

Command pipeline re-enabled



This profile should match Even Reality’s e L.txt / e R.txt idle logs.


---

============================================
6. Acceptance Criteria

Codex must ensure:

1. Exact Idle Silence

No outbound BLE traffic from phone during sleep.

No heartbeats, no PING, no reconnect, no GATT refresh.

No [BLE] ❤️ Keepalive or ERR logs during true idle.



2. Sleep/Wake Interpretation

SleepEvent.SleepEntered only when both lenses satisfy:

folded + in case + case open + not charging + two-phase quiet.


SleepEvent.SleepExited on any fold/in-case/case/charging change indicating use or movement.



3. GATT Stability

BLE link remains connected during IdleSleep whenever firmware/Android allows.

No intentional disconnect because of idle state.



4. Command Behavior

Commands during sleep are not sent and do not wake the device.

After wake, commands flow normally.



5. Log Parity

Logs match Even semantics:

[SLEEP] Headset → IdleSleep

[WAKE] Headset → Awake


No degraded/ACK-stall spam during idle.





---

============================================
7. File-Level Responsibilities (For Codex)

File	Responsibility

BleTelemetryRepository	Track fold/case/inCase/charging/vitals; detect sleep/wake; emit SleepEvents
G1BleClient	Gate heartbeat, ACK watchdog, and commands during sleep
MoncchichiBleService	Global sleep/wake gate: reconnect, degraded, telemetry, logging
DualLensConnectionOrchestrator	Implement and enforce IdleSleep state freeze; controlled wake transitions



---

============================================
8. Do Not Touch

To avoid regressions, Codex must not modify:

UI / pairing dialogs

AI assistant layers

Developer console UI components

Context-engineering documents or parsers

Any non-BLE business logic



---

End of CE 5.2 (Rev C) – Idle Sleep Parity

============================================
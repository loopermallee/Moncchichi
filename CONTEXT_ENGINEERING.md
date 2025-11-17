CONTEXT_ENGINEERING – Phase 5.x

Idle Sleep Parity with Even Reality (No-traffic Sleep State)


---

1. Scope and Goal

Goal:
When the G1 is folded, in the case, lid open, not charging, no gestures, Moncchichi Hub must behave identically to the Even Reality app:

No periodic heartbeats / PINGs

No reconnect loops or bond maintenance

No ACK stall spam

Only minimal, event-driven traffic (case/vitals)

Clear, Even-style console logs for SLEEP/WAKE


This patch is behavioral only (no UI changes, no AI changes). It alters how the BLE stack behaves during sleep/idle vs awake/active.


---

2. Ground Truth – What Even Reality Does When Idle

From the Even Reality nRF logs (for the context: glasses folded, in case, lid open, not charging, no user actions):

1. No Heartbeats in Sleep

Even does not send PING / keepalive frames when the glasses are folded and idle.

There are no periodic 0x25 “host heartbeat” packets in this state.



2. No Reconnect / Bond Maintenance in Sleep

Once connected and idle, Even does not:

constantly refresh GATT

constantly reconnect

constantly rebond


It sits in a stable, passive state waiting for wake events.



3. Minimal Telemetry Traffic

While idle/sleeping, traffic is almost zero.

Occasional packets only when:

case state changes (lid open/close, in-case/out-of-case)

power/charge changes

a deliberate app action occurs


No repetitive traffic just because time passes.



4. Sleep vs Wake Trigger Sources

Sleep is implied by combination of:

fold state

in-case / out-of-case

case lid

vitals updates going quiet


Wake is triggered when any of these change:

case lid closes / opens

fold/unfold state changes

in-case/out-of-case changes

button/gesture / power events

app explicitly sends commands





Even’s model is: “When the glasses are sleeping, the host must shut up.”


---

3. Current Moncchichi Behavior (Post Patch 5.3)

From the latest moncchichi-log.txt and nRF logs in the same idle scenario:

1. Heartbeats Continue During Sleep

Moncchichi still logs messages like:

[BLE] ❤️ Keepalive → ERR


These occur periodically even when the glasses are folded and idle.

That means the heartbeat coroutine is still running against a sleeping device.



2. ACK Supervision Continues During Sleep

You see:

[WARN][L][ACK] stalled

[WARN][R][ACK] stalled


These are caused by expecting ACKs from a device that is intentionally not responding because it is sleeping.

Even does not treat sleep as an error; your app currently does.



3. Reconnect / Bond Noise While Idle

Logs indicate:

[PAIRING] bonded

[PAIRING] bond transitions=…

GATT refreshes after disconnects, etc.


This is happening without any user interaction or state change, just idle time passing.

Even Reality does not do such bond maintenance in this idle sleep scenario.



4. Sleep Telemetry Underused

Vitals already report fields like caseOpen / inCase / etc. (via the protocol parser).

But these fields are not yet being used to drive a global “sleep/awake” flag that gates:

heartbeat

reconnect

ACK interpretation


As a result, the app continues acting like the glasses are “awake but misbehaving,” instead of “asleep and healthy.”





---

4. Desired Behavior – Sleep/Awake Model

We need a single, coherent “awake vs sleeping” model used by all BLE layers.

4.1 Define a Sleep State (Per Lens + Global)

Introduce a logical “sleep state” derived from telemetry:

Per lens:

foldState (folded / unfolded)

inCase

possibly wearDetect / proximity / etc.

last vitals timestamp


Case:

caseOpen (lid)

case battery / charging



Simplified rules (conceptual logic, not exact code):

A lens is “sleeping” when:

it is folded AND

it is in case AND

case is not actively charging AND

there has been no recent “active” event for some period.


The pair (global headset) is “sleeping” when:

both lenses are sleeping OR

any protocol-defined “sleep” flag is received.



When the headset is in sleep:

Host must:

stop sending periodic heartbeats / PINGs

stop scheduling reconnect loops

stop treating missing ACKs as errors

just sit and wait for wake signals.



4.2 Wake Conditions

Any of the following should transition from sleeping → awake:

case lid changes (open/close)

inCase/outOfCase changes

fold/unfold changes for either lens

any new vitals packet

explicit user command from the app (user opens Developer console and hits “Send Test Ping”, etc.)

gesture or button events


On wake:

Host may:

resume heartbeats (if the connection is alive)

schedule reconnects if the link is actually down

refresh telemetry (case + battery + wear detect)




---

5. Module Responsibilities

Now, distribute this behavior cleanly across the existing architecture.

5.1 BleTelemetryRepository – Sleep Signal Source of Truth

Current role:

Tracks per-lens vitals (battery, RSSI, etc.).

Tracks heartbeat metrics.


We need it to also:

1. Track vital sleep-related fields

caseOpen

inCase

foldState / wear detect if available

lastVitalsTimestamp (per lens and/or global)



2. Expose a queryable sleep/awake state

E.g. helpers (concept only):

isLensSleeping(lens)

isHeadsetSleeping()


Or: an explicit enum SleepState = { Awake, Sleeping, Unknown }.



3. Emit sleep/wake transitions

When telemetry changes from “awake conditions” to “sleep conditions”:

push an event like SleepEvent.SleepEntered.


When it changes from “sleep conditions” to “awake conditions”:

push SleepEvent.SleepExited.




4. Never decide reconnection policy

It only reports state; it does not directly start/stop reconnects or heartbeats.

That’s for higher layers.




5.2 G1BleClient – Heartbeat Must Respect Sleep

Current behavior:

Once notifications are armed and setup is completed, it starts a heartbeat loop (PING + wait for ACK).

On failure / no ACK, it triggers error handling / reconnect.


Wanted behavior:

1. Heartbeat only when “awake”

G1BleClient must consult a sleep flag / callback before enqueuing heartbeats.

If the headset is sleeping, it does not send PING at all.

No PING → no “ERR” spam, no false stalls.



2. Cancel or suspend heartbeat when sleep begins

If the lens transitions to sleep:

stop the heartbeat coroutine or set a flag to prevent further PING writes.


If already waiting for an ACK on a PING, and sleep is detected:

treat it as “cancelled due to sleep,” not as a connection error.




3. Resume heartbeat on wake

When wake is signaled:

resume heartbeat logic (if connection still valid).


If connection was lost during sleep:

do not spam; let the higher layer orchestrator decide when to reconnect after wake.





5.3 MoncchichiBleService – Global Sleep/Wake Orchestrator

Current behavior:

Manages ReconnectCoordinator.

Emits stability metrics.

Logs “[PAIRING]” / “[BLE]” / “[ACK]” events.

Tracks handshake state (LINK/ACK) and logs:

[LINK][L] Connected

[ACK][L] OK received

etc.



Required behavior additions:

1. Track and log sleep/wake transitions

Subscribe to sleep events from BleTelemetryRepository.

On sleep entry:

log:

[SLEEP] Headset → IdleSleep


increment counters if needed (e.g. number of sleep cycles).


On wake:

log:

[WAKE] Headset → Awake


reset any relevant timers or counters.




2. Gate reconnect coordinator on sleep

While in sleep:

ReconnectCoordinator should not schedule new reconnects.

If a reconnect loop is running, it should be paused or aborted gracefully.


On wake:

if any lens is actually disconnected, then schedule reconnects.




3. Do not treat sleep as instability

Do not mark stability metrics as degraded just because ACKs stop when sleeping.

Sleep should be tracked separately from “bad signal / unstable GATT.”




5.4 DualLensConnectionOrchestrator – State Machine Needs IdleSleep

Current state machine already has states like:

Idle

ConnectingLeft / ConnectingRight

LeftOnlineUnprimed / RightOnlineUnprimed

ReadyRight

ReadyBoth

Stable

DegradedLeftOnly / DegradedRightOnly

RecoveringLeft / RecoveringRight

Repriming


We need to introduce and properly use IdleSleep:

1. New state: IdleSleep

Represents: “Both lenses are logically connected or connectable, but sleeping; host must minimize traffic.”

This is not a failure state, it’s a rest state.



2. Transition rules

From Stable or ReadyBoth → IdleSleep:

when BleTelemetryRepository reports the headset has entered sleep.


From IdleSleep → Stable (or ReadyBoth):

when telemetry reports wake conditions.


If wake reveals an actual disconnect:

from IdleSleep → RecoveringLeft/RecoveringRight and trigger reconnect.




3. Behavior while in IdleSleep

Do not call scheduleReconnect as long as sleep persists.

Do not send any host-initiated PING or refresh commands (except on explicit user action).

Maintain sessions but essentially “do nothing” until wake or explicit command.





---

6. Logging Expectations – What Good Looks Like

When the glasses are folded in the case, lid open, not charging, no gestures:

Moncchichi log should show:

A final burst of connection + handshake logs:

[LINK][L] Connected

[ACK][L] OK received

[LINK][R] Connected

[ACK][R] OK received

[SEQ] L:HELLO→OK→ACK | R:HELLO→OK→ACK


Possibly some initial case/vitals prints.

Then, when sleep inferred:

[SLEEP] Headset → IdleSleep



After that:

No [BLE] ❤️ Keepalive → ERR

No [WARN][ACK] stalled

No [RECONNECT] or [PAIRING] spam


When user opens the case, unfolds, or interacts:

[WAKE] Headset → Awake

Followed by:

heartbeat resuming or reconnects if genuinely needed.




This should look visually similar to Even’s logs:

active handshake & initial telemetry, then silence, then wake logs.



---

7. Testing Plan (Manual)

Use this test scenario to confirm parity:

1. Initial Pair & Connect

Pair glasses with Moncchichi.

Confirm:

left then right handshake

LINK/ACK/SEQ logs as expected.




2. Put into Sleep State

Fold both lenses.

Place into case; lid open.

Ensure not charging.

Leave untouched for at least 3–5 minutes.



3. Observe Logs

Verify:

one-time [SLEEP] log.

no further heartbeats / ACK stalls / reconnect logs.


Cross-check with nRF logs:

no host PING traffic during sleep.




4. Wake Test

Open case lid, remove glasses, unfold.

Verify:

[WAKE] log.

heartbeat resumes only after wake.

reconnect only if connection was genuinely lost.




5. Compare with Even Reality

Repeat the same scenario with Even app.

Ensure the nRF logs for Even and Moncchichi look structurally identical:

same “silence profile” during sleep.

similar bursts on wake.
**CONTEXT_ENGINEERING.md — Phase 5.2

Even Reality Idle Sleep Parity (Authoritative Spec)**

Goal:
Make Moncchichi Hub behave exactly like the Even Reality G1 official app when the glasses are:

Folded

In the case

Case lid open

Not charging

Idle for 3–5+ minutes


This state must result in identical idle behavior:

Zero PINGs

Zero keepalives

Zero reconnect attempts

Zero ACK waiting

Zero ERR logs

Fully stable connection

No GATT churn

Only quiet periodic vitals from each lens


This spec is derived from:

Your uploaded Even Reality nRF logs (eL.txt, eR.txt)

Your uploaded Moncchichi logs

Even Reality’s G1 Protocol readme

Even Reality app traces in v1.6.6

The Teleprompter repo and developer notes


All wording below is literal expected behavior, not a suggestion.


---

🔥 1. Exact Behavior Observed in Even Reality App

1.1 No host-initiated traffic during idle

In idle case mode, Even app sends:

0 PING frames

0 keepalives

0 write commands

0 status requests

0 mirror refresh

0 priming attempts


The host is completely silent.

Only the glasses speak.


---

1.2 The glasses still send vitals

Frames observed:

0x2C — Battery/vitals

0x2B — Case state / in-case

0x37 — Folded / proximity

Occasional 0x0B, 0x4F, etc.


Even app does not reply or treat these as errors.

It only logs them.


---

1.3 Sleep state is triggered by environmental signals

Even enters idle after:

Folded == true

inCase == true

caseOpen == true

charging == false

No gesture frames

Quiet vitals-only pattern for ~10–20s


It then:

freezes all GATT activity

suspends reconnect system

suspends heartbeat / PING

suppresses all ACK logic

suppresses connection evaluation


Even’s internal state stops moving.


---

1.4 Wake events bring the app back online

Wake signals include:

Unfold

Case lid closed then opened

USB power applied

Button gesture

Change in case state

Rapid vitals sequence


Upon wake:

Heartbeat resumes

Reconnect logic re-arms

ACK logic returns

State machine moves to Stable


And then, if any lens is disconnected, Even starts reconnect.


---

🧠 2. Root Cause: Why Moncchichi Does NOT Match Even

2.1 Moncchichi still performs host-driven traffic

Logs show:

background heartbeat scheduling

pending PINGs

ACK stall warnings

reconnect attempts during idle

unnecessary “RecoveringLeft/Right” logs


Even app does not do any of these.


---

2.2 Moncchichi does NOT fully enter idle sleep state

Moncchichi triggers:

state machine transitions

reconnect coordinator

ACK retry logic

evaluation of degraded states


Even app completely freezes activity during sleep.


---

2.3 Heartbeat is still active

Even app: absolutely zero outbound packets.
Moncchichi: heartbeat coroutine still runs.


---

2.4 G1BleClient keeps ACK timers active

Even app disables the entire ACK subsystem during idle.


---

2.5 Moncchichi logs noise that Even never produces

Examples:

[BLE] ❤️ Keepalive sent

[WARN][ACK] stalled

[LINK] reconnect scheduled

[STATE] transitioning…

[CLIENT] write command…


Even app’s logs during idle contain:

vitals

case state

battery

timestamps


Nothing else.


---

🧩 3. Requirements for Phase 5.2

This is the authoritative implementation spec.

Codex must implement every item without deviation.
Nothing extra. No optimizations. No improvements.
Just mimic Even Reality perfectly.


---

✅ 4. Sleep Detection Requirements

Add to BleTelemetryRepository:

4.1 Required fields

caseOpen: Boolean
inCase: Boolean
foldState: Boolean
charging: Boolean
lastVitalsTimestamp: Long

4.2 Required helper methods

isLensSleeping(lens)
isHeadsetSleeping()

Definition of sleep (match Even):

A lens is considered sleeping when:

folded = true

inCase = true

caseOpen = true

charging = false

AND (now - lastVitalsTimestamp) > quietThreshold

Even’s quiet threshold ≈ 10–20s



The headset is sleeping when both lenses are sleeping.


---

4.3 Emit Sleep/Wake events

Repository must emit:

SleepEntered
SleepExited

Events fire only on meaningful transitions.


---

🔥 5. IdleSleep Mode (Core Requirement)

This is the heart of Phase 5.2.

When headset enters sleep:


---

5.1 G1BleClient

STOP ALL HEARTBEATS

suspend heartbeat job
suspend ACK timers
do not send PING
do not log ERR when no ACK

No outbound BLE writes.


---

5.2 MoncchichiBleService

Upon receiving SleepEntered:

log: [SLEEP] Headset → IdleSleep

disable reconnect engine

disable degraded state evaluation

disable priming

disable refresh

suppress ACK warnings

suppress link instability logs


Upon wake:

log [WAKE] Headset → Awake

re-enable heartbeat

re-enable reconnect logic

if any lens is disconnected → begin reconnect



---

5.3 DualLensConnectionOrchestrator

Add new state:

IdleSleep

State transitions:

Stable → IdleSleep       when SleepEntered
IdleSleep → Stable       when SleepExited
IdleSleep → RecoveringX  only if wake reveals disconnection

While in IdleSleep:

Do NOT send ANY commands

Do NOT start reconnect

Do NOT run mirror refresh

Do NOT run priming

Do NOT evaluate degraded state

Allow only inbound notifications from glasses


This must match Even Reality behavior exactly.


---

🔕 6. Logging Expectations (Match Even Reality)

During sleep:

NO heartbeat logs

NO ERR logs

NO reconnect logs

NO GATT busy logs

NO “stalled ACK” logs


Allowed logs:

vitals

case state

occasional telemetry events


Required logs:

[SLEEP] Headset → IdleSleep
[WAKE] Headset → Awake

Absolutely nothing else.


---

🎯 7. Acceptance Criteria (Codex MUST use this)

Moncchichi is considered compliant when:

7.1 Idle State Behavior

No PING sent for entire idle window

No reconnect triggered

No warnings logged

State machine frozen at IdleSleep

Connection stable for 5–10 minutes

Only vitals/case frames visible in nRF Connect

Logs identical to Even app idle logs


This must be verifiable via:

nRF Connect LEFT

nRF Connect RIGHT

Moncchichi Developer Console



---

7.2 Wake Behavior

When user unfolds glasses / closes the case / taps:

wake event fires

heartbeat reactivates

reconnect logic reactivates

ACK logic reactivates

state machine transitions from IdleSleep → Stable


Matches Even.


---

🧪 8. Test Scenarios Codex Must Validate Internally

Even if Codex cannot run Android, it must logically verify:


---

8.1 Idle Test

Scenario:

glasses folded

in case

case lid open

not charging

3–5 min quiet


Expected:

SleepEntered

IdleSleep state active

zero traffic

zero PING

zero reconnect

light vitals only



---

8.2 Wake Test

Trigger wake by:

unfolding

closing/opening case

connecting USB

pressing a button


Expected:

SleepExited

resume normal flow

heartbeat resumes

reconnect functioning



---

8.3 Stability Test

Glasses remain idle for 10 minutes.

Expected:

connection never drops

no GATT resets

no reconnect triggers



---

🏆 Summary for Codex

Your goal is not to improve Moncchichi.

Your goal is to clone Even Reality’s idle behavior exactly, byte-for-byte, state-for-state, timing-for-timing.

Phase 5.2 success =
Moncchichi’s idle logs match Even Reality’s idle logs with zero extraneous traffic.


---

✔️ END OF DOCUMENT
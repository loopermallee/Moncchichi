CONTEXT ENGINEERING DOCUMENT — SLEEP / VITALS PIPELINE & STATE MACHINE ALIGNMENT

Objective

Codex must understand exactly how Even Reality’s app transitions glasses into “idle sleep” when placed in the case (folded), and how your Moncchichi app currently fails to do so — causing:
	•	infinite scan loops
	•	no caseOpen / inCase state
	•	no sleep suppression
	•	handshake stalling
	•	no vitals flowing

Codex must later implement patches without guessing, and without altering Even’s behaviour.

⸻

1. What Even Reality’s App Actually Does (verified via your Even logs)

Even’s workflow:

1.1 LEFT is always the primary lens
	•	Even initiates LEFT first.
	•	LEFT handshake must complete fully before RIGHT starts.
	•	Both vitals & sleep-state notifications only begin once LEFT is “primed”.

1.2 Case state and fold state are transmitted as regular GATT NOTIFY events

These events come from 0x0E / 0x0F “Case” opcodes in the EvenRealities BLE protocol.

Even logs show:

NOTIFY: 0E 01 → CaseOpen=true
NOTIFY: 0E 00 → CaseOpen=false
NOTIFY: 0F 01 → InCase=true

These arrive continuously while connected.

1.3 Even uses these signals to transition to “IdleSleep”

Conditions:
	•	caseOpen != null
	•	inCase != null
	•	fold state from wear detect (0x?? opcode in G1 protocol)

If all 3 indicate sleep, Even:
	•	stops scanning
	•	stops reconnects
	•	stops keepalive
	•	stops sending commands
	•	logs [SLEEP]
	•	enters a persistent Idle state
	•	only wakes on:
	•	case opened
	•	lid opened
	•	fold changed

⸻

2. What Moncchichi Currently Does Wrong (from your latest logs)

2.1 Moncchichi never receives (or processes) case / fold notifications

All telemetry snapshots show:

Case — Unknown  
L — –  
R — –  
FW — –  
Up — –

Meaning:
	•	BleTelemetryRepository.snapshot.left.caseOpen is always NULL
	•	subtree for vitals never updates
	•	G1ReplyParser never emits caseOpen/inCase in runtime
	•	notifications may be blocked because LEFT handshake stalled

2.2 LEFT never reaches warmup / notifyReady

Your log shows:

[WARN][L][ACK] stalled

This means:
	•	HELLO OK ACK is incomplete
	•	MTU ACK is not received
	•	notifyReady never flips to TRUE
	•	CCCD enabling may be incomplete
	•	GATT notifications are never actually flowing
	•	therefore no vitals
	•	therefore no case state
	•	therefore orchestrator never sees sleep conditions

2.3 DualLensConnectionOrchestrator never reaches “ReadyLeft” or “ReadyBoth”

Because:
	•	LEFT never becomes primed
	•	RIGHT handshake is blocked “correctly” (because you match Even)
	•	But then LEFT stays unprimed, so state machine never advances

2.4 Without caseOpen/inCase signals, Moncchichi never enters IdleSleep

Your service + orchestrator patches rely on:

if (telemetry.isSleeping()) enter IdleSleep()

But because telemetry never receives caseOpen/inCase:
	•	isSleeping() always returns false
	•	reconnect loops continue
	•	scan loops continue
	•	heartbeat never shuts off
	•	app keeps “hunting” for glasses even when they’re in the case

2.5 G1BleClient heartbeat gate is correct — but sleep state is never propagated

You added:
	•	heartbeat suppressed during sleep
	•	ackTimeOut suppression
	•	keepalive cancellation

But because telemetry never says “sleep”, these never activate.

⸻

3. Required Behaviour (MUST replicate Even Reality)

Codex must internalize the following:

3.1 The entire sleep pipeline depends on 4 independent flows

Layer	What Must Happen	Source
GATT Notifications	receive opcodes 0x0E/0x0F (case), 0x18 (wear detect), battery, vitals	G1 BLE protocol
G1ReplyParser	decode → map: caseOpen, inCase, foldState into DeviceVitals	evendemo repo
BleTelemetryRepository	store values + timestamp; compute sleeping = true/false	your app
MoncchichiBleService	translate sleeping → service state; short-circuit reconnect/heartbeat	your app

If ANY ONE of these is broken → entire sleep system collapses.

3.2 LEFT must complete HELLO → OK → ACK before notifications flow

This is both:
	•	a G1 hardware requirement
	•	AND Even’s app logic

If LEFT does not reach primed:
	•	notifyReady remains false
	•	CCCD enabling may not complete
	•	vitals will NEVER be delivered

3.3 Case/fold/vitals packets are ONLY delivered after warmup

Verified in Even logs AND G1 protocol documentation.

⸻

4. What Codex must know about your current implementation

4.1 G1BleClient.notifyReady works — but LEFT handshake stalls

Meaning:
	•	notifications never fire
	•	sleep does not propagate
	•	vitals do not update

4.2 G1ReplyParser likely does not decode OPCODE 0x0E/0x0F in runtime

Even though:
	•	you added fields to telemetry repo
	•	you added logging
	•	you added sleep detection

…the parser itself never feeds these values into the repository.

4.3 BleTelemetryRepository.sleepState never returns true

Because:
	•	no data is ever stored
	•	isSleeping() conditions never satisfied

4.4 MoncchichiBleService never receives sleep events

Because telemetry never changes state.

4.5 DualLensConnectionOrchestrator never stops scanning or reconnecting

Because:

isSleeping() == false

at all times.

⸻

5. Required Behaviour Constraints Codex MUST follow

Codex must implement patches consistent with:

5.1 G1 Protocol
	•	OPCODE 0x0E: Case open/closed
	•	OPCODE 0x0F: Lens in case
	•	Wear detect (opcode varies by firmware, but exists in G1ReplyParser from evendemo)

5.2 evendemo app

Specifically:
	•	G1ReplyParser.kt
	•	G1Status.kt
	•	G1BleManager.kt
	•	EvenStatusAggregator.kt

5.3 Exact connection behaviour from Even
	•	LEFT → RIGHT sequencing
	•	notifyReady check
	•	warmup ACK required before reading vitals
	•	accept textual “OK” responses
	•	sleep = no keepalive, no reconnect, no scanning
	•	wake = re-enable scanning

5.4 No design invention

Codex must:
	•	not infer
	•	not “improve”
	•	not optimize
	•	follow Even packet flow exactly

⸻

6. What Codex must achieve (outcome of the patch)

When lenses are folded in case:

Your app MUST behave exactly like Even:
	1.	LEFT handshake completes
	2.	Vitals flow starts
	3.	caseOpen/inCase/foldState appear in telemetry
	4.	telemetry.isSleeping() becomes TRUE
	5.	MoncchichiBleService logs:

[SLEEP] CaseClosed→Sleep


	6.	Orchestrator enters IdleSleep
	7.	scanning loops stop
	8.	reconnect logic stops
	9.	G1BleClient stops PING entirely

When user opens lid or unfolds glasses:
	1.	new notifications arrive
	2.	telemetry transitions from sleep → awake
	3.	service logs

[WAKE] CaseOpen→Active


	4.	orchestrator resumes LEFT-first connection
	5.	G1BleClient handshake restarts normally

⸻

7. Key failures Codex must fix (but no code here)

Codex must understand the defects:

7.1 LEFT warmup handshake doesn’t complete → notifications blocked

This is the root cause.

7.2 Parser never forwards 0x0E/0x0F → vitals

Telemetry never updates.

7.3 Telemetry never derives sleeping state

isSleeping() always false.

7.4 Service never receives sleeping state

IdleSleep never used.

7.5 Orchestrator continues scanning

Because it never gets sleep flag.
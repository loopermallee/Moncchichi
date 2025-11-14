**CONTEXT ENGINEERING — Patch Series 5.x

Sleep / Case / Fold Behavior (Even Reality Parity)**

🎯 Objective

Moncchichi Hub must mirror the exact behavior of the Even Reality G1 App when the glasses:

go into the case,

are folded,

enter sleep mode,

stop sending keepalive ACKs,

stop advertising.


The current Moncchichi logic continues sending keepalive and aggressively reconnects, which deviates from Even’s real behavior.
Even Reality does not reconnect while the glasses are in-case or folded. It goes idle and waits for a wake event.

This document defines the exact rules, thresholds, signals, state transitions, and stopping conditions required to match Even’s behavior exactly.


---

1. 🔍 What Actually Happens on the G1 Glasses (Confirmed by Real nRF Logs)

When the glasses are:

A. In Case + Lid Closed

BLE remains connected for ~3–5 seconds

Then:

Notifications stop

Text “manual exit” (from firmware) may appear

GATT quietly disconnects


The glasses stop responding to pings

The glasses stop advertising

They do not wake unless:

case opens, OR

magnet sensor changes, OR

user picks up glasses (wake gesture)



B. Folded (no charging)

Similar behavior:

A fold gesture (0xF5) is sent

The glasses disconnect

They stop advertising

They remain idle until unfolded or case opened



C. Even Reality’s reaction

Even’s app does NOT:

spam pings

mark ACK failures

trigger reconnect

retry endlessly


Instead, it transitions to:

> Expected Idle (sleep mode).
Stop talking. Wait for wake signals.




---

2. 🔍 What Your App Does Wrong (Current Behavior)

From your own diagnostic logs:

Keepalive (0xF1) continues forever

ACK timeouts accumulate (rtt=n/a)

Console logs show [ERR][PING] timeout repeatedly

Reconnect scheduler fires

GATT reopening attempts loop

LensStatus wrongly enters degraded states

Developer UI shows “unstable” even though glasses slept normally


This is caused by a single missing subsystem:

Your app does not interpret case / folded / vitals state and does not know when to stop communicating.


---

3. 📡 The Signals You MUST Use (All available today)

Your app already receives the required signals through G1ReplyParser → Vitals → BleTelemetryRepository:

Signal	Meaning	Source

caseOpen (Boolean?)	Lid open/closed	Vitals Packet (opcode 0x11)
inCase (Boolean?)	Whether the lens is in the charging case	Vitals Packet
charging	If lens is charging	Vitals Packet
battery%	Irrelevant to sleep logic	Vitals Packet
fold gesture (0xF5)	Folded glasses event	Gesture stream
manual exit (text)	Firmware shutdown of BLE	Raw UART text
No notif for X seconds	Device stopped broadcasting	System-level timer


Even’s app uses EVERY ONE OF THESE to stop BLE activity.

Your app currently uses NONE for sleep-logic.


---

4. 📘 REQUIRED NEW STATE MACHINE

You must introduce Sleep / InCase semantics into:

G1BleClient

MoncchichiBleService

DualLensConnectionOrchestrator

BleTelemetryRepository


Actual Even Reality state model:

WAKE → LINKED → HELLO → OK → ACTIVE  
ACTIVE → (CASE CLOSED / FOLD / SLEEP) → SLEEP  
SLEEP → (CASE OPEN / UNFOLD / MOTION) → AWAKE  
AWAKE → HELLO → OK → ACTIVE

Important:
While in SLEEP, Even:

stops heartbeat

stops all writes

stops reconnect attempts

does NOT reconnect

does NOT refresh GATT cache

does NOT mark ACK errors

remains silent until wake



---

5. 🔧 Required Logic (Codex must implement EXACTLY as written)

5.1. STOP KEEPALIVE when ANY of the following is true:

Condition	Meaning

caseOpen == false	Lid closed
inCase == true	Lens inserted in case
Gesture 0xF5 fold detected	Folded
Charging = false + caseOpen = false	Sleeping
No vitality packets in > 3000 ms	Sleep inferred
“manual exit” text received	Firmware shut down BLE


When any of these are true:

→ Immediately disable heartbeat loop.

→ Do NOT send more PING (0xF1).


---

5.2. SUPPRESS RECONNECT when sleep is detected

Reconnect coordinator must be disabled when:

inCase = true OR caseOpen = false OR folded = true

Instead of reconnecting, set:

ConnectionStage = IdleSleep


---

5.3. RESET on Sleep → No errors

Errors MUST NOT be logged when sleep is intentional.

Convert:

Current behavior	Correct behavior

[PING] rtt=n/a	[SLEEP] CaseClosed
timeout	No timeout; device sleeping
reconnect triggered	reconnect suppressed
DegradedLeftOnly	IdleSleep
attempt 1/2/3	No attempts



---

5.4. Wake-up Behavior (Mirror Even)

Wake is triggered by:

caseOpen = true

inCase = false

firmware sends HELLO again

advertising resumes

gesture: unfold


Wake-up sequence:

RESET both client states  
RESET ack counters  
RESET telemetry  
Restart GATT connect LEFT → RIGHT  
Start HELLO  
Expect “OK”  
Resume heartbeat


---

6. 🧪 Match Even’s Timers EXACTLY

Based on observed logs:

Behavior	Even Timing

GATT teardown after folding	500–1500 ms
Keepalive interval	500 ms
Treat missing ACK > 1000 ms as SLEEP (if case closed)	
HELLO retry spacing	~100–150 ms
Reconnect backoff	NEVER when in-case


Your constants must follow these.


---

7. 🔍 Developer Console Requirements (Match Even)

When sleep is detected, log:

[SLEEP][L] CaseClosed

Or if folded:

[SLEEP][R] Folded

Wake logs:

[WAKE][L] CaseOpen

These must appear before any reconnect or HELLO.


---

8. 📦 Components Codex MUST Update

1. G1BleClient

Track caseOpen, inCase, foldGesture, lastVitalsTimestamp

Disable heartbeat on sleep

Suppress ack error accumulation during sleep

Trigger Sleep event


2. MoncchichiBleService

Add sleep-state for each lens

Suppress reconnect

Suppress degraded state

Add wake detection logic

Add console logs identical to Even's


3. DualLensConnectionOrchestrator

Insert Sleep → Idle state

Do NOT schedule reconnect while sleeping

Restart handshake only on wake → case open


4. BleTelemetryRepository

Propagate inCase/caseOpen/fold reliably

Add timestampOfLastVitals

Provide isSleeping() helper function



---

9. 🎯 Expected End Result (App Behavior)

After implementing this, your app will:

✓ Stop pinging when glasses enter case

✓ Stop logging ping errors

✓ Stop reconnect attempts

✓ Enter IdleSleep like Even

✓ Remain quiet

✓ Wake properly when case opens

✓ Reconnect LEFT→RIGHT identically to Even

✓ Match nRF logs exactly


---

10. ✔ Testing Checklist (Use THIS for validation)

1. Place glasses in case with lid closed
→ App must show [SLEEP][...]
→ No more PING
→ No reconnect
→ No ERR logs


2. Remove glasses from case
→ App wakes
→ Connect LEFT → RIGHT
→ HELLO → OK → ACK


3. Fold the glasses
→ Same sleep behavior


4. Unfold
→ Wake behavior identical to Even


5. nRF Connect logs must match Even’s connection order, timing, and sleep timing.
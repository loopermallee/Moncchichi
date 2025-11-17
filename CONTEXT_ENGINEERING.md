CONTEXT_ENGINEERING – Phase 5.x
Idle Sleep Parity With Even Reality (Folded + In Case + Idle)


---

0. High-level intent (what you’re aiming for)

Make Moncchichi Hub behave exactly like the Even Reality G1 app when the glasses are:

Folded

Sitting in the case

Case lid open

Case not charging

No gestures or user interaction


In this state:

The BLE links must stay connected.

The phone must stop “poking” the glasses (no PINGs, no test commands).

The app must not treat this as an error or instability.

The traffic profile must match Even’s idle logs: only device-initiated notifications, no host writes.


This phase is not about improving battery, connection speed, or UX. It is purely protocol parity.


---

1. Ground truth – What Even actually does when idle

From the Even nRF logs in the “folded + in case + idle” scenario:

The phone keeps the GATT connections open to both lenses.

Traffic characteristics:

No periodic host-initiated Write Command keepalives (no PINGs).

No “test ping”, no command bursts.

Only occasional Handle Value Notification packets from the glasses:

Case state / vitals / wear detect frames.


No repeated disconnect/reconnect.


No indication that the phone is upset about missing ACKs (because it doesn’t expect any).


Interpretation:

Even has a concept of “headset is sleeping”:

It knows the headset is folded + in case + lid open + not charging.

While in that mode, it behaves as a passive listener:

No heartbeats.

No reconnect loops.

No “stability” warnings.




That is the gold standard you must mirror.


---

2. Current Moncchichi behavior – What’s wrong

From Moncchichi logs in the same scenario:

App continues sending periodic heartbeats ([BLE] ❤️ Keepalive).

Because the headset is sleeping and ignores those pings:

You see [BLE] ❤️ Keepalive → ERR.

You see [WARN][ACK] stalled for L/R.

Telemetry shows heartbeat miss counts increasing.

Sometimes reconnect behavior or GATT refresh is triggered.


Telemetry / status snapshot:

Holds battery/RSSI data etc.

But does not track:

caseOpen, inCase, foldState, charging as a combined sleep signal.

lastVitalsTimestamp or a “quiet window”.

A single isHeadsetSleeping boolean.




Net effect:

The physical BLE connection may still be OK.

But your app constantly behaves as if the link is unstable, rather than “asleep”.



---

3. Design goals (must vs must-not)

Must do

Detect sleep at the headset level using vitals/case state.

Enter an explicit IdleSleep state when both lenses are sleeping.

While in IdleSleep:

Suspend heartbeat PINGs and ACK monitoring entirely.

Suppress reconnect logic triggered by missing ACKs.

Keep connections open; no active teardown.


On wake:

Exit IdleSleep.

Resume heartbeats, reconnect logic, and normal stability monitoring.

Refresh left/right telemetry once.



Must NOT do

Must not disconnect purely because the headset is sleeping.

Must not treat sleep as a “degraded” or “error” state.

Must not send any host commands during idle sleep, except:

Explicit user-triggered commands (e.g., if you later allow that).


Must not “optimize” beyond Even’s observable behavior.



---

4. Telemetry changes – BleTelemetryRepository

4.1. New data tracked per lens

Extend the telemetry snapshot / per-lens model to include:

caseOpen: Boolean?

inCase: Boolean?

foldState: FoldState?

Example enum: UNFOLDED, FOLDED, UNKNOWN.


charging: Boolean? (if not already present)

lastVitalsTimestamp: Long?

Timestamp of the last vitals/case/wear notification for that lens.



These should be populated from:

Parsed vitals notifications (e.g., from G1ReplyParser → vitals object).

Any case/wear frames the glasses emit.


4.2. Sleep detection helpers

Add helpers:

fun isLensSleeping(lens: Lens): Boolean

fun isHeadsetSleeping(): Boolean


A reasonable definition (must match Even semantics as closely as the data allows):

For a single lens:

foldState == FOLDED

inCase == true

caseOpen == true  (case lid open)

charging == false or charging == null

lastVitalsTimestamp older than some quiet window (e.g., N seconds)
(This prevents flapping the instant vitals update arrives.)


For headset:

Both LEFT and RIGHT satisfy isLensSleeping(lens).


Codex should centralize this in BleTelemetryRepository so all components use the same logic.

4.3. Sleep events

Introduce a small event stream for sleep/wake transitions, e.g.:

SleepEvent.SleepEntered – headset has just transitioned from non-sleeping → sleeping.

SleepEvent.SleepExited – headset has just transitioned from sleeping → non-sleeping.


Key semantics:

Events are headset-level, not per lens.

Only emit when the boolean isHeadsetSleeping() changes value.

Do not emit repeatedly while state is unchanged.

Keep this free of reconnect logic; it is purely detection.



---

5. Heartbeats – G1BleClient behavior

Right now:

Once notifications are armed and _awake is true, enqueueHeartbeat keeps sending PINGs.

Missing ACKs can cause:

ERR logs.

Reconnect attempts.



Parity requirement with Even:

No PING traffic at all during idle sleep.


Desired behavior:

G1BleClient must be aware of “headset sleeping” (directly or via a callback/flag).

Heartbeat coroutine must be gated by this state:

When isHeadsetSleeping == true:

Do not enqueue new heartbeats.

Cancel/suspend existing heartbeat coroutine.

Do not wait on ACKs.

Do not trigger reconnects or ERR logs because of missing ACKs.


When isHeadsetSleeping == false and the lens is connected/primed:

Heartbeat may run as it does today.

ACK stall and reconnect logic applies only in this awake state.




Implementation options Codex can choose (no code here, just logic):

Option A: MoncchichiBleService informs each G1BleClient when the headset enters/exits sleep via a flag or method, and the client gates its heartbeat loops on that flag.

Option B: G1BleClient subscribes to a StateFlow<Boolean> for isHeadsetSleeping and checks it before scheduling heartbeats.


The key requirement is no host-initiated keepalive traffic when sleeping.


---

6. MoncchichiBleService – Sleep/wake orchestration

MoncchichiBleService is the orchestrator that:

Sees raw incoming frames.

Maintains LensStatus.

Owns the reconnect coordinator and stability metrics.


It should be the place that:

1. Subscribes to SleepEvent from BleTelemetryRepository.


2. Logs headset-level sleep/wake.


3. Coordinates heartbeat/reconnect suppression.



6.1. On SleepEntered

When a sleep event fires:

Log once:

[SLEEP] Headset → IdleSleep
(with timestamp consistent with other console logs.)


Instruct:

ReconnectCoordinator to freeze:

No new reconnect attempts while sleep is active.


Heartbeat logic to suspend:

Either via a shared flag used by G1BleClient or by broadcasting a “sleep” signal.


Stability metrics:

Suppress “degraded”, “ACK stalled”, and “keepalive ERR” logging while sleeping.




Important:

Do not disconnect.

Do not clear known devices or GATT clients.

The app should simply become passive.


6.2. On SleepExited

When a wake event fires:

Log once:

[WAKE] Headset → Awake


Actions:

Re-enable reconnect coordinator:

If any lens is actually disconnected, now the reconnect loops may run.


Resume heartbeat permission:

G1BleClient should be allowed to restart heartbeat coroutine on each primed lens.


Trigger a one-off telemetry refresh:

E.g., request CASE, BATT_DETAIL, WEAR_DETECT or equivalent, once, to resync state.

This should be minimal and only happen once per wake.





---

7. DualLensConnectionOrchestrator – State machine

The orchestrator currently focuses on:

Connecting left/right.

Priming lenses.

Recovering from disconnect.

Handling degraded states.


It needs an explicit headset-level IdleSleep state.

7.1. New state

Add:

IdleSleep


7.2. State transitions

Stable → IdleSleep

Trigger: SleepEvent.SleepEntered.

Precondition: both lenses either connected or gracefully sleeping; no active reconnect in progress.


IdleSleep → Stable

Trigger: SleepEvent.SleepExited AND both lenses are connected & primed.

On transition: mark headset stable; schedule telemetry refresh.


IdleSleep → RecoveringLeft / RecoveringRight

Trigger: wake event shows that a lens is actually disconnected.

Then normal reconnect flow can proceed.



7.3. Behavior in IdleSleep

While in IdleSleep:

Do not:

Send periodic commands.

Run priming logic.

Run reconnect loops.

Schedule left refresh or mirror commands.


Do:

Maintain the GATT connection if it’s still alive.

Accept incoming notifications (case/vitals/wear).

Allow explicit user commands if later you want that (but default is: no traffic).



This ensures the BLE traffic profile matches Even’s logs: the phone is essentially idle.


---

8. Logging expectations

During idle sleep, logs must resemble Even’s:

Allowed:

Case/vitals logs (e.g., updates to case open/close, battery, etc.).

Sleep/wake markers:

[SLEEP] Headset → IdleSleep

[WAKE] Headset → Awake



Not allowed while sleeping:

[BLE] ❤️ Keepalive → ERR

[WARN][ACK] stalled

Reconnect warning spam.

GATT cache refresh logs.

Any host command send logs (PING, test ping, etc.).



When awake again, normal logging resumes.


---

9. Edge cases & precedence rules

Codex should apply these rules when behavior conflicts:

1. One lens asleep, one awake.

isHeadsetSleeping() must only be true if both lenses satisfy isLensSleeping.

If only one lens is sleeping:

Headset is not in IdleSleep.

Heartbeats/reconnect logic may still run, but avoid hammering the sleeping lens (e.g., only heartbeat the awake side).




2. Charging in case.

If charging behavior differs (e.g., Even does not sleep while charging), treat charging == true as “not sleeping” unless logs prove otherwise.

For now, prefer conservative behavior: only treat as sleeping when charging == false.



3. User commands during sleep.

Base spec: don’t send automatic commands in IdleSleep.

If UI triggers a command, it may be allowed to wake the headset (like Even might), but do not add that behavior unless you know Even does it.



4. Disconnect during sleep.

If a lens genuinely disconnects while in idle sleep:

Do not auto-reconnect until a wake signal occurs.

On wake, if the lens is still disconnected:

Then enter recovery state and run reconnect.





5. Flapping signals.

Use lastVitalsTimestamp and a quiet window to avoid entering/exiting sleep repeatedly on noisy case/vitals updates.





---

10. Testing scenarios Codex should reason through

Given that Codex’s environment may not have Android SDK, focus on logical / unit-level verification:

1. Idle sleep entry:

Both lenses report:

foldState = FOLDED

inCase = true

caseOpen = true

charging = false


No vitals for N seconds.

Expect:

isLensSleeping(lens) → true for both.

isHeadsetSleeping() → true.

SleepEvent.SleepEntered emitted once.

Orchestrator state → IdleSleep.

Heartbeat coroutine stops; no more PING scheduling.

ReconnectCoordinator paused.




2. Idle period:

Simulate 3–5 minutes with no state changes.

Expect:

No heartbeat scheduling.

No reconnect scheduling.

No keepalive ERR logs.

Only case/vitals logs if device sends them.




3. Wake:

Change conditions (e.g., foldState = UNFOLDED or inCase = false).

Expect:

isHeadsetSleeping() → false.

SleepEvent.SleepExited emitted once.

Orchestrator IdleSleep → Stable (or Recovering* if a lens is gone).

Heartbeats allowed again.

Single telemetry refresh run.




4. Lens disconnect while sleeping, wake later:

While in IdleSleep, mark one lens disconnected.

No reconnect should run yet.

When wake happens:

Orchestrator transitions to a recovery state.

Reconnect logic can now start.
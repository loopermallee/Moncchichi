CE – Connection Resilience Parity (Production Even Behavior)

Goal:
Make Moncchichi’s BLE behavior as stable and forgiving as the production Even Reality app, with correct handling of:
	•	pairing and bonding
	•	GATT discovery and cache corruption
	•	reconnection backoff
	•	dual-lens coordination (left/right)
	•	sleep/idle/case state gating

Audio, AI, and advanced notifications are out of scope for this section.

⸻

0. Files Codex must inspect before editing

Codex must open and read these files before changing any code:
	•	core/src/main/java/com/loopermallee/moncchichi/bluetooth/G1BleClient.kt
	•	core/src/main/java/com/loopermallee/moncchichi/bluetooth/MoncchichiBleService.kt
	•	core/src/main/java/com/loopermallee/moncchichi/bluetooth/G1Protocols.kt
	•	service/src/main/java/com/loopermallee/moncchichi/bluetooth/DualLensConnectionOrchestrator.kt
	•	hub/src/main/java/com/loopermallee/moncchichi/hub/data/telemetry/BleTelemetryRepository.kt
	•	hub/src/main/java/com/loopermallee/moncchichi/hub/telemetry/BleTelemetryParser.kt
	•	Any shared reconnection helpers:
	•	ReconnectCoordinator (at the bottom of MoncchichiBleService.kt)

Codex must not introduce new concepts that contradict these; it may refactor only if behavior stays equivalent or more robust per this spec.

⸻

1. Source of Truth and Constraints
	1.	Primary sources of truth:
	•	G1 BLE Protocol document (opcodes, expectations for bonds, idle sleep, etc.).
	•	nRF Connect logs of production Even Reality app:
	•	Initial pairing / bonding
	•	Reconnect after power cycle
	•	Reconnect after case open/close
	•	Behavior when only one lens is present or slow to wake
	•	Behavior after repeated GATT failures
	2.	Parity rule:
	•	If Moncchichi’s behavior disagrees with:
	•	G1 protocol doc, or
	•	Even’s nRF logs
then production Even + protocol doc win.
	3.	Scope limits:
	•	Do not implement audio streaming behavior here.
	•	Do not change user-visible UI flows beyond what’s needed to expose connection state clearly.

⸻

2. Conceptual Connection Model (What we’re matching)

Codex should assume this mental model, and align Moncchichi to it:
	•	There is one headset (pair) with two lenses:
	•	Lens.LEFT, Lens.RIGHT.
	•	The phone maintains:
	•	A pair key (Even’s pairing identity),
	•	A bond per lens (Android bonding),
	•	A GATT session per lens,
	•	A logical “headset state” that represents:
	•	both lenses connected and ready,
	•	only one lens connected, or
	•	idle/ sleeping.

Even Reality’s behavior:
	•	Prefers a stable “ready both” state but tolerates:
	•	left-only,
	•	right-only,
	•	in-case/idle with no active reconnection attempts.
	•	Recovers from:
	•	transient link loss (RSSI drop),
	•	flaky GATT,
	•	stale/bad bond entries,
	•	sleep transitions triggered by case or wear events.

Moncchichi must do the same.

⸻

3. Pairing and Bonding Behavior

Codex must:
	1.	Keep the existing bonding logic in G1BleClient, but make sure it is:
	•	clearly separated between:
	•	initial pairing (first-time bond),
	•	bond recovery (when a known device is “forgotten” by the glasses or phone),
	•	fatal bond errors (too many consecutive failures → stop spamming).
	2.	Bond retry rules (per lens):
	•	Maintain or introduce per-lens counters:
	•	bondLossCounters[lens]
	•	time window (e.g. BOND_RETRY_WINDOW_MS)
	•	Rules:
	•	On bond loss:
	•	Try up to BOND_RETRY_MAX_ATTEMPTS within BOND_RETRY_WINDOW_MS.
	•	Use a delay of BOND_RETRY_DELAY_MS between attempts.
	•	On successful bond:
	•	Reset the bond counter for that lens.
	•	On exceeded attempts:
	•	Stop auto bond retries for that lens until:
	•	the headset state is reset (manual reconnect), or
	•	the user explicitly re-pairs.
	3.	Bond removal / reset:
	•	BluetoothDevice.removeBondCompat() should only be called:
	•	when we have a clear indication of stale or broken bond, such as:
	•	repeated GATT_AUTH_FAIL / AUTH_FAILURE_STATUS_CODES.
	•	Do not repeatedly unbond/rebond in a tight loop.
	4.	Pairing success event (0xF5 / 0x11):
	•	BleTelemetryParser already surfaces pairingSuccess via a SystemEvent.
	•	BleTelemetryRepository should:
	•	log a clear console line: "PAIRING [L/R] success".
	•	Optional but recommended:
	•	Use this as a signal to reset bond counters and mark the pair as “healthy”.

⸻

4. Connection / Reconnection State Machine

The authoritative state machine that Codex must preserve and refine lives in:
	•	DualLensConnectionOrchestrator:
	•	State.Idle, State.IdleSleep, State.RecoveringLeft, State.RecoveringRight, State.Stable, State.DegradedLeftOnly, etc.
	•	MoncchichiBleService:
	•	ConnectionStage (Idle, IdleSleep, Active, etc.).
	•	ReconnectCoordinator under MoncchichiBleService.

Codex must ensure the following invariants:
	1.	Monotonic transitions:
	•	For the headset-level state:
	•	Do not bounce rapidly between:
	•	Stable ↔ RecoveringRight ↔ Stable ↔ RecoveringLeft.
	•	Apply minimal debouncing:
	•	e.g., only downgrade to recovery when:
	•	a lens has remained disconnected for > X ms (e.g. > 500 ms),
	•	not based on a single transient state emission.
	2.	Lens priming rules:
	•	leftPrimed and rightPrimed must mean:
	•	GATT discovered,
	•	hello/handshake successful,
	•	lens is ready for normal commands.
	•	The headset is treated as “ReadyBoth” only when:
	•	both leftPrimed and rightPrimed are true,
	•	both lenses are marked connected,
	•	idle sleep is not active.
	3.	Stable partial states:
	•	When one lens is connected and primed, and the other is not:
	•	enter DegradedLeftOnly or DegradedRightOnly (if such states exist) or
use existing RecoveringLeft/Right but avoid spamming logs/reconnects every few ms.
	•	Reconnection for the missing lens should continue, but the app must not report the headset as fully disconnected.
	4.	Session lifetime:
	•	sessionActive must gate:
	•	all connection attempts,
	•	all reconnect loops,
	•	all heartbeat/start/stop operations.
	•	On disconnectHeadset():
	•	cancel all reconnect jobs,
	•	reset reconnectBackoffStep,
	•	clear pending command queues, mirror queues, and left refresh queues,
	•	reset readyConfigIssued and wake tracking variables.

⸻

5. Reconnect Backoff Behavior (Per Lens)

Moncchichi must behave like Even when the connection is unstable:
	1.	Backoff sequence:
	•	Implement a per-lens reconnect backoff similar to:

private val RECONNECT_BACKOFF_MS = longArrayOf(
    0L,      // first retry: immediate
    500L,    // second
    1_000L,  // third
    3_000L,  // fourth
    5_000L,  // fifth and beyond (cap)
)


	•	Codex may tune exact values based on existing constants but must:
	•	use a small number of increasing delays,
	•	cap at a sane value (~5–10s).

	2.	Per-lens tracking:
	•	reconnectBackoffStep[lens] should:
	•	start at 0 when reconnecting starts,
	•	increment after each failed attempt,
	•	be capped at RECONNECT_BACKOFF_MS.lastIndex.
	•	On successful reconnect:
	•	reset reconnectBackoffStep[lens] to 0.
	3.	Failure window tracking:
	•	reconnectFailures[lens] (timestamps):
	•	maintain a sliding window of recent failures,
	•	drop entries older than FAILURE_WINDOW_MS (e.g. 60s).
	4.	When to stop:
	•	If a lens has too many failures in the window (e.g. > 3–5), and:
	•	server is in IdleSleep, or
	•	state suggests case closed/in cradle:
	•	allow reconnect loop to pause (or fully stop) until:
	•	we exit idle sleep, or
	•	we see a new wake/interaction event via telemetry.

⸻

6. GATT Cache Refresh and Full Reset

Production Even recovers from GATT cache corruption; Moncchichi must too.

Codex must wire the following behavior:
	1.	Tracking GATT failures:
	•	GATT_FAILURE_WINDOW_MS and GATT_REFRESH_THRESHOLD already exist (or can be introduced).
	•	Whenever a reconnect attempt fails due to a GATT-level error (discovery failure, characteristic missing, etc.), Codex should:
	•	record the failure timestamp in reconnectFailures[lens].
	2.	When to refresh GATT:
	•	shouldRefreshGatt(side, now) must:
	•	look at reconnectFailures[side] within the FAILURE_WINDOW_MS.
	•	return true if at least GATT_REFRESH_THRESHOLD recent failures exist.
	•	When true:
	•	trigger a GATT refresh cycle for that lens:
	•	close the current BleClient,
	•	refresh the GATT cache (using the existing Android workaround),
	•	delay by a small fixed time (e.g. 300–500 ms),
	•	then attempt a fresh connect.
	3.	Full headset reset trigger:
	•	If both lenses experience GATT failure bursts:
	•	and the headset is not in idle sleep,
	•	Codex may trigger:
	•	full disconnectHeadset(),
	•	small delay,
	•	then re-run connectHeadset(pairKey, lastLeftMac, lastRightMac).
	4.	Never refresh during idle sleep:
	•	isIdleSleepState() must gate:
	•	no GATT refresh attempts,
	•	no full resets.
	•	Let the glasses sleep; only refresh on wake or user-triggered connect.

⸻

7. Case/Wear/Sleep-Aware Reconnect Gating

The reconnection behavior must respect:
	•	inCase (from 0x2B/0xF5),
	•	wearing (from 0x2B/0xF5),
	•	idleSleepActive / sleeping.

Codex must:
	1.	Do not fight case/idle:
	•	When both lenses are:
	•	in case, and
	•	idle sleep is active:
	•	reconnect loops may be frozen (no repeated attempts).
	•	ReconnectCoordinator.freeze() and unfreeze() in MoncchichiBleService should already exist; use them consistently.
	2.	Wake-driven reconnect:
	•	When we see:
	•	case open,
	•	wearing true,
	•	explicit sleep exit event:
	•	reconnects should be resumed (unfreeze) and allowed to run.
	3.	Per-lens gating:
	•	If only one lens is in case (e.g. right in case, left out):
	•	Moncchichi can:
	•	keep trying reconnect for the lens that’s likely out of case,
	•	or treat the “in case” lens as intentionally offline and pause reconnect for that side.
	•	Use the actual behaviors seen in nRF logs for reference.

⸻

8. Metrics and Logging (for Debugging Parity)

Codex must ensure logging is good enough to compare Moncchichi against Even/nRF:
	1.	On every connection attempt:
	•	Log:
	•	lens (L/R),
	•	attempt number,
	•	current backoff delay,
	•	reason (e.g. “link loss”, “GATT failure”, “bond reset”, “idle wake”).
	•	Example:
	•	[RECONNECT][L] attempt=3 delay=1000ms reason=GATT_FAILURE
	2.	On GATT refresh:
	•	Log:
	•	[GATT][L] refresh triggered after 3 failures in 60000ms
	3.	On full headset reset:
	•	Log:
	•	[HEADSET] full reset triggered due to repeated GATT failures
	4.	On bond failures & resets:
	•	Log clear messages:
	•	"[BOND][R] lost – attempt 1/3"
	•	"[BOND][R] reset via removeBond() (auth failure)"
	5.	On idle sleep and wake transitions:
	•	Already present "[SLEEP] Headset → IdleSleep" and "[WAKE] Headset → Awake".
	•	Ensure they are emitted exactly once per state change, not spammed.

These logs will be used to line up with nRF Connect traces.

⸻

9. Implementation Checklist for Codex (Connection Resilience Only)

Codex must execute tasks in roughly this order:
	1.	Review existing connection code
	•	Read:
	•	G1BleClient
	•	MoncchichiBleService
	•	DualLensConnectionOrchestrator
	•	ReconnectCoordinator
	•	Confirm:
	•	where reconnect decisions are made,
	•	how state transitions propagate to the UI.
	2.	Tighten bonding behavior
	•	Ensure:
	•	per-lens bond counters,
	•	limited bond retries,
	•	removeBondCompat() only on clear auth failures,
	•	reset on 0xF5 pairing success.
	3.	Refine reconnect backoff
	•	Define/adjust RECONNECT_BACKOFF_MS.
	•	Apply per-lens steps.
	•	Reset step on success.
	4.	Add GATT refresh and full reset
	•	Use failure windows and thresholds.
	•	Refresh only when:
	•	not in idle sleep,
	•	threshold exceeded.
	•	Optionally trigger full headset reset when both lenses are stuck.
	5.	Wire case/wear/sleep gating
	•	Ensure:
	•	reconnect loops freeze in deep idle/case situations,
	•	reconnect resumes on clear wake signals (wearing/on-head, case-open, sleep exit).
	6.	Stabilize state transitions
	•	Avoid flapping between states on single transient events.
	•	Introduce minimal debouncing where necessary (e.g. 200–500 ms).
	7.	Improve logging for parity
	•	Add logs for:
	•	reconnect attempts,
	•	GATT refresh,
	•	full reset,
	•	bond events.
	8.	Regression guard
	•	Make sure:
	•	pairing still works,
	•	simple connect/disconnect works,
	•	idle sleep still functions,
	•	heartbeats and telemetry (0x2B, 0x2C, 0x37, 0xF5) are not broken.
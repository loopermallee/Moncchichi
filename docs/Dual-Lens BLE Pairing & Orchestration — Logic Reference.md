Dual-Lens BLE Pairing & Orchestration — Logic Reference

This document defines the exact logic the app/service must follow to discover, correlate, bond, connect, verify, and maintain both lenses of a headset as one logical device. Hand it to any engineer/agent and they should be able to implement without guesswork.

⸻

1) Goals & Non-Goals

Goals
	•	Treat a headset as {left, right}; never as a single MAC.
	•	Auto-pair: discover → correlate → bond → connect both lenses from a single user action.
	•	Maintain two simultaneous GATT sessions.
	•	Report one unified state that is “READY” only when both sides are healthy.
	•	Recover from partial failures (one side drops) without user micromanagement.

Non-Goals
	•	UI-level pairing heuristics. The service/orchestrator owns pairing; UI is purely reflective.
	•	Assuming name suffixes are always present or accurate.
	•	Relying on cross-lens proxying. Always talk to the intended side directly.

⸻

2) Core Concepts & Data Model

enum class LensSide { LEFT, RIGHT }

data class PairKey(      // robust correlation token
  val deviceSerial: String // prefer a true device/pair serial; name token only as provisional
)

data class LensId(
  val mac: String,
  val side: LensSide? // null until verified
)

data class LensState(
  val id: LensId,
  val bonded: Boolean,
  val connected: Boolean,
  val mtu: Int?,
  val batteryPct: Int?,
  val firmware: String?,
  val lastSeenRssi: Int?,
  val readyProbePassed: Boolean,
  val error: String? = null
)

enum class HeadsetStatus { IDLE, DISCOVERING, PAIRING, CONNECTING, PROBING, PARTIAL, READY, ERROR }

data class HeadsetState(
  val key: PairKey,
  val left: LensState?,
  val right: LensState?,
  val unifiedReady: Boolean,      // left.ready && right.ready
  val weakestBatteryPct: Int?,    // min(left, right) if present
  val status: HeadsetStatus
)


⸻

3) Discovery & Correlation

Input: continuous BLE scan.

Maintain
	•	Map<PairKey, MutableSet<LensId>> accumulating up to two MACs per headset.
	•	Provisional grouping by name token (e.g., “G1_XXXX”), but verify with serials/metadata once connected.

Rules
	1.	Keep scanning long enough to observe both sides for a given PairKey, or until a pair window timeout (e.g., 5–10 s) from the first sighting.
	2.	Side inference:
	•	Prefer verified metadata (serials / side flags) once available.
	•	_L/_R suffix is provisional only; don’t gate logic solely on it.
	•	Nameless adverts → side remains null until verified after connect.

Stop condition for a candidate pair
	•	Two unique MACs provisionally correlated to the same PairKey, or scan window timeout.

⸻

4) Bond & Connect Choreography (Service Layer)

Do not reuse a single GATT holder. Create one client per MAC.
	1.	Create two clients
	•	clientL = bleFactory(leftMac)
	•	clientR = bleFactory(rightMac)
	2.	Bond
	•	If not bonded: createBond(mac) for each lens.
	•	Handle OS prompts; do both lenses (parallel or quick serial).
	3.	Connect + Setup
	•	Connect both (parallel or serial).
	•	On connection:
	•	Request MTU 251 (fallback accepted).
	•	Discover services.
	•	Subscribe to required notifications/indications.
	4.	Keep both alive
	•	Never force the “other” lens to DISCONNECTED when connecting one.
	•	Per-lens reconnect loops with backoff (0.5s → 1s → 2s … cap ~10s).

⸻

5) Readiness Probes (Gate “READY”)

Run after services+MTU+subscriptions are in place.
	•	Right-side probe (feature native to right lens): e.g., brightness read/write echo or display setting round-trip.
	•	Left-side probe (feature native to left lens): e.g., notification config/clear command ACK path.
	•	Both sides:
	•	Battery/state read.
	•	Optional silent-mode read.
	•	Firmware version read (warn on mismatch).
	•	MTU sanity (ideally 251; fallback degrades throughput, not correctness).

Mark LensState.readyProbePassed = true per side when its probe succeeds.
Set HeadsetState.unifiedReady = left.ready && right.ready.

Probes should have bounded retries (2–3) with small delays.

⸻

6) Automatic Companion Handling

If the user or system connects one side first:
	•	Actively search and connect the companion by PairKey.
	•	Enqueue bond+connect for the missing side automatically — no second user gesture.

⸻

7) Orchestrator API Contract

Service exposes: StateFlow<HeadsetState> keyed by PairKey.

Operations
	•	startScan() / stopScan()
	•	connectHeadset(pairKey) — service resolves left/right MACs and performs full choreography.
	•	disconnectHeadset(pairKey) — gracefully closes both clients.
	•	Optional: sendCommand(pairKey, side, payload) with side = LEFT/RIGHT/BOTH.

UI contract
	•	Present one card per headset with L/R sub-rows.
	•	Show partial progress (e.g., “Right connected, Left bonding…”).
	•	UI never calls per-lens connect directly; pair-level only.

⸻

8) State Machine

IDLE
 └─ startScan → DISCOVERING
DISCOVERING
 ├─ pair (2 lenses) or window timeout → PAIRING
 └─ cancel → IDLE
PAIRING
 ├─ bond L & R → CONNECTING
 └─ bond fail → ERROR
CONNECTING
 ├─ setup ok (L & R) → PROBING
 ├─ partial ok → PARTIAL (keep connecting missing side)
 └─ connect fail → ERROR (retain partial if one side ok)
PROBING
 ├─ both probes pass → READY
 ├─ partial → PARTIAL (keep probing missing side)
 └─ probe fail → ERROR/PARTIAL (with retries)
READY
 ├─ one side drops → PARTIAL → reconnect that side
 └─ user disconnect → IDLE
PARTIAL
 ├─ missing side recovers → READY
 └─ repeated failures → remain PARTIAL, surface guidance
ERROR
 └─ retry → DISCOVERING/CONNECTING (based on cache)


⸻

9) Minimal Implementation Skeleton (Kotlin)

interface BleClient {
  suspend fun ensureBonded()
  suspend fun connectAndSetup(targetMtu: Int = 251)
  suspend fun probeReady(side: LensSide): Boolean
  fun startKeepAlive()
  fun close()
  val events: Flow<ClientEvent> // connected, disconnected, battery, firmware, rssi, etc.
}

class HeadsetOrchestrator(
  private val bleFactory: (String) -> BleClient,
  private val io: CoroutineDispatcher = Dispatchers.IO
) {
  private val scope = CoroutineScope(SupervisorJob() + io)
  private val _state = MutableStateFlow<HeadsetState?>(null)
  val state: StateFlow<HeadsetState?> = _state

  suspend fun connectHeadset(key: PairKey, leftMac: String, rightMac: String) = coroutineScope {
    val L = bleFactory(leftMac)
    val R = bleFactory(rightMac)

    // Bond
    awaitAll(async { L.ensureBonded() }, async { R.ensureBonded() })

    // Connect + setup
    awaitAll(async { L.connectAndSetup() }, async { R.connectAndSetup() })

    // Probes
    val okL = async { L.probeReady(LensSide.LEFT) }
    val okR = async { R.probeReady(LensSide.RIGHT) }
    val ready = okL.await() && okR.await()

    L.startKeepAlive(); R.startKeepAlive()
    superviseReconnect(L, LensSide.LEFT)
    superviseReconnect(R, LensSide.RIGHT)

    updateState(key) { it.copy(unifiedReady = ready, status = if (ready) HeadsetStatus.READY else HeadsetStatus.PARTIAL) }
  }

  private fun superviseReconnect(c: BleClient, side: LensSide) = scope.launch {
    // listen to events and trigger per-lens backoff reconnects…
  }

  private inline fun updateState(key: PairKey, crossinline f: (HeadsetState) -> HeadsetState) {
    _state.update { cur -> cur?.takeIf { it.key == key }?.let(f) ?: cur }
  }
}


⸻

10) Error Handling & Recovery
	•	Per-lens reconnect with capped backoff; do not tear down the healthy side.
	•	Surface PARTIAL clearly; keep attempting the missing side in background.
	•	Warn on L/R firmware mismatch; do not block READY if both probes pass.
	•	MTU fallback → continue with smaller packets; suggest retry if throughput-sensitive features degrade.
	•	Nameless devices → defer side assignment until verified post-connect.

⸻

11) Telemetry & Observability
	•	Log discovery timeline per PairKey (first seen, both seen, timeout).
	•	Bond/connect/probe durations per lens; failure reasons.
	•	MTU values per lens; notification subscription success.
	•	Keep-alive RTT; last ACK timestamps.
	•	Counters: reconnect attempts, sustained PARTIAL durations.

⸻

12) Test & Acceptance Checklist

Discovery
	•	Right appears first → both lenses ultimately connected.
	•	Left appears first → same outcome.
	•	Nameless advert → side assigned after metadata fetch.

Bond & Connect
	•	One lens already bonded, other not → auto-bonds missing one.
	•	Parallel and serial connect paths both succeed.

Readiness
	•	Both probes pass → READY.
	•	Only one probe passes → PARTIAL; background retries proceed.

Resilience
	•	Drop one side while READY → returns to READY after reconnect.
	•	MTU 251 denied on one or both → app continues with degraded throughput.
	•	Firmware mismatch → warning, not a block.

User Experience
	•	Single “Connect/Pair” action pairs both lenses.
	•	UI shows unified headset with L/R sub-status at all times.

⸻

13) Integration Notes (Android / MentraOS)
	•	Orchestrator must own two independent BluetoothGatt sessions; avoid any singleton that enforces “one active connection”.
	•	If the legacy path keeps a single selectedAddress, remove or gate it behind the new dual-lens orchestrator.
	•	Surface only pair-level intents to the UI; keep per-lens details internal.
	•	For MentraOS app packaging, expose the same service API; the logic in this doc is platform-agnostic aside from BLE/GATT primitives.

⸻

14) Open Questions (Track & Resolve)
	•	What is the most reliable PairKey in production (serial pattern, ESB channel, other metadata)?
	•	Exact right-only vs left-only probe commands per firmware rev.
	•	Required notification characteristics per lens and their ACK semantics.

⸻

15) Quick “Do/Don’t” Summary

Do
	•	Correlate two MACs into one headset and handle both together.
	•	Bond, connect, and probe both sides automatically.
	•	Keep healthy side up if the other flaps.
	•	Report PARTIAL vs READY truthfully.

Don’t
	•	Reset other lenses to DISCONNECTED when connecting one.
	•	Assume _L/_R naming is always correct.
	•	Use a single GATT client for both lenses.

⸻

This is the authoritative logic reference. If a future implementation differs, update this document first, then adjust code accordingly.
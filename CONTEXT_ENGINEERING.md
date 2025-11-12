Context Engineering — Pairing & Connection Parity (Even Reality)

Scope

Goal: make Moncchichi Hub connect and stay connected exactly like Even’s app.

Out of scope: UI polish, DFU, subtitles/teleprompter, assistant features.


References (project-local)

G1 Protocol (updated today) — the command set & event semantics your repo tracks.

Even v1.6.6 bundle analysis — confirms dual-lens UART model and “Right as control link”.

Your runtime notes — ASCII system replies (0x23 0x74) and 0xF5 gesture/event stream.


Core Parity Principles

Two UART links (Left, Right) over BLE; treat them as distinct but coordinated.

Right is the control link for Right-only getters/setters (e.g., brightness 0x29, display settings 0x26); “Both” queries must be mirrored to both lenses.

Do not rely on Left-first. Prefer Right-first when available, but remain robust if Left connects first.

Prime on Right once it’s up; Left can attach before or after without breaking flow.

Events first. Subscribe to notifications and 0xF5 event stream immediately upon connection (whichever lens arrives first), so taps/wear/case signals aren’t lost.

ASCII vs ACK. Some system commands return ASCII text (e.g., 0x23 0x74 firmware line). Do not force binary ACK (C9/CA/CB) on those.



---

State Machine (connection-only)

State	Trigger	Action	Exit

Idle	User requests connect	Start scan (filtered by G1 names/UUIDs)	Scanning
Scanning	Lens ADV found	Rank devices (prefer Right if present)	Connecting(X)
Connecting(R)	Start GATT to Right	On success: Discover services → Enable UART notify	RightOnline (Unprimed)
Connecting(L)	Start GATT to Left	On success: Discover services → Enable UART notify	LeftOnline (Unprimed)
RightOnline (Unprimed)	Right notify ready	Run Priming Sequence (Right)	ReadyRight
LeftOnline (Unprimed)	Left notify ready	If Right not ready → Events only; else → Mirror “Both” queries	ReadyBoth or WaitingRight
WaitingRight	Right not yet online	Hold Right-only queue; continue events on Left	RightOnline → Priming
ReadyRight	Right primed	Send Right-only cmds freely; mirror “Both” to Left when online	ReadyBoth (once Left up)
ReadyBoth	Both online	Normal ops; keep queues drained	…
DegradedRightDown	Right drops	Re-queue Right-only; keep Left events; reconnect Right	RightOnline → Priming
DegradedLeftDown	Left drops	Continue on Right; reconnect Left; mirror “Both” on return	ReadyBoth


Transitions on errors: If connect/discover/notify fails → backoff (1s, 2s, 4s, 8s capped), refresh GATT, retry.


---

Device Selection & Connect Policy

Scan filter: target the G1 advertised names and known service UUID(s) you already use for UART.

Ranking: If both seen, connect Right first; if only Left appears, connect it (events OK), then attach Right as soon as it’s discovered.

Do not block on Left if Right is available; do not block on Right if only Left is visible.



---

Notification Subscription (must happen before any priming)

Enable UART notifications (CCC descriptor 0x2902) on the RX/Notify characteristic for whichever lens is connected.

Begin decoding 0xF5 immediately to capture gestures, wear, case state, silent mode. Show human labels, not “unknown gesture”.



---

Priming Sequence (Right-anchored)

Run only when Right’s notifications are enabled:

1. Subscribe (already done) — ensure 0xF5 arriving.


2. System/Firmware line: 0x23 0x74 → expect ASCII text; parse version/build/time/DeviceID.


3. Brightness (Right): 0x29 getter to seed UI/state.


4. Wear/Case/Silent: 0x2B getter (protocol’s consolidated status) — treat as Both semantic; read on Right first.


5. Battery detail: 0x2C 0x01 getter — collect Right; mirror to Left later.


6. Wear detection: 0x27 toggle/get as required by user setting (don’t force a change on connect).



After Right priming:

If Left is already online → mirror “Both” queries to Left.

If Left isn’t online → set mirror-pending flags and fulfill once Left connects.



---

Command Routing Rules

Command	Category	Route	Notes

0x23 0x74	System (ASCII)	Right	Treat as text line; no binary ACK expected.
0x29	Brightness	Right-only	Get/set on Right; Left does not own brightness in Even’s pattern.
0x26	Display Settings	Right-only	Ensure Seq/ACK discipline; fail fast on CB.
0x27	Wear Detect	Both (config)	Apply on Right, then Left (idempotent).
0x2B	Wear/Case/Silent status	Both	Read Right first, mirror Left; keep a merged snapshot.
0x2C 0x01	Battery detail	Both	Collect per lens; show combined view.
0xF5	Events stream	Both	Decode on whichever lens is connected first; unify into one event bus.


ACK handling:

Binary ACKs: C9/CA = success; CB = failure.

ASCII responses (system lines): don’t expect C9/CA/CB; accept printable lines.



---

Queues & Concurrency

Two queues + a mirror buffer:

RightOnlyQueue — enqueue if Right not ready; drain once Right = Ready.

BothQueue — enqueue on whichever lens is online; mirror to the other when it appears.

MirrorPending flags — per command family (status, battery, wearDetect) to resend to Left when it connects, avoiding duplicates.


In-flight limits

One outstanding write per lens; next command waits for ACK/timeout.

Timeouts: 800–1200 ms per write (tuneable); retries: 2 with backoff (e.g., +400 ms each).


Seq discipline (where required, e.g., 0x26)

Maintain per-lens sequence if protocol specifies; reject late replies by Seq.



---

Reconnect & Stability Rules

If a lens drops:

Mark that lens Down, keep its queue intact.

Continue operating on the other lens (events/UI remain live).

Reconnect with backoff; on Right reconnection, re-run Priming Sequence; on Left reconnection, mirror the “Both” set.


If Android GATT flaps:

Close GATT, refresh cache (use your existing helper), delay 1–2 s, reconnect.


Never tear down the surviving lens just because its partner dropped.



---

Developer Console Expectations

Show decoded gesture names and case/wear states from 0xF5 (no “unknown gesture”).

Log ASCII system lines distinctly (prefix SYS:) to avoid ACK confusion.

Tag each line with lens and Seq (if applicable).

Emit state transitions: Scanning, RightConnected, LeftConnected, PrimingRight, ReadyRight, ReadyBoth, DegradedRightDown, etc.



---

Implementation Tasks (Codex-ready)

1. Connection Policy

Add a ranker in scanner: prefer Right if both present; otherwise connect what’s available.

Update MoncchichiBleService state machine to the table above, including WaitingRight and degraded modes.



2. Notify-First Enforcement

In G1BleClient (per lens), ensure CCC write/notify enable happens before any command enqueue.

Start 0xF5 decode immediately on notify success.



3. Priming Sequence (Right)

Implement an atomic sequence: sys(0x23 0x74) → get(0x29) → get(0x2B) → get(0x2C,0x01) → optional get/set(0x27).

Flag completion as RightPrimed = true.



4. Command Routers

Add Route.RIGHT_ONLY, Route.BOTH, Route.EVENTS.

Map commands per the routing table above.

Enforce one in-flight per lens and per-lens timeout/retry.



5. Mirror Engine

For any Route.BOTH request issued while only one lens is online:

Execute on the online lens.

Set mirrorPending[family] = true.

On partner connect → flush mirror families in a single small batch (preserving per-lens in-flight rule).




6. ACK/ASCII Parser

Binary frames: honor C9/CA/CB.

System text (e.g., 0x23 0x74): route to ASCII handler; never wait for C9/CA.

Guard against misclassification (first byte printable → ASCII parser path).



7. Reconnect Logic

On disconnect: change state → requeue pending → backoff → reconnect.

On Right reconnect: re-run Priming Sequence; on Left reconnect: flush mirror families.



8. Developer Console

Replace “unknown gesture” with explicit labels from the updated 0xF5 map.

Prefix: [R]/[L], ACK:CA, ERR:CB, SYS:<line>, EVT:<gesture>.



9. Metrics (optional but useful)

Connection times (to notify enabled), number of reconnects/session, command RTTs, timeout counts.





---

nRF Connect / On-Device Acceptance Checklist

A. Right-first scenario

Power both lenses; both advertise.

App connects Right first, enables notify, runs Priming Sequence (watch logs).

App connects Left; mirrors “Both” gets (status/battery/wear).

Developer console shows decoded events for both.


B. Left-first scenario (stress the ordering)

Power Left only; connect Left; verify:

Notifications and 0xF5 events decode.

No Right-only commands are sent.


Power Right; app auto-connects, primes Right, mirrors “Both”.


C. Right drop / Left survives

With both connected, manually power down Right.

App stays up on Left (events continue), requeues Right-only.

Power Right back; app reconnects, re-primes Right, mirrors pending “Both”.


D. ASCII vs ACK sanity

Trigger 0x23 0x74; observe ASCII line logged (SYS:), no C9/CA wait.

Send a 0x26 op; observe C9/CA success or CB failure with retry budget.


E. Case/wear/silent

Open/close case, wear/unwear → 0xF5 reflects; 0x2B getter consistent on demand.


Pass/fail: zero “unknown gesture”, no Right-only writes before Right is ready, mirrors executed on Left connect, reconnects don’t tear down the surviving lens.


---

Exit Criteria (for this patch)

Connection parity: In Right-first conditions, Right is connected & primed before any Right-only command; Left attaches without disrupting flow.

Order robustness: In Left-first, no Right-only commands are issued until Right is online and primed; events on Left are not missed.

Stability: With one lens power-cycled 3× during a 10-minute session, app maintains the other lens and fully restores state on reconnection (priming/mirroring).

Protocol correctness: 0x23 0x74 handled as ASCII; 0x26/0x29/0x27/0x2B/0x2C routed per table; 0xF5 fully labeled.

Operator visibility: Developer console shows state transitions and per-lens frames clearly; no “unknown gesture”.



---

Notes (brief corrections)

Prior docs sometimes implied “Left-first” as a rule. That’s not reliable in practice. The parity behavior anchors control on Right, but must tolerate any arrival order. This document enforces that.
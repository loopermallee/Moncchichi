Below is a CE-style section you can drop into CONTEXT_ENGINEERING.md for this upcoming patch. It:
	•	Recaps the project goal and current plan.
	•	Summarises what we learned from the protocol docs + logs.
	•	States the concrete invariants and behavioral rules this patch is enforcing.
	•	Names the key problems in the current app that this patch is meant to fix.

You can put this under a new section like:

## 8. BLE v1.6.6 Parity Patch – Session Lifetime, Telemetry Bundles, Sleep/Wake

⸻

8. BLE v1.6.6 Parity Patch – Session Lifetime, Telemetry Bundles, Sleep/Wake

8.1 Project Goal (for any new assistant / tool)

Moncchichi Hub is a third-party Android companion app for Even G1 glasses.
The near-term BLE goal:
	•	Reach practical 1:1 parity with the official Even app’s behavior on G1 firmware v1.6.6 for:
	•	Pairing + bonding
	•	Initial connect + priming
	•	Telemetry / heartbeat
	•	Sleep / wake / case handling
	•	Reconnect + GATT recovery
	•	Only after BLE is rock-solid do we layer:
	•	AI assistant / MCP integration
	•	HUD / teleprompter / text & BMP sending
	•	Offline/online AI mode switching

Non-goals for this patch:
	•	No new AI features.
	•	No UX flows outside BLE stability.
	•	No speculative protocol behavior that isn’t grounded in:
	•	EvenDemoApp BLE wiki and upgrade tools
	•	Community G1 apps (teleprompter, AI assistant, etc.)
	•	v1.6.6 nRF Connect logs (e L.txt, e R.txt)

8.2 What We Learned (Ground Truth Sources)

We align behavior with these anchors:
	•	Dual-lens topology + left-first
	•	G1 uses two independent BLE links: one per arm.
	•	Protocol guidance: send to LEFT first, then RIGHT after LEFT ACK, except for explicitly right-only commands.
	•	MTU + notifications as handshake precondition
	•	Community reverse-engineering shows G1 won’t send notifications until MTU is negotiated and notifications are enabled.
	•	A “usable” link = connected + MTU set + RX notifications enabled.
	•	Telemetry bundle after connect (per lens)
From v1.6.6 logs, each lens, after connect+notify, emits a consistent sequence:
	•	0x4D ... 0xC9 ... – success marker (“M 0xC9 …”)
	•	0xF5 0x11 ... – status / dashboard sync frame
	•	0x2C ... – battery / case / wear telemetry
	•	0x37 ... – uptime / runtime counters
	•	ASCII UART line:
	•	"net build time: ... ver 1.6.6, JBD DeviceID 4010 ..."
→ That full set is treated as a telemetry bundle per lens.
	•	Heartbeat as “vitals still moving”
	•	Logs show periodic 0x2C / 0x37 and other small frames over time.
	•	This behaves like a vitals-poll heartbeat, not aggressive PING spam.
	•	Sleep / wake / case behavior
	•	Case and wear detection bits live inside 0x2C payloads.
	•	After placing/removing in case, both lenses send new 0x2C/0x37 + version strings, then mostly quiet.
	•	There is clearly an “IdleSleep” and a short “wake but quiet” phase where the app waits for vitals after wake.

These observations are the basis for the CE invariants enforced by this patch.

⸻

8.3 Current App Issues This Patch Targets

Pre-patch, Moncchichi BLE behavior had these problems:
	1.	Handshake / priming is synthetic and too narrow
	•	leftPrimed / rightPrimed were mostly driven by probeReady() on a single PING-style command.
	•	“ReadyBoth” could be reached without seeing a full telemetry bundle from each lens.
	2.	Session lifetime not enforced
	•	GATT refreshes, link drops, and bond loss did not consistently:
	•	Clear leftPrimed / rightPrimed
	•	Clear pending mirrors and left-refresh queue
	•	Reset wake/ready configuration flags
	•	Result: “ReadyBoth” could be reached on stale state from a previous session.
	3.	Sleep / wake not properly gated
	•	scheduleReconnect() and bond retries could run during:
	•	IdleSleep
	•	Wake quiet windows
	•	“Awaiting vitals” phase after wake
	•	This caused reconnect storms and unnecessary wake-ups.
	4.	State machine not strictly left-first / monotonic
	•	Right could effectively “come ready” before left.
	•	Stable and ReadyBoth sometimes depended on raw isReady flags instead of strict left-first priming + telemetry bundles.
	5.	Heartbeat not integrated with telemetry
	•	Heartbeat logic mainly looked at ping/ack and missed counts.
	•	It did not treat 0x2C / 0x37 telemetry as valid proof of life, even though the real device behaves that way.
	6.	Duplicate operations allowed
	•	Multiple connect/bond/GATT refresh attempts could overlap per lens.
	•	No per-lens op mutex; reconnect/backoff state could be corrupted by concurrent operations.

This patch’s CE defines how all of that must behave going forward.

⸻

8.4 Core CE Invariants for This Patch

8.4.1 Left-First Semantics (Commands + Readiness)
	•	For any command family that is not explicitly right-only:
	•	LEFT is always the primary target.
	•	RIGHT must not receive the mirrored frame until:
	•	LEFT link is READY, and
	•	LEFT has positively ACKed (binary ACK or textual “OK” frame).
	•	Readiness ordering:
	•	LEFT must become primed before RIGHT priming is accepted.
	•	If RIGHT “primes” while leftPrimed == false, it is treated as out-of-order and triggers a RIGHT reconnect, not a ready state.

8.4.2 Session & Handshake Lifetime
Definitions:
	•	Session start (per lens):
	•	GATT connected.
	•	MTU successfully negotiated.
	•	UART RX notifications enabled.
	•	At this moment:
	•	leftPrimed / rightPrimed = false
	•	Telemetry bundle state = empty for that lens.
	•	Telemetry bundle (per lens) is considered complete when we have seen, in the current session:
	•	≥ 1 0x2C battery/case/wear frame.
	•	≥ 1 0x37 uptime/runtime frame.
	•	≥ 1 firmware/version ASCII line:
	•	"net build time: ... ver 1.6.6, JBD DeviceID ...".
	•	Primed lens:
	•	A lens becomes primed only when:
	•	Above telemetry bundle is complete since the last invalidateHandshake.
	•	probeReady() has passed for that lens.
	•	Headset ReadyBoth / Stable:
	•	ReadyBoth reachable only when:
	•	leftPrimed == true
	•	rightPrimed == true
	•	Both priming events happened after the last invalidateHandshake().
	•	Stable is derived from ReadyBoth + debounced transitions + no active reconnects.
	•	Handshake invalidation (invalidateHandshake(reason)):
	•	Must be called on:
	•	Link drop (ConnectionStateChanged.connected == false)
	•	Bond loss
	•	GATT refresh attempt
	•	Wake-handshake restarts (exit IdleSleep / enter wake handshake)
	•	It must:
	•	Clear leftPrimed / rightPrimed
	•	Clear pending mirrors
	•	Clear the left-refresh queue
	•	Reset leftRefreshRequested
	•	Reset readyConfigIssued and wakeRefreshIssued
	•	Reset per-lens operation flags (connectOpsActive, bondOpsActive, gattRefreshOpsActive)

8.4.3 Sleep / Wake / WakeQuiet Gating
	•	IdleSleep entry:
	•	When both lenses are effectively offline and telemetry or case/wear bits indicate “in case / sleeping”:
	•	Enter State.IdleSleep
	•	Cancel all reconnect jobs
	•	Reset reconnect backoff
	•	Clear pending queues, primed flags, telemetry bundle state
	•	Wake and WakeQuiet:
	•	On wake trigger (case open / wear detect):
	•	awaitingWakeTelemetry = true
	•	wakeQuietActive = true
	•	wakeQuietUntil = now + QUIET_WINDOW_MS
	•	Call invalidateHandshake("wake_handshake_restart")
	•	During wake quiet:
	•	Do not schedule reconnects
	•	Do not run bond retries
	•	Do not perform GATT refresh
	•	Wake telemetry qualification:
	•	wakeTelemetryObserved[lens] = true only after we see fresh 0x2C and 0x37 from that lens after wake.
	•	Exit wake handshake:
	•	When wakeTelemetryObserved[LEFT] == true AND wakeTelemetryObserved[RIGHT] == true AND now >= wakeQuietUntil
	•	Clear wakeQuietActive
	•	Allow reconnect / bond / GATT operations to resume
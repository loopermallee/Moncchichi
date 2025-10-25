🧠 Moncchichi Hub — Context Engineering Document

(Shared operational memory between ChatGPT, Codex, and the user)

⸻

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT_PHASE: Phase 4.0 rev 3 — BLE Core Fusion (Wave 1 Foundations → Wave 1c Refinements)

PHASE OBJECTIVE
Stabilize the Even Realities G1 dual-lens BLE foundation with full ACK-based sequencing, parallel telemetry channels, and heartbeat synchronization.
All telemetry, diagnostics, and HUD messages must complete in ≤ 2 seconds with 5 ms chunk pacing and 3× retry logic.
Wave 1c finalizes live telemetry propagation into the Hub UI and replaces the stub BLE tool with the real service bridge (Codex-aligned).
Maintain Even Realities monochrome theme and offline-first principles from Phase 3.

⸻

🧩 CURRENT MILESTONES (Sequenced by Wave)

#	Milestone	Wave	Status	Notes
1	Dual-lens BLE connection (L + R)	1	🟢 Implemented / 🟡 Pending Validation	MoncchichiBleService + G1BleClient manage dual GATT sessions and per-lens state.
2	Bidirectional communication (App ↔ Glasses)	1	🟢 Implemented / 🟡 Pending Validation	ACK-aware send pipeline with mutex and 5 ms stagger.
3	BLE telemetry (battery %, firmware, RSSI)	1 → 1c	🟡 In Progress	BleTelemetryRepository parses 0x2C/0x37/0x11, auto-reset on disconnect; r1c: surface RSSI to UI & firmware UTF-8→hex fallback.
4	Heartbeat (keepalive every 30 s)	1	🟢 Implemented / 🟡 Pending Validation	0x25 seq heartbeat per lens with 0x25 0x04 ACK.
5	HUD messaging API	2	⚫ Not Started	Wave 2 – sendHudMessage() broadcast + ack feedback.
6	Event decoding (touch, case open)	2	⚫ Not Started	Reserve for Phase 4.1.
7	Diagnostic console integration	2	🟢 Partial / 🟡 Aligning in r1c	ConsoleInterpreter summaries; r1c adds firmware + case notes.
8	Assistant diagnostic bridge	3	⚫ Not Started	Wave 3.
9	Monochrome theme	—	🟢 Implemented	BleStatusView palette (black/gray/white) with status colors.
10	Docs + progress notes	—	🟢 Updated	[4.0-r1], [4.0-r1b]; [4.0-r1c] changes below.


⸻

🧠 WAVE 1c: Scope (Codex-aligned)
	•	Replace stub BleToolImpl with a live bridge that keeps the existing BleTool contract (no signature changes; disconnect() returns Unit).
	•	Bind BleTelemetryRepository to MoncchichiBleService so snapshots flow to UI; add RSSI per lens.
	•	Warm-up telemetry after connect: send BATTERY & FIRMWARE once on success so the repo snapshot populates immediately.
	•	Throttle duplicate telemetry logs (do not suppress changes).
	•	Deduplicate scan callbacks (emit only newly discovered MACs) and ensure stopScan() cancels the scan coroutine.
	•	Route lens-specific commands (LEFT/RIGHT) to matching service target.
	•	AppLocator augmentation only (do not remove other singletons) + optional feature flag to avoid double-binding during QA.
	•	Permissions UX: unify requested runtime permissions with PermissionToolImpl.areAllGranted() (include ACCESS_FINE_LOCATION on all API levels) and surface a clear warning when missing.

⸻

2️⃣ BLE Service Architecture (delivered; reused in r1c)
	•	MoncchichiBleService: dual clients, sequenced writes, heartbeat, reconnect, shared incoming frames.
	•	G1BleClient: per-lens ACK tracking and RSSI reader.
	•	✅ Firmware 0x11 + case telemetry parsed in r1b.
	•	🔄 No service API changes in r1c (wiring/UI only).

⸻

3️⃣ Telemetry Repository (delivered → r1c refinements)

File: hub/data/telemetry/BleTelemetryRepository.kt
Status: Delivered; refine in r1c

Changes to implement in r1c
	•	Add rssi: Int? to LensTelemetry; merge from MoncchichiBleService.state inside bindToService().
	•	Firmware decode fallback: try UTF-8; if blank/invalid, store hex.
	•	Emit on change only: guard updates (withFrame/value compare) to reduce spam.
	•	Reset note: on reset() also events.tryEmit("[BLE][DIAG] telemetry reset").

Outcome: UI and console receive fresh battery/case/firmware/RSSI values with minimal noise.

⸻

4️⃣ Console Interpreter (delivered → r1c alignment)

File: hub/console/ConsoleInterpreter.kt

r1c adds
	•	Include firmware and battery/case notes when present (e.g., “Firmware vX.Y.Z”, “Battery update • left=85% case=62%”).
	•	Keep existing health heuristics (ACK timeouts, reconnects, keepalive).

⸻

5️⃣ UI – Monochrome BLE Status (bind live telemetry)

File: hub/ui/components/BleStatusView.kt

r1c task
	•	Bind to live BleTelemetryRepository.snapshot via AppLocator scope so RSSI/battery update in real time (keep refreshDeviceVitals() as one-shot in VM; no long-lived collectors there).

⸻

6️⃣ Reliability Matrix (Wave 1 → 1c)

Constraint	Guideline
Chunk delay	≈ 5 ms
ACK gate	0xC9 before next command
Retries	3× then mark degraded
Heartbeat	30 s / lens
Round-trip	≤ 2 s
Connect timeout	20 s / lens
Telemetry freshness	push via flow; one-shot reads OK
Reset trigger	both lenses disconnected → reset() + console note


⸻

7️⃣ Acceptance After r1c
	•	Dual connection + ACK: UI shows “Connected L/R”; console logs ❤️ keepalive ACK.
	•	Live telemetry: [DIAG] left/right battery=% case=% firmware=vX.Y.Z (no hard-coded 87/62/v1.2.0).
	•	RSSI display: per-lens RSSI in BleStatusView (e.g., −55 dBm).
	•	Disconnect reset: console prints telemetry reset; UI clears values promptly.
	•	No duplicates: scan emits new devices once; telemetry logs only on change.
	•	Latency: ≤ 2 s app↔glasses for commands and key telemetry.

⸻

8️⃣ Files to Implement / Modify in Wave 1c (and how)

App DI (augment, don’t replace)

File: hub/di/AppLocator.kt
Do:
	•	Keep all existing singletons (memory, router, llm, display, perms, tts, prefs, diagnostics).
	•	Add appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).
	•	Add feature flag useLiveBle = true (QA default).
	•	When useLiveBle=true:
	•	Construct BluetoothScanner(appCtx) (no args to start/stop).
	•	Construct MoncchichiBleService(appCtx, appScope).
	•	Construct BleTelemetryRepository() and pass into the live tool.
	•	Set ble = BleToolLiveImpl(appCtx, bleService, bleTelemetry, bleScanner, appScope).
	•	Else: fallback ble = BleToolImpl(appCtx).
	•	Ensure init(ctx) runs before any AppLocator.ble access (unchanged contract).

Live BLE bridge (contract-compatible)

File (new): hub/tools/impl/BleToolLiveImpl.kt
Do:
	•	Keep BleTool signatures exactly (e.g., disconnect(): Unit).
	•	Import com.loopermallee.moncchichi.hub.tools.ScanResult (top-level).
	•	Bind telemetry in init { telemetry.bindToService(service, appScope) }.
	•	Scan lifecycle:
	•	Store scanJob; scanJob?.cancel() before starting new.
	•	Maintain seen MAC set; only call onFound for new addresses; clear on stopScan().
	•	Call scanner.start() / scanner.stop() without args.
	•	Connect:
	•	Resolve BluetoothDevice via BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceId) (guard IllegalArgumentException).
	•	Call service.connect(device); on success remember lastConnectedMac = device.address.
	•	Warm-up telemetry after success: in VM (see below) immediately send BATTERY + FIRMWARE (Both).
	•	Send mapping:
	•	PING → G1Packets.ping() → Target.Both
	•	BATTERY/FIRMWARE → G1Packets.batteryQuery() / G1Packets.firmwareQuery() → Target.Both
	•	BRIGHTNESS_UP/DOWN (absolute 80/30 for now) → Target.Both
	•	LENS_LEFT_* / LENS_RIGHT_* → G1Packets.brightness(..., LEFT/RIGHT) → Target.Left/Right
	•	DISPLAY_RESET → G1Packets.textPageUtf8("") → Target.Both
	•	fallback text → G1Packets.textPageUtf8(command) → Target.Both
	•	Getters:
	•	battery()/caseBattery()/firmware() from repo snapshot.
	•	signal() as max(state.left.rssi, state.right.rssi).
	•	macAddress() from lastConnectedMac.
	•	Permissions helper (optional for VM): requiredPermissions() returning any missing BT/Location grants.

Telemetry repository: RSSI + noise guards

File: hub/data/telemetry/BleTelemetryRepository.kt
Do:
	•	Add rssi: Int? to LensTelemetry.
	•	In bindToService(), collect service.state and mergeRssi into snapshot with value-change guard.
	•	Firmware fallback: UTF-8 else hex.
	•	reset() emits "[BLE][DIAG] telemetry reset".

Permission UX alignment

File: hub/src/main/java/com/loopermallee/moncchichi/PermissionsActivity.kt
Do:
	•	Always include ACCESS_FINE_LOCATION (pre-Q as well), alongside BLUETOOTH_SCAN/BLUETOOTH_CONNECT (S+).
	•	This matches PermissionToolImpl.areAllGranted() (Codex already green-lit this fix).

ViewModel: warm-up + log throttle

File: hub/viewmodel/HubViewModel.kt
Do:
	•	Before ble.connect(id): (optional) check ble.requiredPermissions() via perms; log clear warning if missing (no auto-rescan while dialog is up).
	•	After successful connect:
	•	Try/catch (suspend-safe) send: "BATTERY" then "FIRMWARE" via ble.send(...) (do not wrap suspend calls in non-suspend runCatching).
	•	Telemetry log throttle: keep hubAddLog("[BLE] Telemetry • …") but gate with a cached digest so repeats don’t spam.
	•	Do not add long-lived collectors here; one-shot reads only.

Console notes

File: hub/console/ConsoleInterpreter.kt
Do: extract last firmware line and battery/case tuple into notes (compact phrasing).

Protocol helpers

File: hub/bluetooth/G1Protocol.kt
Do: no change (already has batteryQuery/firmwareQuery/ping/brightness/reboot/textPageUtf8).

⸻

9️⃣ Known Gaps / Next Patch Objectives (Wave 1c)

Area	Task	Status / Plan
AppLocator wiring	Swap ble to live implementation; keep other singletons	🟢 Ready (r1c)
Service ↔ Repo	bindToService(service, appScope) once; unbind() on shutdown	🟢 Ready (r1c)
Scan dedupe + stop	Seen-set + scanJob cancellation; reset seen on stopScan()	🟢 Ready (r1c)
Lens-target routing	Map LEFT/RIGHT commands to correct target	🟢 Ready (r1c)
RSSI to UI	Store in repo; surface in BleStatusView	🟡 Validate in QA
Firmware fallback	UTF-8 else hex	🟢 Ready (r1c)
Telemetry logs	Throttle duplicates; keep change logs	🟢 Ready (r1c)
Permissions UX	Request FINE_LOCATION on all API levels (plus BT perms)	🟢 Codex-approved
Feature flag	useLiveBle to avoid AIDL coexistence during QA	🟡 Optional toggle


⸻

🧾 PROGRESS NOTES
	•	[4.0-r1] ✅ Wave 1 foundations — dual-GATT service, ACK client, telemetry base, monochrome UI.
	•	[4.0-r1b] ✅ Firmware parsing, disconnect reset, ping/brightness/reboot helpers.
	•	[4.0-r1c] 🟡 Pending validation — Live BLE binding in AppLocator, repo binding to service, scan dedupe + stop, RSSI to UI, firmware hex fallback, telemetry log throttling, lens-specific routing, permission UX alignment (ACCESS_FINE_LOCATION requested across all API levels – Codex approved).

⸻

✅ Summary

Wave 1a → 1b delivered the BLE core and parser. Wave 1c replaces the stub tool with the live bridge, binds the telemetry repository to the service, warms up telemetry after connect, and connects live battery/firmware/RSSI data to the Hub UI and console—without changing the BleTool contract.
All r1c changes above are aligned with Codex guidance and will be validated by runtime acceptance tests before advancing to Wave 2 (HUD + event visuals).
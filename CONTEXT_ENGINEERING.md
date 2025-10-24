🧠 Moncchichi Hub — Context Engineering Document
(Shared operational memory between ChatGPT, Codex, and the user)

⸻

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT_PHASE: Phase 4.0 rev 3 — BLE Core Fusion (Wave 1 Foundations → Wave 1c Refinements)

PHASE OBJECTIVE:
Stabilize the Even Realities G1 dual-lens BLE foundation with full ACK-based sequencing, parallel telemetry channels, and heartbeat synchronization.
All telemetry, diagnostics, and HUD messages must complete in ≤ 2 seconds with 5 ms chunk pacing and 3× retry logic.
Wave 1c finalizes live telemetry propagation into the Hub UI and replaces the stub BLE tool with the real service bridge, per Codex agreement.
Maintain the Even Realities monochrome theme and offline-first principles from Phase 3.

⸻

🧩 CURRENT MILESTONES (Sequenced by Wave)

| Milestone | Wave | Status | Notes

—|—|—|—|—
1 | Dual-lens BLE connection (L + R) | 1 | 🟢 Implemented / 🟡 Pending User Validation | MoncchichiBleService + G1BleClient manage dual GATT sessions and per-lens state.
2 | Bidirectional communication (App ↔ Glasses) | 1 | 🟢 Implemented / 🟡 Pending User Validation | ACK-aware send pipeline with mutex and 5 ms stagger.
3 | BLE telemetry (battery %, firmware, RSSI) | 1 | 🟢 Implemented / 🟡 Pending Validation | BleTelemetryRepository parses 0x2C/0x37/0x11, auto-reset on disconnect; RSSI surfaced to UI in r1c.
4 | Heartbeat (keepalive every 30 s) | 1 | 🟢 Implemented / 🟡 Pending User Validation | 0x25 seq heartbeat per lens with 0x25 0x04 ACK.
5 | HUD messaging API | 2 | ⚫ Not Started | Wave 2 – sendHudMessage() broadcast + ack feedback.
6 | Event decoding (touch, case open) | 2 | ⚫ Not Started | Reserve for Phase 4.1.
7 | Diagnostic console integration | 2 | 🟢 Partial / 🟡 Pending User Validation | ConsoleInterpreter summaries; extend for firmware/case in r1c.
8 | Assistant diagnostic bridge | 3 | ⚫ Not Started | Wave 3.
9 | Monochrome theme | — | 🟢 Implemented | BleStatusView palette (black/gray/white) with status colors.
10 | Docs + progress notes | — | 🟢 Updated | [4.0-r1] + [4.0-r1b]; [4.0-r1c] pending.

⸻

🧠 CODEX-ALIGNED IMPLEMENTATION GUIDELINES (Wave 1 → 1c)

Wave 1c scope (agreed with Codex):
	•	Replace the stub BLE tool with a live bridge that honors the existing BleTool contract (no signature changes).
	•	Bind BleTelemetryRepository to MoncchichiBleService so snapshot updates flow to the UI.
	•	Surface per-lens RSSI and case battery; handle firmware as UTF-8 with hex fallback.
	•	Throttle duplicate telemetry logs (no spam), but do not suppress change events.
	•	Deduplicate scan callbacks (emit only newly discovered MACs) and ensure stopScan() actually cancels the scan coroutine.
	•	Route lens-specific console commands to the correct BLE Target (Left/Right/Both).
	•	AppLocator: augment (do not replace) existing services and wire ble to the live tool; optional feature flag to avoid double-binding during QA.
	•	Optional permission preflight to make denials visible instead of silent “connection failed.”

⸻

2️⃣ BLE Service Architecture (Delivered; r1c uses as-is)
	•	MoncchichiBleService: dual clients, sequenced writes, heartbeat, reconnect, shared incoming frames.
	•	G1BleClient: per-lens ACK tracking and RSSI reader.
	•	✅ 0x11 firmware and case telemetry already parsed (r1b).
	•	🟡 r1c: no service API changes; repository/UI wiring only.

⸻

3️⃣ Telemetry Repository (Delivered → r1c refinements)

hub/data/telemetry/BleTelemetryRepository.kt
	•	Parses battery (0x2C) / uptime (0x37) / firmware (0x11); maintains left/right LensTelemetry, uptimeSeconds, firmwareVersion.
	•	bindToService(service, scope) / unbind() helpers; auto-reset when both lenses disconnect.
	•	r1c changes (agreed):
	•	➕ Add rssi: Int? to LensTelemetry; update from MoncchichiBleService.state inside bindToService().
	•	✅ Confirm caseBatteryPercent from byte [3]; keep battery from byte [2].
	•	🔁 Emit only on change (distinctUntilChanged style) to reduce log/UI spam.
	•	🔤 Firmware: try UTF-8; if invalid/blank, store hex string fallback.
	•	🧹 When reset(): also events.tryEmit("[BLE][DIAG] telemetry reset") for the console.

⸻

4️⃣ Console Interpreter (Delivered → r1c alignment)

hub/console/ConsoleInterpreter.kt
	•	Summarizes BLE/network/API/LLM health, detects ACK timeouts/reconnects.
	•	r1c changes (agreed):
	•	➕ Include firmware version and case battery in BLE notes when present.
	•	Keep messaging concise; no change in public API.

⸻

5️⃣ UI – Monochrome BLE Status View (Delivered → bind live telemetry)

hub/ui/components/BleStatusView.kt
	•	Shows per-lens connectivity, RSSI, last ACK.
	•	r1c task: bind live BleTelemetryRepository.snapshot via AppLocator scope so RSSI/battery update in real time (no polling loops in refreshDeviceVitals()).

⸻

6️⃣ Reliability Matrix (Wave 1 → r1c)

Constraint	Guideline
Chunk delay	≈ 5 ms
ACK gate	0xC9 before next command
Retries	3× then mark degraded
Heartbeat	30 s/lens
Round-trip	≤ 2 s
Connect timeout	20 s/lens
Telemetry freshness	push via flow; one-shot reads still supported
Reset trigger	both lenses disconnected → reset() + console note

⸻

7️⃣ Wave 1 Acceptance (to verify post-r1c)

Scenario	Expected Behaviour
Dual connection + ACK	UI shows “Connected L/R”; console logs ❤️ keepalive ACK.
Live telemetry	[DIAG] left/right battery=% case=% firmware=vX.Y.Z; no hard-coded 87/62/v1.2.0.
RSSI display	Per-lens RSSI in BleStatusView (e.g., −55 dBm).
Disconnect reset	Console prints telemetry reset; UI clears values promptly.
No duplicates	Scan emits new devices once; telemetry logs only on change.
Latency	≤ 2 s app↔glasses for commands and key telemetry.

⸻

8️⃣ Files to Implement / Modify in Wave 1c (agreed with Codex)

Keep all other AppLocator singletons intact (memory/router/llm/display/prefs/perms/diagnostics/etc.). Only the BLE wiring changes.

App DI
	•	hub/di/AppLocator.kt
	•	➕ Add appScope = SupervisorJob()+Dispatchers.Default.
	•	➕ Feature flag useLiveBle (default true in QA).
	•	🔁 Set ble to BleToolLiveImpl when flag = true; otherwise keep BleToolImpl.
	•	⚠️ Ensure init(ctx) runs before any AppLocator.ble access (unchanged behavior).

BLE Tool (Live bridge)
	•	hub/tools/impl/BleToolLiveImpl.kt (new file)
	•	Contract compliance: exact BleTool signatures; disconnect() returns Unit, not Boolean.
	•	Imports: use com.loopermallee.moncchichi.hub.tools.ScanResult (top-level data class).
	•	Scanner usage: construct BluetoothScanner(context), call scanner.start() / scanner.stop() (no args).
	•	Scan dedupe: maintain seenMacs: MutableSet<String>; only invoke onFound for new addresses; clear set in stopScan().
	•	Scan lifecycle: store scanJob; cancel it in stopScan(); cancel previous job on re-scan.
	•	Connect: resolve BluetoothDevice (e.g., BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceId)), call MoncchichiBleService.connect(device); on success, remember lastConnectedMac for macAddress().
	•	Telemetry bind: instantiate BleTelemetryRepository(logger=…) and call bindToService(service, appScope) in init; do not create long-lived collectors in refreshDeviceVitals().
	•	Send mapping: map console strings → G1Packets helpers and Target:
	•	PING → G1Packets.ping() (Both)
	•	BRIGHTNESS_UP/DOWN → choose absolute level (temporary policy, e.g., 70/30), target = Both
	•	LENS_LEFT_ON/OFF, LENS_RIGHT_ON/OFF → per-lens target (Left/Right)
	•	DISPLAY_RESET → G1Packets.textPageUtf8("") (Both)
	•	Unknown → G1Packets.textPageUtf8(command) (Both)
	•	Battery / Firmware → G1Packets.batteryQuery() / firmwareQuery() (Both)
	•	Permissions (optional preflight): helper requiredPermissions(): List<String> that checks BT/Location and returns missing; VM can surface a user-friendly message.
	•	Getters: battery()/caseBattery()/firmware()/signal()/macAddress() read from repository snapshot and MoncchichiBleService.state (for RSSI). Return null when disconnected.

Telemetry Repository
	•	hub/data/telemetry/BleTelemetryRepository.kt
	•	➕ LensTelemetry.rssi: Int? and update RSSI from service.state within bindToService() (collect state and merge).
	•	🔤 Firmware parser: try UTF-8; fallback to hex on invalid/blank payload.
	•	🔁 Emit updates only when values change (local compare / update guard).
	•	🧹 On reset(): also events.tryEmit("[BLE][DIAG] telemetry reset").

Hub ViewModel
	•	hub/viewmodel/HubViewModel.kt
	•	🔇 Throttle: keep hubAddLog("[BLE] Telemetry • …"), but guard with a cached digest so repeats don’t spam.
	•	🚫 Do not add infinite collectors in refreshDeviceVitals() (leave as one-shot reads).
	•	(Optional) Before ble.connect(id), query ble.requiredPermissions() and log a clear message when missing.

Console
	•	hub/console/ConsoleInterpreter.kt
	•	➕ Add firmware + case battery to BLE notes where available.
	•	(No API change.)

Protocol helpers
	•	hub/bluetooth/G1Protocol.kt
	•	✅ Already contains batteryQuery()/firmwareQuery()/ping()/brightness()/reboot()/textPageUtf8(); no changes required for r1c.

⸻

9️⃣ Known Gaps / Next Patch Objectives (Wave 1c)

Area	Task	Status / Planned Action
AppLocator wiring	Swap ble to live implementation, keep every other singleton intact	🟡 Planned r1c
Service ↔ Repo	Call bindToService(service, appScope) once; provide unbind() on shutdown	🟡 Planned r1c
Scan dedupe + stop	Seen-set + scanJob cancellation; reset seen on stopScan()	🟡 Planned r1c
Lens-target routing	Map left/right console commands to Target.Left/Right	🟡 Planned r1c
RSSI to UI	Store RSSI in repository; surface in BleStatusView	🟡 Planned r1c
Firmware fallback	UTF-8 else hex string	🟡 Planned r1c
Telemetry logs	Throttle duplicates; retain log on changes	🟡 Planned r1c
Permissions UX	Optional preflight helper in live tool; VM logs missing perms	🟡 Planned r1c
Feature flag	useLiveBle in AppLocator to avoid AIDL coexistence conflicts during QA	🟡 Planned r1c

⸻

🧾 PROGRESS NOTES
	•	[4.0-r1] ✅ Wave 1 foundations — dual-GATT service, ACK client, telemetry base, monochrome UI.
	•	[4.0-r1b] ✅ Firmware parsing, disconnect reset, ping/brightness/reboot helpers.
	•	[4.0-r1c] 🟡 Planned — Live BLE binding in AppLocator, repository binding to service, scan dedupe + stop, RSSI to UI, firmware hex fallback, telemetry log throttling, lens-specific routing, optional permission preflight.

⸻

✅ Summary

Wave 1a → 1b delivered the BLE core and parser. Wave 1c (per Codex agreement) replaces the stub tool with the live bridge, binds the telemetry repository to the service, and connects live battery/firmware/RSSI data to the Hub UI and console—without changing the BleTool contract.
All r1c changes above are pending Codex green-light and will be validated by runtime acceptance tests before moving to Wave 2 (HUD + event visuals).
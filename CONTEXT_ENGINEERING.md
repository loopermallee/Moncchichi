Perfect — here’s your clean, updated CONTEXT_ENGINEERING v1.7, rewritten to reflect the latest firmware-based BLE logic (v1.6.3 sync) and remove sections that have already been patched or completed.
This version becomes your new authoritative roadmap before moving to Audio/MCP phases.

⸻

CONTEXT_ENGINEERING v1.7 — BLE Core Realignment & Audio Preparation

1. CURRENT STATE
	•	Dual-lens BLE architecture now stable post-Phase 4.0 r1e (HUD & telemetry confirmed).
	•	HUD text and clear display confirmed operational (0x09, 0x25).
	•	Mic watchdog functional and heartbeat partially implemented.
	•	Remaining instability tied to outdated ACK logic and incomplete telemetry decoding.
	•	Latest Even Reality firmware (v1.6.3) introduces new opcodes (0xCB, 0x2B–0x39, 0x23 72/6C, etc.) that the app must now support.
	•	Build succeeds; runtime validation pending for updated protocol handling.
	•	MCP (Model Context Protocol) and voice integration remain deferred until BLE parity is achieved.

⸻

2. GOALS
	1.	Achieve full BLE parity with v1.6.3 firmware — eliminate false “→ PING ← ERR” logs.
	2.	Implement 0xCB continuation ACKs and update telemetry map.
	3.	Stabilize heartbeat (0x25) and add lens-safe reconnects.
	4.	Expand telemetry parsing to include system, gestures, uptime, and environment data.
	5.	Prepare clean ground for upcoming AudioOutManager / Voice UI integration.

⸻

3. BLE LOGIC REALIGNMENT (Phase 4.0 r1)

Purpose:
Update Moncchichi Hub BLE stack to reflect Even Reality v1.6.3 protocol structure and handshake timing.

ACK Layer
	•	Recognize binary 0xC9 / 0x04, textual “OK”, and continuation 0xCB.
	•	Resume multi-frame transfers on 0xCB.
	•	Suppress redundant error logs for textual OK responses.
	•	Mark unknown ACKs once per session with [WARN][ACK].

Heartbeat (0x25)
	•	Send every 28 – 30 s with its own sequence counter.
	•	Reset timer upon any inbound frame.
	•	Disconnect and trigger RebondManager after 3 missed beats.

Display / Voice-Wake (0x26)
	•	Dual-mode parser:
	•	Short len = 5–7 → Dashboard geometry update.
	•	len = 6 → Voice-wake toggle (enable/disable speech trigger).
	•	Preserve backward compatibility for legacy HUD packets.

Telemetry Expansion

Opcode	Description	Output Type
0x2B	Device state / connection flags	DeviceStatus
0x2C	Battery voltage + charge flag	BatteryInfo
0x32–0x37	Env / uptime / sensor metrics	DeviceTelemetrySnapshot
0x39	System OK / confirmation	AckEvent
0xF5	Gesture (tap, hold, translate, etc.)	GestureEvent

	•	Update BleTelemetryParser and ProtocolMap accordingly.
	•	Publish through unified Flow<DeviceTelemetry>.

Notification Stream
	•	Handle multi-packet JSON payloads (0x04, 0x4B, 0x4C) using 0xCB continuation.
	•	Reassemble fragments before dispatch to DeveloperViewModel.

Reboot / Debug (0x23 72 / 0x23 6C)
	•	Add stubs in G1Protocols and MoncchichiBleService.
	•	Implement console-only triggers (no UI).

⸻

4. TELEMETRY VALIDATION
	•	Battery (0x2C) refreshes ≈ every 30 s.
	•	Uptime (0x37) monotonic between frames.
	•	Gesture (0xF5) events appear instantly per lens.
	•	Confirm DeviceTelemetrySnapshot timestamps match reception time.
	•	No degradation to HUD or mic watchdog.

⸻

5. AUDIOOUTMANAGER DESIGN (Phase 4.0 r2 – Upcoming)
	•	Enum AudioSink { GLASSES, WEARABLE, PHONE }.
	•	Backed by SettingsRepository with Flow<AudioSink>.
	•	Integrates with Android AudioManager / TTS.
	•	Cross-fade (~100 ms) on sink switch.
	•	Default = GLASSES.
	•	Will connect to Voice & Audio UI in next phase.

⸻

6. VOICE & AUDIO SETTINGS UI (Phase 4.0 r3 – Upcoming)

Purpose:
Expose microphone and output routing to users and link UI controls to new AudioOutManager and MicStreamManager.

Core Elements
	•	VoiceAudioSettingsFragment under hub/ui/settings/.
	•	Toggles: “Audible Responses”, “Prefer Phone Mic”.
	•	Dropdown: “Output Device” (Auto / Phone / Headset).
	•	Dynamically requests RECORD_AUDIO permission on toggle.
	•	Uses flows from SettingsRepository → updates AudioOutManager.

Success Criteria
	1.	Settings panel live-updates routing.
	2.	Permission prompts appear once; degrade gracefully.
	3.	Cross-device audio switch seamless.
	4.	Preferences persist between sessions.
	5.	UI ready for MCP/Voice integration (Phase 5.0).

⸻

7. MCP PREPARATION (Phase 5.0 Preview)
	•	Stub McpBridge.kt with init(), sendContext(), receiveContext().
	•	Local context model engine only (no network calls).
	•	Will later integrate audio context and memory embedding.

⸻

8. EXECUTION PLAN
	1.	Phase 4.0 r1 → BLE Logic Realignment (current patch).
	2.	Phase 4.0 r2 → AudioOutManager implementation.
	3.	Phase 4.0 r3 → Voice & Audio UI + permissions.
	4.	Phase 4.0 r4 → Full BLE validation (sustained connection test).
	5.	Phase 5.0 → MCP + Assistant Integration.

⸻

✅ Verification Checklist
	•	BLE ack/heartbeat stable (no false errors).
	•	Dual-lens telemetry consistent.
	•	HUD and voice-wake operate without conflict.
	•	Battery and gesture data validated.
	•	Ready for AudioOutManager phase merge.

⸻

Summary:
This v1.7 document supersedes all prior stabilization notes.
It assumes HUD display, mic watchdog, and build unblock are complete.
Codex should focus solely on implementing and validating the BLE realignment layer before advancing to Audio and Voice phases.
CONTEXT_ENGINEERING.md v1.7 — BLE Core Realignment & Audio Preparation (Aligned with Even Reality v1.6.6)

1. CURRENT STATE
	•	Dual-lens BLE architecture operational; HUD text and clear commands (0x09, 0x25) confirmed.
	•	Mic watchdog active; heartbeat partial.
	•	Outdated ACK logic still triggers false “→ PING ← ERR” events.
	•	Even Reality firmware v1.6.6 adds new ACK codes (0xC0, 0xCB), revised audio (0xF1) headers, and sub-opcode behavior for 0x26.
	•	Build is successful; runtime BLE verification in progress.
	•	MCP and voice integration remain deferred until BLE parity achieved.

⸻

2. GOALS
	1.	Achieve full BLE parity with v1.6.6 (protocol ack logic + continuations).
	2.	Stabilize heartbeat and mic sessions for > 30 s links.
	3.	Add support for 0x26 dual-mode (Dashboard / Voice-Wake).
	4.	Implement new telemetry set (0x2B–0x39, 0xF5).
	5.	Prepare for AudioOutManager and Voice UI integration.

⸻

3. BLE LOGIC REALIGNMENT (Phase 4.0 r1)

ACK Layer
	•	Recognize 0xC9, 0x04, 0xC0, 0xCB, and text “OK”.
	•	0xCB → continuation; 0xC0 → transfer complete.
	•	Suppress duplicate error logs for textual ACKs.
	•	Warn once per session on unknown ACKs.

Heartbeat (0x25)
	•	Interval 28–30 s.
	•	Reset on any RX frame; rebond after 3 misses.
	•	Logged via [HB][Lens][OK].

Display / Voice-Wake (0x26)
	•	Sub-opcode defines mode: Dashboard (geometry bytes) vs Voice-Wake toggle.
	•	Backward HUD compatibility preserved.

Audio Stream (0xF1)
	•	Now includes 2-byte length + channel prefix.
	•	Update parser to extract PCM payload correctly.

Telemetry Expansion

Opcode	Function	Output Type
0x2B	Device state / flags	DeviceStatus
0x2C	Battery (mV + charging)	BatteryInfo
0x32–0x37	Env + uptime	DeviceTelemetrySnapshot
0x39	System OK	AckEvent
0xF5	Gesture	GestureEvent

	•	Update ProtocolMap and BleTelemetryParser.
	•	Emit to Flow<DeviceTelemetry> and Developer console.

Notification Stream
	•	Reassemble multi-frame JSON (0x04 / 0x4B / 0x4C) using continuations.

Reboot / Debug (0x23 72 / 0x23 6C)
	•	Console-only stubs for future diagnostics.

⸻

4. TELEMETRY VALIDATION
	•	Battery updates ≈ 30 s.
	•	Uptime monotonic.
	•	Gestures instant per lens.
	•	Timestamps match reception time.
	•	No regression in HUD or mic watchdog.

⸻

5. AUDIOOUTMANAGER DESIGN (Phase 4.0 r2 – Next)
	•	enum AudioSink { GLASSES, WEARABLE, PHONE }.
	•	Flow-backed preference in SettingsRepository.
	•	Integrates with Android AudioManager and TTS.
	•	100 ms cross-fade on sink switch.
	•	Default sink = GLASSES.

⸻

6. VOICE & AUDIO SETTINGS UI (Phase 4.0 r3 – Next)
	•	VoiceAudioSettingsFragment under hub/ui/settings/.
	•	Toggles: Audible Responses, Prefer Phone Mic.
	•	Dropdown: Output Device (Auto / Phone / Headset).
	•	Runtime RECORD_AUDIO permission.
	•	Live binding to AudioOutManager / MicStreamManager.

⸻

7. MCP PREPARATION (Phase 5.0 Preview)
	•	Stub McpBridge.kt with init(), sendContext(), receiveContext().
	•	Local-only context engine (no network).

⸻

8. EXECUTION PLAN
	1.	Phase 4.0 r1 → BLE Realignment (current patch)
	2.	Phase 4.0 r2 → AudioOutManager implementation
	3.	Phase 4.0 r3 → Voice & Audio UI + permissions
	4.	Phase 4.0 r4 → BLE long-duration validation
	5.	Phase 5.0 → MCP / Assistant integration

⸻

✅ Verification Checklist
	•	ACK & heartbeat stable.
	•	Dual-lens telemetry consistent.
	•	HUD + Voice-Wake non-conflicting.
	•	Battery & gesture events valid.
	•	Ready for AudioOutManager merge.

⸻

🔖 Summary

This v1.7 document fully supersedes prior 1.6.3-based plans.
It reflects Even Reality firmware v1.6.6 protocol behavior, with updated ACK, audio framing, and telemetry logic.
Codex must complete the BLE realignment before audio or MCP phases begin.
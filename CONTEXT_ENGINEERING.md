# CONTEXT_ENGINEERING v1.6 — BLE ↔ Voice Stabilization Roadmap

## 1. CURRENT STATE
- Dual-lens BLE architecture operational but unstable.
- Repeated `[ACK][R] opcode=0x00 status=0xCA → FAIL` indicates right-lens command mis-sequencing.
- Heartbeat (`0x25`) missing → lenses disconnect after 32 s.
- BLE ACKs may appear as either binary (0xC9/0x04) or textual “OK”.
- Telemetry (`0xF1` audio, `0x2C` battery, `0x37` uptime) partially parsed.
- Build currently fails due to missing `AudioOutManager` and unimplemented Voice Settings fragments.
- MCP (Model Context Protocol) integration planned but deferred until BLE stabilized.

## 2. GOALS
1. Restore full BLE reliability (ACK logic + mic watchdog).
2. Bring back HUD display commands (0x20/0x21/0x26).
3. Expand telemetry coverage (battery, uptime, gestures).
4. Re-enable build with modular audio architecture (AudioOutManager, Voice UI).
5. Prepare stub interfaces for future MCP local memory system.

## 3. BLE LOGIC DETAILS
- Left/Right radios use Nordic UART with opcodes:
  - `0x11` Firmware Info, `0x2C` Battery, `0x37` Uptime,
  - `0x25` Heartbeat, `0x26` Dashboard Position,
  - `0xF1` Audio frames, `0xF5` Gestures.
- Each packet = `[Header][Opcode][Len][Seq][Payload][CRC]`.
- App must send `0x25` heartbeat every ≤ 30 s.
- ACK expected within < 150 ms. Missing ACK triggers retry.
- Textual “OK” occurs on firmware v1.5.x+ instead of binary ACK.

## 4. HUD Display and Text Pipeline (Phase 4.0 r1e)

Purpose: Re-enable HUD text output via G1 protocol commands (0x09, 0x25) and ensure display clears on session reset.
Depends on stable BLE and telemetry (Phase 4.0 r1d).

Design:
- Command 0x09 sends UTF-8 payload for HUD text.
- Command 0x25 clears the display.
- Routing uses DashboardDataEncoder → MoncchichiBleService → HudTextManager.
- Auto-clear on reset/unbind prevents ghost frames.

Success Criteria:
1. HUD shows text sent from app without errors.
2. Clears automatically on disconnect or reset.
3. No impact to mic/ACK telemetry.
4. Patch compiles and passes manual display validation.

## 5. MIC WATCHDOG
- Monitor incoming `0xF1` frames.
- If no frame within 2 s, restart BLE mic.
- Report `micAlive` → false when exceeded threshold.
- Restart using existing coroutine scope in `HubBleService`.

## 6. HUD COMMANDS
- `0x20` Display Text → payload UTF-8 string + len.
- `0x21` Clear Display → no payload.
- `0x26` Dashboard Position → [0x02, 0x00/0x01, vertical (1–8), distance (1–9)].
- Use for in-glasses text rendering and placement control.

## 7. TELEMETRY EXTENSION
- `0x2C` → Battery (voltage + charge flag).  
- `0x37` → Uptime (seconds since boot).  
- `0xF5` → Gesture (events: tap, swipe, hold).  
- Merge into `DeviceTelemetrySnapshot` with timestamp.  
- Publish via `Flow<DeviceTelemetry>`.

## 8. AUDIOOUTMANAGER DESIGN
- Enum class `AudioSink { GLASSES, WEARABLE, PHONE }`.  
- Pref-backed Flow for sink changes.  
- Uses Android AudioManager / AudioDeviceInfo for routing.  
- Handles cross-fade (100 ms) when sink changes.  
- Default sink = GLASSES.  
- Integrates with TTS engine and VoiceAudioSettings.

## 9. VOICE & AUDIO SETTINGS UI
- New fragment `VoiceAudioSettingsFragment`.  
- Toggles: “Audible Responses” (on/off), “Prefer Phone Mic”.  
- Dropdown: Output Device (Auto / Phone / Headset).  
- Requests `RECORD_AUDIO` permission at runtime.  
- Updates preferences and notifies AudioOutManager / MicStreamManager.

## 10. PERMISSIONS
Manifest addition:  
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
Runtime check with ActivityCompat.requestPermissions() → REQ_CODE_AUDIO.

## 11. MCP PREPARATION
- Stub McpBridge.kt with empty methods init(), sendContext(), receiveContext().
- Placeholder for local context model engine.
- No network or OpenAI calls yet; local only.

## 12. EXECUTION PLAN
1. Build Unblock: stub missing audio classes.
2. Patch 1: ACK logic + mic watchdog.
3. Patch 2: HUD display and clear.
4. Patch 3: Telemetry (battery, gesture).
5. Patch 4: Voice and Audio UI + permissions.
6. Validate: dual-lens sync, telemetry stability, no crash on mic toggle.
7. Then: MCP integration phase begins.


⸻

This document provides Codex full architectural context for implementing the above patches with minimal ambiguity. All commands, file paths, and expected behaviors are explicit.

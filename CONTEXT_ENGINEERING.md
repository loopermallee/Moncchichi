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

## 7. TELEMETRY EXTENSION (Phase 4.0 r1f)

**Purpose:**  
Integrate real-time status streams for battery (0x2C), uptime (0x37), and gesture (0xF5) telemetry.  
Builds on the stable BLE and HUD foundations (Phases r1d–r1e) to provide complete device-state visibility.

**Design:**  
- `0x2C` → Battery telemetry  
  - Returns voltage (mV) + charging flag (0 = idle, 1 = charging).  
  - Parsed into `BatteryInfo(voltage:Int, isCharging:Boolean)`.  
- `0x37` → Uptime telemetry  
  - Reports seconds since lens boot.  
  - Used to confirm connection stability and runtime drift.  
- `0xF5` → Gesture telemetry  
  - Captures events such as tap, double-tap, hold, or swipe.  
  - Parsed into `GestureEvent(code:Int, name:String)` and emitted with lens tag.  
- Extend `BleTelemetryParser` with opcode→handler map producing typed `TelemetryEvent` objects.  
- Update `BleTelemetryRepository` to subscribe to parser events and maintain:  
  - `_battery: StateFlow<BatteryInfo?>`  
  - `_gesture: SharedFlow<GestureEvent>`  
  - Uptime updates merged into the unified `DeviceTelemetrySnapshot` with timestamps.  
- Expose all new telemetry via `Flow<DeviceTelemetry>` and surface through `DeveloperViewModel` for live debugging.  
- Log to console using tags `[VITALS]` for battery/uptime and `[GESTURE]` for gesture input.

**Success Criteria:**  
1. Battery status visible and refreshed every ≈ 30 s while connected.  
2. Uptime counter monotonic with no resets between packets.  
3. Gesture events appear instantly per-lens in the Developer console.  
4. All new handlers coexist with legacy `when{}` parsers (no breakage).  
5. No regression to BLE stability, ACK timing, or mic watchdog.  
## 8. AUDIOOUTMANAGER DESIGN
- Enum class `AudioSink { GLASSES, WEARABLE, PHONE }`.  
- Pref-backed Flow for sink changes.  
- Uses Android AudioManager / AudioDeviceInfo for routing.  
- Handles cross-fade (100 ms) when sink changes.  
- Default sink = GLASSES.  
- Integrates with TTS engine and VoiceAudioSettings.

## 9. VOICE & AUDIO SETTINGS UI (Phase 4.0 r1g)

**Purpose:**  
Provide users with a centralized interface to manage microphone routing, audio output, and voice-feedback behavior.  
Completes the BLE ↔ Voice stabilization cycle by giving control over the new AudioOutManager and MicStreamManager.

---

**Design & Implementation**

- **Fragment:** `VoiceAudioSettingsFragment`
  - Built under `hub/ui/settings/`.
  - Accessible via the main Settings menu or overflow navigation in `SettingsFragment`.
  - Contains:
    - **Toggle 1 – “Audible Responses”** → Enables/disables TTS or spoken feedback.
    - **Toggle 2 – “Prefer Phone Mic”** → Switches mic input routing between Glasses / Phone.
    - **Dropdown – “Output Device”** → Choices: Auto / Phone / Headset (GLASSES default).
  - Requests `RECORD_AUDIO` permission dynamically when user enables “Prefer Phone Mic”.

- **AudioOutManager** (`hub/audio/AudioOutManager.kt`)
  - Centralized sink router with `enum AudioSink { GLASSES, WEARABLE, PHONE }`.
  - Backed by a reactive `StateFlow<AudioSink>` persisted in `SettingsRepository`.
  - Handles cross-fade (~100 ms) between sinks and will later link to Android AudioManager/TTS routing.

- **SettingsRepository**
  - New keys and flows:
    - `KEY_AUDIO_SINK` → `audioSinkFlow`
    - `KEY_AUDIBLE_RESPONSES` → `audibleResponsesFlow`
    - `KEY_PREFER_PHONE_MIC` → `preferPhoneMicFlow`
  - Each exposes a `Flow` that immediately updates `AudioOutManager` and `MicStreamManager`.

- **Permission Handling**
  - Manifest includes:
    ```xml
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    ```
  - Runtime request handled in `HubViewModel.requestAudioPermission()`, using `ActivityCompat.requestPermissions()`.

- **Integration**
  - `HubViewModel` observes flows from `SettingsRepository` and updates active mic routing.
  - `MicStreamManager` restarts capture when source changes or permission granted.

---

**Success Criteria**
1. Settings screen is reachable and reflects live preference states.  
2. Mic routing updates instantly when user toggles “Prefer Phone Mic.”  
3. Audio output changes between devices with no crashes or stutter.  
4. Permission prompt appears only once and degrades gracefully on denial.  
5. All preferences persist between sessions and sync with repository flows.  
6. Ready baseline for MCP/Voice-Assistant integration in Phase 5.0.  

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

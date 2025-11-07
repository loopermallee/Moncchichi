CONTEXT ENGINEERING UPDATE — v4.4 Multi-Source Audio Routing Layer
Moncchichi Hub Context Engineering v4.4

Extends v4.3. Introduces multi-source audio routing between Glasses, Wearable, and Phone.

────────────────────────────────────────
SECTION 1 – AUDIO ROUTING ARCHITECTURE
────────────────────────────────────────
• All audio flows pass through MicStreamManager (input) and AudioOutManager (output).
• SettingsRepository persists preferred MicSource & AudioSink.
• AppLocator provides singletons for both managers.
• Each manager exposes a StateFlow for UI updates (rx rate, active sink label, errors).
• BLE G1 remains default; other sources/sinks activated on demand.

────────────────────────────────────────
SECTION 2 – MIC INPUT ROUTING
────────────────────────────────────────
Order of preference → GLASSES (BLE 0xF1 stream) > WEARABLE (Bluetooth SCO) > PHONE (AudioRecord 16 kHz mono).
Switch within 200 ms on source change or disconnect.
Apply AEC when input and output both Phone.
Stats: rxRate (pkts/s) + gaps count → Developer Console tag [DIAG][AUDIOIN].

────────────────────────────────────────
SECTION 3 – AUDIO OUTPUT ROUTING
────────────────────────────────────────
Sink priority → GLASSES (HUD BLE speaker) > WEARABLE (A2DP) > PHONE (AudioTrack).
Prebuffer 100–200 ms and cross-fade on switch.
Playback uses Flow with single writer model.
Console tag [DIAG][AUDIOOUT].

────────────────────────────────────────
SECTION 4 – SETTINGS CONTRACT
────────────────────────────────────────
Keys: preferred_mic_source, preferred_audio_sink.
Enums MicSource & AudioSink live in audio/AudioRouting.kt.
Values persist in SharedPreferences and are observed as Flow.
Defaults → GLASSES/GLASSES.
Auto-restore on launch and BLE reconnect.

────────────────────────────────────────
SECTION 5 – UI / USER WORKFLOW
────────────────────────────────────────
VoiceAudioSettingsFragment lists two toggle groups (Input, Output).
Selecting a new option updates SettingsRepository and restarts managers.
BLE status affects availability of “Glasses” options.
Fallbacks occur silently with console note [DIAG][ROUTE].

────────────────────────────────────────
SECTION 6 – PERMISSIONS & LIFECYCLE
────────────────────────────────────────
Request RECORD_AUDIO & BLUETOOTH_CONNECT when needed.
Handle permission denial with toast + fallback Phone mic.
Stop all streams on fragment destroy or BLE service stop.
Lifecycle binding follows existing HubService patterns.

────────────────────────────────────────
SECTION 7 – EXPECTED BEHAVIOUR / TESTS
────────────────────────────────────────
• BLE connected → Mic/Sink = Glasses.
• BLE drop → fallback Wearable → Phone.
• Manual toggle re-routes ≤ 200 ms.
• Prefs persist across sessions.
• Reconnection restores user selection or defaults.

────────────────────────────────────────
SECTION 8 – EXTENSIBILITY
────────────────────────────────────────
Future hooks → multi-channel beamforming input or spatial TTS.
Both MicStreamManager and AudioOutManager expose public switchSource()/switchSink() for AI assistant integration.

────────────────────────────────────────
SECTION 9 – LOG FORMAT
────────────────────────────────────────
Follow v1.1 §4 console schema →
[AUDIOIN] Using Phone mic (16 kHz)
[AUDIOOUT] Routing to Glasses HUD
Throttled ≤ 3/s.
---
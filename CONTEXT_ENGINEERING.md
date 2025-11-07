# CONTEXT_ENGINEERING.md v1.4
Extends v1.3. Locks parser architecture + plumbing so Codex can implement safely.

────────────────────────────────────────
§1. Parser Coexistence & Migration Plan
────────────────────────────────────────
• Introduce ProtocolMap + BleTelemetryParser alongside the legacy when{} in BleTelemetryRepository.
• Route initial set: 0x2B, 0x2C/01, 0x37, 0xF1, 0xF5.
• Unknown or not-yet-migrated opcodes fall back to legacy when{}.
• Goal: migrate ≥90% before removing legacy path.

────────────────────────────────────────
§2. ProtocolMap Contract
────────────────────────────────────────
• Map<Opcode, Handler>, where Handler = (lens, payload, ts) → List<TelemetryEvent>.
• Handlers call canonical parsing helpers in G1ReplyParser (telemetry package).
• Events: StateEvent, BatteryEvent, UptimeEvent, AudioPacketEvent(seq, data), F5Event(code, meta).
• Emit console via §4 tags ([STATE], [VITALS], [GESTURE], [DIAG]); throttle ≤3/s.

────────────────────────────────────────
§3. BleTelemetryParser Contract
────────────────────────────────────────
• Input: frame(opcode, subOpcode?, lens, payload, ts).
• Look up handler in ProtocolMap; emit typed events via callbacks to Repo.
• Return boolean handled; Repo uses legacy when{} if false.

────────────────────────────────────────
§4. Canonical G1ReplyParser
────────────────────────────────────────
• Location: core/.../telemetry/G1ReplyParser (authoritative).
• Old file in core/.../core/ble is deprecated → delegate or remove next patch.
• Helpers: parseState(0x2B codes), parseBattery(0x2C/01), parseUptime(0x37), parseF5(code-range), parseFirmwareAscii([0x23,0x74]).
(Use modular routing style similar to “handler flows” from your refs.  [oai_citation:8‡ajay-bhargava-clairvoyant-8a5edab282632443.txt](file-service://file-Po5GeTwpn9ivKUHHkfDTpo)  [oai_citation:9‡ajay-bhargava-clairvoyant-8a5edab282632443.txt](file-service://file-Po5GeTwpn9ivKUHHkfDTpo))

────────────────────────────────────────
§5. MicStreamManager (0xF1) & Mic Toggle (0x0E)
────────────────────────────────────────
• Lives inside BleTelemetryRepository; created on first 0xF1.
• Tracks: lastSeq, rxRate (packets/s), gaps; exposes State{rxRate,gaps,lastSeq}.
• sendMicToggle(enable): write 0x0E; expect 0xC9; persist Settings.mic_enabled; [DIAG] single-line log.
(Wake/audio managers with clean callbacks mirror patterns in wake systems.  [oai_citation:10‡picovoice-porcupine-8a5edab282632443.txt](file-service://file-3F7UzxX2y7CrSx3JdBsPJB)  [oai_citation:11‡picovoice-porcupine-8a5edab282632443.txt](file-service://file-3F7UzxX2y7CrSx3JdBsPJB))

────────────────────────────────────────
§6. DashboardDataEncoder (0x06 07–1C)
────────────────────────────────────────
• Command-path utility to chunk >180B payloads into ≈189B packets; add sequence header; small inter-packet delay.
• Repo enqueues bursts; single concise “[DIAG] Dashboard burst …” per send.

────────────────────────────────────────
§7. Developer UI & Settings Contract
────────────────────────────────────────
• Settings: mic_enabled(bool), voice_wake_on_lift(bool).
• Developer screen: toggles (mic/voice-lift), cards: audio stats (rx,gaps), dashboard burst status, firmware info.
• Placeholders “—/Waiting…” if data not ready.

────────────────────────────────────────
§8. Expected Behaviour Snapshot
────────────────────────────────────────
• Map-driven handlers process migrated opcodes; legacy path covers the rest.
• No UI regressions; telemetry stable; console format per v1.1 §4.
• Mic toggle persists + logs; audio stats live-update; dashboard bursts are queued and throttled.
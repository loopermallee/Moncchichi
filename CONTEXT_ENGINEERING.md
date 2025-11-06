# CONTEXT_ENGINEERING.md v1.2
Extends v1.1 with Teleprompter pipeline & HUD sync rules aligned to Open-Teleprompter.

───────────────────────────────────────────────
SECTION 6 – TELEPROMPTER PIPELINE (OPEN-TELEPROMPTER STYLE)
───────────────────────────────────────────────
Purpose
• Provide a local teleprompter UI and stream compact, readable text lines over BLE to both lenses at a controlled cadence.

Dual-Lens Posture
• Broadcast identical HUD lines to LEFT and RIGHT to avoid desync. When protocol requires side specificity, override at call-site (not in this v1).

Transport & Opcodes (mapped to our constants)
• Use the existing UART/HUD text channel already used for console text.
• Teleprompter control in G1OT uses explicit TP opcodes (e.g., `GLASSES_CMD_TELEPROMPTER`, `…_END`, control/header sizes) and conservative per-packet delays; we mirror the conservative pacing for reliability even when sending plain text.  [oai_citation:6‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)  [oai_citation:7‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)

Sizing & Timing
• Packet size: keep ≤ ~40 bytes for HUD text to avoid fragmentation on typical firmware; if a line exceeds, split and send the first segment only; subsequent segments deferred until next cadence tick.
• Cadence: 2–3 s between HUD sends (per v1 objective). G1OT uses small inter-packet delays (e.g., 5–10 ms) inside multi-packet sequences; our v1 rarely multi-segments, but keep a small intra-burst delay when needed.  [oai_citation:8‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)  [oai_citation:9‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)

Mirroring & State
• Mirror affects on-phone preview only; do not reverse characters for HUD.
• Persist last text, speed, mirror with SettingsRepository to restore state after process death.

UI & VM Contracts
• VM exposes: `text`, `speed`, `isPlaying`, `isMirror`, `isHudSync`, `visibleLine`.
• Auto-scroll calculates visible line index by current scroll offset; export a short line (ASCII-safe) for HUD.
• VM throttles HUD emission; if `visibleLine` unchanged, skip send.
• Errors are non-blocking; a single console line marks failure; next success resets quiet period.

Console Format
• Reuse v1.1 Section 4 log schema:
  `[PROMPT][L] <line-preview> @ HH:mm:ss`
  `[PROMPT][R] <line-preview> @ HH:mm:ss`
• Max line length in logs: ≤ 60 chars; UI truncates with ellipsis.

Acceptance Signals (non-UI)
• Telemetry remains stable while Teleprompter runs; no interference with ACK hygiene.
• No burst spam: ≤ 1 HUD send per side per cadence; intra-burst delay when multi-segmenting. 
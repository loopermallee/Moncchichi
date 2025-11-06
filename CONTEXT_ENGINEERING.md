# CONTEXT_ENGINEERING.md v1.3
Extends v1.2 with new/clarified opcode behaviors and helpers.

───────────────────────────────────────────────
SECTION 7 – MICROPHONE & AUDIO STREAM (0x0E, 0xF1)
───────────────────────────────────────────────
0x0E Mic Toggle:
• App issues ON/OFF; expect 0xC9 for success; persist last user choice.
• Log once: “[DIAG] Mic ON/OFF ack OK” or “…failed”.

0xF1 Audio Packets:
• Sequential PCM frames with seq 0–255 wrap-around.
• Maintain circular buffer with continuity check; mark gaps; do not block main UI/telemetry.
• MicStreamManager: owns buffer, seq tracking, simple stats (rx rate, gaps).

───────────────────────────────────────────────
SECTION 8 – DASHBOARD / MAP ENCODING (0x06, subcmd 0x07–0x1C)
───────────────────────────────────────────────
• Encodes 3D bitmap/arrow/meta into multi-packets.
• Chunking target ≈189 B; include a small inter-packet delay and queue to avoid GATT overrun.
• Sequence tag format: “07 1c 00 <seq> …” (follow device spec).
• DashboardDataEncoder: build chunks; BleTelemetryRepository enqueues; a single burst log.

───────────────────────────────────────────────
SECTION 9 – HEAD-LIFT VOICE ACTIVATION (0x26, len=06)
───────────────────────────────────────────────
• Parser branches by packet length:
  - len==6 → voice activation toggle payload: [26 06 00 <enable 00/01> 08 <flag 00/01>]
  - len==8 → display depth/height control (existing)
• Developer screen exposes “Voice Wake-on-Lift” toggle/readout.

───────────────────────────────────────────────
SECTION 10 – STATE & POWER CLARIFICATIONS (0x2B, 0x2C)
───────────────────────────────────────────────
0x2B state codes:
• 06 worn • 07 not worn • 08 case open • 0A case closed • 0B case closed + plugged
→ Map directly; emit `[STATE][L/R]` with reason strings; drop heuristics.

0x2C power read (sub 0x01):
• byte2 battery %, byte3 voltage/charge state; validate 0–100 %.
• BATTERY frames authoritative; ignore quick status if fresh reading ≤10 s (v1.1).
(0x2C present in G1OT constants; firmware request also defined.)  [oai_citation:6‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)

───────────────────────────────────────────────
SECTION 11 – SYSTEM & ASCII HELPERS (0x10, 0x23)
───────────────────────────────────────────────
• 0x10 reset 0° → expect 0xC9.
• 0x23 0x72 reboot → no response expected.
• 0x23 0x74 firmware info → ASCII line(s) with version/build/DeviceID; parse into fields and surface in Developer UI (Firmware Info card).
(Firmware info request opcode appears in G1OT constants.)  [oai_citation:7‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)

───────────────────────────────────────────────
SECTION 12 – NOTIFICATIONS / WHITELIST (0x04, JSON)
───────────────────────────────────────────────
• JSON payload chunked to ≤~180 B; include chunk index.
• Expect continue acks (0xCB) then final 0xC9.
• On failure, abort burst and log one concise `[DIAG] Notification send failed`.

───────────────────────────────────────────────
SECTION 13 – MESSAGE EVENTS (0xF5)
───────────────────────────────────────────────
• Dispatch table:
  - 06–0B → wearing/case transitions (mirror to STATE layer)
  - 1E–1F → dashboard open/close (UI hint)
  - 20 → double-tap translate/transcribe (diagnostic only for now)
• Mirror into GestureTelemetry flow; debounce 250–300 ms.

───────────────────────────────────────────────
SECTION 14 – CONSOLE FORMAT (reference)
───────────────────────────────────────────────
• Use v1.1 Section 4 schema: `[TAG][SIDE] Message @ HH:mm:ss`
  - TAGs: `[STATE] [VITALS] [GESTURE] [PROMPT] [DIAG]`
• Keep lines < 80 chars; throttle to ≤3/s.
(Teleprompter pacing and delays remain aligned with Open Teleprompter.)  [oai_citation:8‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)  [oai_citation:9‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)
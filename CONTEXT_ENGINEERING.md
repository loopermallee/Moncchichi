# CONTEXT_ENGINEERING.md v1.3
Extends v1.2. Purpose: make the app aware of updated protocol surfaces and able to use them immediately.

────────────────────────────────────────
§A. VERIFIED FROM G1 OPEN TELEPROMPTER
────────────────────────────────────────
BLE (Nordic UART Service):
• Service: 6e400001-b5a3-f393-e0a9-e50e24dcca9e
• Write (TX): 6e400002-b5a3-f393-e0a9-e50e24dcca9e
• Notify (RX): 6e400003-b5a3-f393-e0a9-e50e24dcca9e   [oai_citation:14‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)  [oai_citation:15‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)
• MTU: 247 (project config).   [oai_citation:16‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)

Opcodes present:
• BATTERY: 0x2C
• UPTIME: 0x37
• FIRMWARE REQUEST: [0x23, 0x74]   [oai_citation:17‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)

Teleprompter framing (carried over for pacing):
• Control array size = 10; header size = 2; total len ≤ 255; per-packet delay used in repo.   [oai_citation:18‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)  [oai_citation:19‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)  [oai_citation:20‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)

────────────────────────────────────────
§B. ASSUMPTION FLAGS (until G1 PDF is shared)
────────────────────────────────────────
• 0x0E Mic toggle: treat 0xC9 as success ACK; persist setting; single [DIAG] line on change.
• 0xF1 Audio stream: seq 0–255 wrap; circular buffer; track rx rate + gaps; never block UI; log compact stats.
• 0x06 Dashboard + sub 0x07–0x1C: encoder chunk target ≈189 B; sequence tags; queued writes with short inter-packet delay.
• 0x26 len==6 → voice-on-lift toggle; len==8 → display depth/height.
• 0x04 JSON whitelist: chunks ≤~180 B; expect 0xCB “continue”, final 0xC9 OK.

If any assumption conflicts with your protocol doc, adjust values in ProtocolMap.kt without refactoring core repositories.

────────────────────────────────────────
§C. STATE & POWER (authoritative mapping)
────────────────────────────────────────
• 0x2B state codes:
  06 worn, 07 not worn, 08 case open, 0A case closed, 0B case closed+plugged (emit [STATE] + reason).
• 0x2C sub 0x01:
  byte2=battery %, byte3=voltage/charge state (validate 0–100). BATTERY frames authoritative; ignore quick ping if last good ≤10 s (per v1.1).

────────────────────────────────────────
§D. SYSTEM COMMANDS
────────────────────────────────────────
• 0x10 reset 0° → expect 0xC9.
• 0x23 0x72 reboot → no response expected.
• 0x23 0x74 firmware info → ASCII (version/build/DeviceID) → parsed to fields on Developer screen.   [oai_citation:21‡elvisoliveira-g1-open-teleprompter-8a5edab282632443.txt](file-service://file-6TXkygHWbigjNPahJBtr1M)

────────────────────────────────────────
§E. GESTURE / MESSAGE EVENTS
────────────────────────────────────────
• 0xF5 dispatch table:
  06–0B wearing/case, 1E–1F dashboard open/close, 20 double-tap translate/transcribe.
• Mirror to GestureTelemetry (diagnostic), debounce 250–300 ms.

────────────────────────────────────────
§F. CONSOLE FORMAT (reference)
────────────────────────────────────────
Reuse v1.1 §4: “[TAG][L/R] Message @ HH:mm:ss”; Tags = [STATE] [VITALS] [GESTURE] [PROMPT] [DIAG]; throttle ≤3/s.
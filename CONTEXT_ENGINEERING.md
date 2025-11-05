# CONTEXT_ENGINEERING.md v1.1
Baseline architecture for Moncchichi Hub dual-lens telemetry system.

───────────────────────────────────────────────
SECTION 1 – ARCHITECTURE OVERVIEW
───────────────────────────────────────────────
• Two independent G1BleClient instances (L / R) → merged into SharedFlow<IncomingFrame>.  
• BleTelemetryRepository parses frames by opcode and lens; produces domain models → LensStatus.  
• HubViewModel collects flows for UI panels and Developer Console.  
• State change and battery frames handled per lens to avoid cross-contamination.  
• All events tagged `[L]` or `[R]` for clarity.

───────────────────────────────────────────────
SECTION 2 – STATE DECODE RULES
───────────────────────────────────────────────
Bit map (from protocol doc):  
- 0x01 → in case  
- 0x02 → wearing  
- 0x04 → silent mode  
- 0x08 → case open (optional bit)  

Rules:  
- Update a field only when its bit changes.  
- Emit `StateChangedEvent(lens, reason)` to SharedFlow.  
- Resolution priority: in case > wearing > silent flag.  
- UI headline summarizes lens pair state deterministically.  

───────────────────────────────────────────────
SECTION 3 – BATTERY & ACK POLICY
───────────────────────────────────────────────
Battery:  
- `BATTERY_OPCODE` = authoritative source.  
- Ignore quick status if a good battery value ≤ 10 s old.  
- Mark as “Out of date” if > 30 s old.  
- Each LensStatus holds `lastPowerOpcode`, `lastPowerTimestamp`.  

ACK Hygiene:  
- Min interval 1 s between keep-alive writes per lens.  
- Treat textual “OK” or “NA” as soft ACK during warm-up.  
- Reset failure streak on first valid binary ACK (`0xC9`, `0x04`).  
- Count failures only for missing ACKs beyond timeout.  

───────────────────────────────────────────────
SECTION 4 – CONSOLE TELEMETRY TAGS & FORMAT RULES
───────────────────────────────────────────────
Standard developer log pattern:  
`[TAG][SIDE] Message @ HH:mm:ss`

Tags:  
- `[STATE]`    – wearing/case/silent updates  
- `[VITALS]`  – battery or uptime frames  
- `[GESTURE]` – TouchBar telemetry (Later phase)  
- `[PROMPT]`  – Teleprompter HUD updates  
- `[DIAG]`    – debug and ack diagnostics  

Console example:  
`[STATE][L] Now wearing @ 10:48:12`  
`[VITALS][R] Battery 92 % (case closed) @ 10:49:33`  
`[DIAG] ACK OK (L) resetting fail count @ 10:49:45`

All timestamps HH:mm:ss (local).  
Console lines are short (< 80 chars) and throttled ≤ 3/s.

───────────────────────────────────────────────
SECTION 5 – EXPECTED RUNTIME BEHAVIOUR
───────────────────────────────────────────────
• Dual-lens status updates independent and synchronized to UI.  
• No false “wearing” when device in case.  
• Battery fields stable with timestamp aging.  
• Keep-alive stream rate ≤ 1 Hz per lens.  
• Console shows ACK resets and de-bounced state changes.  
• UI always displays placeholder or latest known value with timestamp.
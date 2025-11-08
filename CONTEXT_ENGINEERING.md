# Moncchichi Hub Context Engineering v4.5
────────────────────────────────────────────
PHASE P1 – BLE Stability Core (Watchdog + Dual ACK)

Goal:
Stabilize BLE communication and mic stream availability to create a reliable foundation for HUD and teleprompter work.

────────────────────────────────────────────
1. ACK Handling Update
• Older firmware (< v1.5.0) returns binary ACK bytes `0xC9 04`.  
• Newer firmware (≥ v1.5.6) sends textual “OK” or no payload.  
• Repository now accepts both formats and logs type.  
  → Eliminates false `→ PING ← ERR` in Developer Console.  
Source: Even Realities G1 BLE Protocol + EvenDemoApp logs (2024–2025).

────────────────────────────────────────────
2. Mic Stream Watchdog
• `BleTelemetryRepository.handleAudioPacket()` records last 0xF1 frame timestamp.  
• New coroutine monitors for silence > 500 ms; sets `_micAvailability = false`.  
• Resets on frame receipt or unbind().  
• Exposed as Flow<Boolean> to MicStreamManager.  
Source: Codex review (2025-11-08).

────────────────────────────────────────────
3. Mic Source Auto-Fallback
• MicStreamManager subscribes to micAvailability.  
• If false for > 1 s → fallback priority order:  
    GLASSES → WEARABLE → PHONE.  
• Restores preferred MicSource after BLE stable ≥ 2 s.  
• All capture lifecycle bound to HubBleService.coroutineScope.  
• Switch latency ≤ 200 ms.  
Source: Context Engineering Addendum v4.4a §2 and Codex notes.

────────────────────────────────────────────
4. Telemetry Stability Notes
• Ping interval ≥ 1 s per lens to avoid ACK storm.  
• Warm-up text responses are soft ACKs; don’t raise fail count.  
• Legacy parsing path unchanged for backward compatibility.  
• No UI impact expected.

────────────────────────────────────────────
5. Verification Checklist
✅ PING command logs `[ACK][L/R] OK (type=…)`.  
✅ BLE audio loss triggers fallback to Wearable within 1 s.  
✅ BLE reconnect restores Glasses mic in < 2 s.  
✅ No duplicate collectors in SettingsRepository flows.  
✅ No SecurityException when permissions missing.  

────────────────────────────────────────────
6. Next Phase Preview
After P1 stabilizes, move to Phase P2 (HUD Text & Clear Control).  
P2 will add GuiCommandHelper.kt and command queue for 0x4E/0x18.

────────────────────────────────────────────
Citations:
• Even Realities G1 BLE Protocol docs (2024).  
• EvenDemoApp log samples (Dashboard and Battery ACK traces).  
• Codex patch review and feedback (2025-11-08).  
• Moncchichi Hub Context Engineering v4.4a Addendum.  
• G1Sample Swift code (battery frame and 0xF1 audio handling).  
────────────────────────────────────────────
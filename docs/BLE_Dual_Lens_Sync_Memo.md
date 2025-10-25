# Moncchichi Hub — Dual-Lens Synchronization & Diagnostic Reality Memo  
**Phase 4.0 r2** • *Dated 2025-10-25*  

---

## **Summary**

This diagnostic confirms that both Even G1 lenses (Left = CE0CDF, Right = 1D7162) operate under an identical BLE architecture once Bluetooth bonding is complete.  
Each exposes a Nordic UART Service and begins emitting binary telemetry and ASCII firmware banners (`ver 1.6.5, JBD DeviceID 4010`).  
The Moncchichi Hub dual-client architecture already supports this pattern; this memo defines how synchronization and runtime coordination occur.

---

## **Observed Reality**

| Layer | Finding | Evidence |
|-------|----------|----------|
| **Bonding Gate** | UART silent until device is bonded. | Both lenses emitted data only after `BOND_STATE_CHANGED → BONDED (12)` |
| **Firmware Parity** | Both lenses share build 2025-09-16 (ver 1.6.5). | Identical ASCII banners |
| **ACK Format** | Binary ACK `0x04 CA / 0xC9 04` + text `OK`. | Left-lens trace |
| **Keep-Alive Window** | Disconnect ≈ 30 s idle → needs PING. | Both logs end status 19 |
| **MTU / PHY** | MTU 498 @ LE 1 M stable. | Negotiation confirmed |
| **Telemetry Opcodes** | `F5`, `2C`, `37`, `39`, `39 05`. | Frame match both lenses |
| **RSSI Range** | −50 → −37 dBm stable. | No RF dropouts |

---

## **Dual-Lens Synchronization Workflow**

### **1️⃣ Pair & Bond Bootstrap**
Each `G1BleClient` connects one lens.  
After GATT connect:  
```kotlin
if (!device.isBonded) device.createBond()

UART notifications are enabled only after the system reports BONDED (12).
Both clients thus enter a synchronized bond-ready state before aggregation.

⸻

2️⃣ UART Initialization Warm-Up

After bonding:
	•	Write "ver\n" to RX (Write No Response).
	•	Device replies with multi-fragment banner (ver 1.6.5 … DeviceID 4010).
	•	BleTelemetryRepository.maybeEmitUtf8() reconstructs and tags by lensId (L/R).

⸻

3️⃣ Telemetry Merge Pipeline

merge(left.incomingFrames, right.incomingFrames)
    .onEach { frame -> repository.ingest(frame, lensTag) }

BleTelemetryRepository keeps per-lens buffers to preserve frame order.
Binary opcodes → LensStatus; UTF-8 text → FirmwareVersion, DeviceID, BuildTime.

⸻

4️⃣ ACK Detection Logic

if (bytes.containsAll(byteArrayOf(0xC9.toByte(),0x04)) ||
    bytes.containsAll(byteArrayOf(0x04.toByte(),0xCA.toByte())) ||
    text.trim().equals("OK", true)) markAck()

Fixes previous false “→ PING ← ERR” symptom by accepting both binary & text ACKs.

⸻

5️⃣ Keep-Alive Scheduler

while (connected) {
    sendCommand(PING)
    delay(10_000)
}

Maintains active link; updates RSSI & ACK timestamps in LensStatus.

⸻

6️⃣ Snapshot Fusion & State Cache

{
  "timestamp": "2025-10-25T19:05:00Z",
  "left":  { "fw": "1.6.5", "rssi": -44, "ack": true },
  "right": { "fw": "1.6.5", "rssi": -48, "ack": true }
}

HubViewModel observes snapshot for unified dual-lens dashboard display.

⸻

Confirmed Firmware Handshake Flow

connectGatt()
→ discoverServices()
→ enable TX notify
→ bond established
→ send "ver\n"
→ receive banner
→ ACK 0x04 CA
→ telemetry burst (F5 / 2C / 37 / 39)
→ RSSI updates → idle timeout (30 s)


⸻

Implementation Directives for Codex
	1.	Preserve existing architecture (G1BleUartClient, BleTelemetryRepository).
	2.	Add auto-bond flow using createBond() and monitor ACTION_BOND_STATE_CHANGED.
	3.	Implement dual-mode ACK detection (per Section 4️⃣).
	4.	Integrate keep-alive loop to avoid idle status 19 disconnects.
	5.	Log firmware banner once per bond session to prevent duplicates.
	6.	Cache dual snapshots in MemoryRepository for diagnostic replay.

⸻

Outcome

✅ Both lenses synchronize after bonding.
✅ “→ PING ← ERR” resolved via binary ACK support.
✅ Architecture remains intact – runtime optimizations only.
✅ Firmware 1.6.5 confirmed symmetrical operation.

⸻

Next Phase (4.1 – Runtime Optimization)
	1.	Persist bond keys across sessions.
	2.	Tune RSSI back-off intervals.
	3.	Add optional UART packet-dump for deep debug.
	4.	Expose firmware version & DeviceID in Hub UI.

⸻

End of Memo
(Validated against nRF Connect traces for CE0CDF & 1D7162 on 25 Oct 2025.)

---
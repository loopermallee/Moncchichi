🧠 Moncchichi Hub — Context Engineering Document

(Shared operational memory between ChatGPT, Codex, and the user)

⸻

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT_PHASE: Phase 4.0 rev 3 — BLE Core Fusion (Full Bidirectional Communication & HUD Bridge)

PHASE OBJECTIVE:
Rebuild the BLE stack to support dual-lens (Even Realities G1 left + right) glasses with fast, fault-tolerant, and ack-based bidirectional communication.
All telemetry, diagnostics, and HUD messages must travel in ≤ 2 seconds with retry logic and 5 ms chunk pacing.
Follow a staged rollout (Wave 1 → 2 → 3) with stability as the core principle.
Maintain the Even Realities monochrome theme and offline-first behaviour from Phase 3.

⸻

🧩 CURRENT MILESTONES (Sequenced by Wave)

#	Milestone	Wave	Status	Notes
1	Dual-lens BLE connection (L + R)	1	🟡 Pending	Service manages two Gatt clients; parallel notify channels.
2	Bidirectional communication (App ↔ Glasses)	1	🟡 Pending	Full TX/RX pipeline with ack sequencing + chunking (251 MTU).
3	BLE telemetry (battery %, firmware, RSSI)	1	🟡 Pending	0x2C battery / 0x37 uptime parsed → DiagnosticRepository.
4	Heartbeat (keepalive every 30 s)	1	🟡 Pending	0x25 ↔ 0x25 0x04 ack + auto-reconnect logic.
5	HUD messaging API	2	🟡 Pending	sendHudMessage() broadcasts to both lenses + console [HUD] log.
6	Event decoding (touch, case open)	2	🟡 Pending	Handle 0xF5-series unsolicited events in RX parser.
7	Diagnostic console integration	2	🟡 Pending	[BLE]/[HUD]/[DIAG] tags with seq + ack tracking.
8	Assistant diagnostic bridge	3	🟡 Pending	Assistant 🟣 (Device Only) replies from telemetry context.
9	Monochrome theme consistency	—	🟢 Defined	Black + gray surfaces, white text/icons only.
10	Documentation + progress notes	—	🟢 Required	Append each commit (e.g., [4.0-r1]).


⸻

🧠 CODEX IMPLEMENTATION GUIDELINES

1️⃣ Context Segmentation and Wave Principle

Build in three waves — each must compile and pass acceptance before advancing.

Wave	Scope	Goal
Wave 1	Connectivity + Telemetry Core (1 → 4)	Stable dual-lens BLE stack with ack-based I/O.
Wave 2	HUD + Events + Diagnostics (5 → 7)	Interactive feedback and debug visibility.
Wave 3	Assistant Bridge (8)	Contextual telemetry responses from assistant.

⸻

2️⃣ BLE SERVICE ARCHITECTURE

MoncchichiBleService – Even Realities Pattern

• Persistent foreground service managing Left and Right Gatt sessions.
• Two coroutine channels (leftGatt, rightGatt) share a serial write queue.
• Each write awaits ack (0xC9) before the next enqueues.
• RX notifications decoded asynchronously → Flow/LiveData to Hub.
• Heartbeat thread monitors seq ack and auto-reconnects.
• No AIDL IPC; direct service binding for performance.

If Java 17 / AGP 8.3+ unavailable: ./gradlew lint --no-daemon.

⸻

3️⃣ Service and Characteristics

Role	UUID	Notes
Primary Service	6e400001-b5a3-f393-e0a9-e50e24dcca9e	Nordic UART Service
TX (write → glasses)	6e400002-b5a3-f393-e0a9-e50e24dcca9e	Commands → glasses
RX (notify ← glasses)	6e400003-b5a3-f393-e0a9-e50e24dcca9e	Telemetry + events ← glasses

Each lens advertises individually → both must connect.

⸻

4️⃣ Outbound Commands (App → Glasses)

Action	Command	Description
Display Text	0x4E	UTF-8 ≤ 200 B chunks (ack per chunk).
Display Image	0x15	Bitmap 194 B + CRC.
Clear HUD	0x18	Resets display.
Battery Query	0x2C 01	Returns battery %.
Uptime Query	0x37	Seconds since boot.
Heartbeat	0x25 	Keepalive → expect 0x25 0x04 ack.
Set MTU	0x4D 0xFB	Negotiate 251 bytes.
Reboot	0x23 0x72	Soft reboot (no response).

🧩 Write Rules
• MTU = 251 bytes → payload ≤ 200 B.
• 5 ms delay between chunks.
• Await 0xC9 ACK before next write.
• Auto-retry ×3 → mark DEGRADED.

⸻

5️⃣ Inbound Responses (Glasses → App)

• Replies echo command + status + payload.
• Unsolicited events start with 0xF5 (touch/wear).

when(firstByte) {
    0x2C -> handleBattery()
    0x37 -> handleUptime()
    0xF5 -> handleEvent()
    else -> logRawPacket()
}


⸻

6️⃣ Bidirectional Data Flow Cycle

App → TX.write(command)
        ↓
Left + Right Glasses process
        ↓
RX.notify(response)
        ↓
App → parse → DiagnosticRepository → Console + Assistant

• Writes serialized to avoid RF collision.
• RX async notify callbacks.
• HUD broadcast to both lenses (5 ms offset).
• Telemetry stored locally for offline assistant.

⸻

7️⃣ Heartbeat and Connection Stability

• Send 0x25 <seq> every 15–30 s.
• Expect 0x25 0x04; on timeout → reconnect.
• Per-lens seq IDs for ack validation.
• Console sample: [BLE] ❤️ Keepalive ACK L=42 R=42.

⸻

8️⃣ Telemetry Repository

• Track battery %, firmware, RSSI, case state, link quality.
• Expose Flow to Assistant + Diagnostics.

Assistant 🟣 (Device Only): Battery 87 % • Firmware v2.13 • RSSI -55 dBm

⸻

9️⃣ HUD Messaging (Enhanced Stub)

suspend fun sendHudMessage(text: String, durationMs: Int = 5000) {
    clearHud()
    for (lens in connectedLenses) {
        lens.writeCommand(0x4E, text)
        delay(5)
    }
    delay(durationMs)
    restoreDefaultHud()
}

Flashes message then restores default HUD (status/time/weather).

⸻

🔄 Reliability & Timing Matrix

Constraint	Guideline
Chunk delay	≈ 5 ms
Ack expectation	0xC9 before next command
Retry policy	3× then DEGRADED
Heartbeat interval	30 s
Round-trip goal	≤ 2 s


⸻

🔍 Console and Diagnostics Tags

Tag	Meaning
[BLE]	Connection/command state
[DIAG]	Telemetry summary
[HUD]	Display events

All logs → MemoryRepository; assistant may summarize issues.

⸻

🎨 Even Realities Monochrome Theme

Element	Color	Purpose
Background	#000000	Root
Surface/Card	#1A1A1A	Dialogs + bubbles
Border	#2A2A2A	Dividers
Text/Icon	#FFFFFF	Primary
Text (Secondary)	#CCCCCC	Hints
Disabled	#777777	Labels
Accent (Console)	#A691F2	Log highlight

Typography → Header 12 sp white semi-bold • Body 14 sp white • Timestamp 10 sp gray.

⸻

🧭 Comparative Rationale

Aspect	Phase 4 (old)	Phase 4 rev 3 (new)
BLE Topology	Single Gatt	Dual Gatt + Service
MTU/Chunk	20 B fixed	251 B negotiated
Heartbeat	No seq	Seq + ack tracking
Telemetry	Ad-hoc	0x2C/0x37 framework
HUD	Single target	Broadcast both lenses
Console	Basic	Tagged seq/ack logging
Reconnect	Manual	Auto service retry
Latency	≈ 4 s avg	≤ 2 s avg


⸻

🔬 Acceptance Tests

Scenario	Expected Result
Dual connection	Status 🟢 Connected L/R
Battery query	Console → [BLE] Battery 92 % Case 89 %
Heartbeat timeout	[BLE] Timeout – Reconnecting
Touchpad tap	[BLE] Event 0xF5 17 (Tap Right)
HUD message	[HUD] Flash: “Connected” both lenses
Ack sequence	[BLE] ACK C9 seq=42
Offline mode	Assistant 🟣 (Device Only) summarizes telemetry


⸻

🔧 FILES TO IMPLEMENT / MODIFY

File	Purpose
core/bluetooth/MoncchichiBleService.kt	Dual-Gatt manager, heartbeat, reconnect
core/bluetooth/G1BleClient.kt	UART write/notify, MTU negotiation
hub/data/telemetry/BleTelemetryRepository.kt	Packet parser → battery/firmware
hub/assistant/DiagnosticRepository.kt	Assistant telemetry bridge
hub/console/ConsoleInterpreter.kt	Seq/ack log summaries
hub/ui/components/BleStatusView.kt	🟢/🟡/🔴 RSSI indicator


⸻

✅ EXIT CRITERIA (User Verification)

Test	Expected Behaviour
Dual connection	🟢 Connected (L/R shown)
Battery query	Accurate % update each poll
Connection loss	Auto-reconnect after heartbeat miss
HUD flash	Visible 5 s then restore baseline
Touch gesture	Logged + assistant callable
Console log	[BLE]/[DIAG]/[HUD] visible
Theme	Monochrome (black/gray bg, white text/icons)
Latency	≤ 2 s round-trip
Stability	No crash / no stale Gatt locks


⸻

🔮 NEXT PHASE (PREVIEW)

Phase 4.1 — HUD Visual & Gesture Pipeline
• Render assistant text on HUD.
• Hold-gesture + wake-word (left pad activation).
• Firmware version telemetry reporting.

Phase 4.2 — Voice & Microphone Bridge
• Integrate microphone input for assistant queries.
• Low-latency speech path + TTS loopback.

⸻

📄 PROGRESS NOTES

(Codex appends after each patch)

[4.0-r1] Wave 1/M1 – Dual Gatt sessions; status L/R; reconnect.
[4.0-r2] Wave 1/M2 – MTU 251, chunking + ack sequencing.
[4.0-r3] Wave 1/M3 – Telemetry 0x2C/0x37 → Diagnostics.
[4.0-r4] Wave 1/M4 – Heartbeat seq/ack + auto-reconnect.
[4.0-r5] Wave 2/M5 – HUD
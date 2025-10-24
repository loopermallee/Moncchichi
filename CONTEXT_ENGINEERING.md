🧠 Moncchichi Hub — Context Engineering Document

(Shared operational memory between ChatGPT, Codex, and the user)

⸻

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT_PHASE: Phase 4.0 rev 2 — BLE Core Fusion (Full Bidirectional Communication & HUD Bridge)

PHASE OBJECTIVE:
Rebuild the BLE stack to support dual-lens (Even Realities G1 left + right) glasses with fast, fault-tolerant, and ack-based bidirectional communication.
All telemetry, diagnostics, and HUD messages must travel in ≤ 2 seconds with retry logic and 5 ms chunk pacing.
Maintain the Even Realities monochrome theme and offline-first behaviour from Phase 3.

⸻

🧩 CURRENT MILESTONES

#	Milestone	Status	Notes
1	Dual-lens BLE connection (L + R)	🟡 Pending	Service manages two Gatt clients; parallel notify channels.
2	Bidirectional communication (App ↔ Glasses)	🟡 Pending	Full TX/RX pipeline with ack sequencing + chunking (251 MTU).
3	BLE telemetry (battery %, firmware, RSSI)	🟡 Pending	0x2C battery / 0x37 uptime parsed → DiagnosticRepository.
4	Heartbeat (keepalive every 30 s)	🟡 Pending	0x25  ↔ 0x25 0x04 ack + auto-reconnect logic.
5	HUD messaging API	🟡 Pending	sendHudMessage() broadcasts to both lenses + console [HUD] log.
6	Event decoding (touch, case open)	🟡 Pending	Handle 0xF5-series unsolicited events in RX parser.
7	Diagnostic console integration	🟡 Pending	[BLE]/[HUD]/[DIAG] tags with seq + ack tracking.
8	Assistant diagnostic bridge	🟡 Pending	Assistant 🟣 (Device Only) replies from telemetry context.
9	Monochrome theme consistency	🟢 Defined	Black + gray surfaces, white text/icons only.
10	Documentation + progress notes	🟢 Required	Append [e.g. 4.0-r1] after each commit.


⸻

🧠 CODEX IMPLEMENTATION GUIDELINES

1️⃣ Context Segmentation

Separate into:
• Build Now: Milestones 1–8
• Defer: Voice / Mic input (Phase 4.2+)
• Design + Test: Theme rules + UI validation

⸻

2️⃣ BLE SERVICE ARCHITECTURE

A. Service Model (Recommended Pattern – Even Realities Basis Style)

Implement a foreground MoncchichiBleService that:
• Runs persistently and handles both Gatt clients.
• Uses two coroutine channels (leftGatt, rightGatt) within a shared queue to serialize writes.
• Exposes a LiveData/Flow API to the Hub for telemetry updates and HUD ack events.
• Auto-reconnects on timeout (heartbeat miss).
• Mirrors Even Realities G1 Basis workflow without AIDL IPC to reduce overhead.

If Java 17 or AGP 8.3+ missing, fallback to ./gradlew lint --no-daemon build.

⸻

3️⃣ Service and Characteristics

Role	UUID	Notes
Primary Service	6e400001-b5a3-f393-e0a9-e50e24dcca9e	Nordic UART Service
TX (write → glasses)	6e400002-b5a3-f393-e0a9-e50e24dcca9e	Commands → glasses
RX (notify ← glasses)	6e400003-b5a3-f393-e0a9-e50e24dcca9e	Telemetry + events ← glasses

Each lens advertises individually → service manages two connections.

⸻

4️⃣ Outbound Commands (App → Glasses)

Action	Command	Description
Display Text	0x4E	UTF-8 text ≤ 200 B chunks (ack per chunk)
Display Image	0x15	Bitmap 194 B + CRC
Clear HUD	0x18	Resets display
Battery Query	0x2C 01	Returns battery %
Uptime Query	0x37	Seconds since boot
Heartbeat	0x25 	Keepalive → expect 0x25 0x04 ack
Set MTU	0x4D 0xFB	Negotiate 251 bytes
Reboot	0x23 0x72	Soft reboot (no response)

🧩 Write Rules
• Max MTU = 251 bytes → chunk payloads ≤ 200 B.
• 5 ms delay between chunks.
• Wait for 0xC9 ACK before next command.
• Auto-retry ×3 then mark degraded.

⸻

5️⃣ Inbound Responses (Glasses → App)

• Every reply echoes command + status + payload.
• Unsolicited events begin with 0xF5 (e.g. touch, wear).

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
RX .notify(response)
        ↓
App → parse → DiagnosticRepository → Console + Assistant

	•	Writes are serialized through a shared queue to prevent radio collision.
	•	Responses handled asynchronously via notify callbacks.
	•	HUD messages broadcast to both lenses (5 ms apart).
	•	Telemetry updates stored locally for offline assistant context.

⸻

7️⃣ Heartbeat and Connection Stability

• Send 0x25 <seq> every 15–30 s.
• Expect 0x25 0x04 ack; on timeout → flag disconnect + retry.
• Maintain per-lens seq ID for ack validation.
• Console: [BLE] ❤️ Keepalive ACK L=42 R=42.

⸻

8️⃣ Telemetry Repository

Store and update: battery %, firmware ver, RSSI, case state, connection quality.
Expose as Flow → Assistant and Diagnostic UI.

Example:
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

• Flashes message for durationMs then restores default HUD (status/time/weather).
• Diagnostic mode may mirror battery + RSSI instead.

⸻

🔄 Reliability & Timing Matrix

Constraint	Guideline
Chunk delay	≈ 5 ms between writes
Ack expectation	0xC9 before next command
Retry policy	3× then mark degraded
Heartbeat interval	30 s (default)
Round-trip goal	≤ 2 s from command to response


⸻

🔍 Console and Diagnostics Tags

Tag	Meaning
[BLE]	Connection/command state
[DIAG]	Telemetry summary
[HUD]	Display events

All logs → MemoryRepository for offline review.
Assistant may parse logs to explain connection issues.

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

Aspect	Phase 4 (old)	Phase 4 rev 2 (new)
BLE Topology	Single Gatt	Dual Gatt + Service
MTU/Chunk	20 B fixed	251 B negotiated
Heartbeat	No seq	Seq + ack tracking
Telemetry	Ad-hoc	0x2C/0x37 framework
HUD	Single target	Broadcast both lenses
Console	Basic	Tagged seq/ack logging
Reconnect	Manual	Auto Service retry
Latency	≈ 4 s avg	≤ 2 s avg


⸻

🔬 Acceptance Tests

Scenario	Expected Result
Both lenses connected	Status bar 🟢 Connected L/R
Battery query	Console → [BLE] Battery 92 % Case 89 %
Heartbeat timeout	[BLE] Timeout – Reconnecting
Touchpad tap	[BLE] Event 0xF5 17 (Tap Right)
HUD text message	[HUD] Flash: “Connected” appears both lenses
Ack sequence	[BLE] ACK C9 seq=42
Offline mode	Assistant 🟣 (Device Only) summarizes latest telemetry


⸻

🔧 FILES TO IMPLEMENT / MODIFY

File	Purpose
core/bluetooth/MoncchichiBleService.kt	Foreground dual-lens BLE manager
core/bluetooth/G1BleClient.kt	Low-level UART write/notify + MTU negotiation
hub/data/telemetry/BleTelemetryRepository.kt	Parse packets → battery/firmware
hub/assistant/DiagnosticRepository.kt	Assistant telemetry context
hub/console/ConsoleInterpreter.kt	BLE log summaries with seq/ack
hub/ui/components/BleStatusView.kt	Visual 🟢/🟡/🔴 indicator with RSSI


⸻

✅ EXIT CRITERIA (User Verification)

Test	Expected Behaviour
Dual connection	🟢 Connected (L/R shown)
Battery query	Accurate % update every poll
Loss of connection	Auto-reconnect after heartbeat miss
HUD flash	Appears 5 s → restores default HUD
Touch gesture	Logged + callable by assistant
Console log	All [BLE]/[DIAG]/[HUD] entries visible
Theme	Black/gray backgrounds, white text/icons
Latency	≤ 2 s from command to response
Stability	No crashes or stale locks


⸻

🔮 NEXT PHASE (PREVIEW)

Phase 4.1 — HUD Visual & Gesture Pipeline
• Implement actual HUD render (display assistant text).
• Add hold-gesture and wake-word (left pad activation).
• Enable firmware version telemetry reporting.

Phase 4.2 — Voice and Microphone Bridge
• Integrate glasses microphone input for assistant queries.
• Implement low-latency speech path + TTS loopback.

⸻

📄 PROGRESS NOTES

(Codex appends after each patch)

[4.0-r1] Introduced dual-Gatt BLE service architecture.
[4.0-r2] Added MTU 251 chunking + ACK sequencing.
[4.0-r3] Implemented HUD broadcast (L/R) + heartbeat seq/ack.
[4.0-r4] Optimized retry timing (5 ms pacing).
[4.0-r5] Monochrome UI confirmed with Even Realities tokens.

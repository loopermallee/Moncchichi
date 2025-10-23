🧠 Moncchichi Hub — Context Engineering Document

(Shared operational memory between ChatGPT, Codex, and the user)

⸻

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT_PHASE: Phase 4.0 — BLE Core Fusion (Bidirectional Communication & HUD Bridge)
PHASE_OBJECTIVE:
Integrate stable, bidirectional BLE telemetry between the Even Realities G1 glasses and the Moncchichi Hub app.
Ensure real-time synchronization of device telemetry, HUD messages, diagnostics, and assistant context while maintaining the Even Realities monochrome theme and offline-first reliability established in Phase 3.

⸻

🧩 CURRENT MILESTONES

#	Milestone	Status	Notes
1	BLE connection and scanning for L/R glasses	🟡 Pending	Connect to both sides using Nordic UART service
2	Bidirectional communication (App ↔ Glasses)	🟡 Pending	Full TX (write) + RX (notify) channels functional
3	BLE telemetry (battery %, firmware, RSSI)	🟡 Pending	Data parsed into DiagnosticRepository
4	Heartbeat system (keepalive every 30 s)	🟡 Pending	Maintain connection and auto-reconnect
5	HUD messaging API stub	🟡 Pending	sendHudMessage() with console feedback
6	Event decoding (touch, case open)	🟡 Pending	Handle 0xF5-series unsolicited events
7	Diagnostic console integration	🟡 Pending	BLE logs prefixed with [BLE]/[HUD]/[DIAG]
8	Assistant diagnostic link	🟡 Pending	“Assistant 🟣 (Device Only)” responses
9	Monochrome theme consistency	🟢 Defined	Black + gray surfaces; white text/icons only
10	Phase 4 documentation / progress notes	🟢 Required	Append after each commit (e.g. [4.0-r1])


⸻

🧠 CODEX IMPLEMENTATION GUIDELINES

1️⃣ Context Segmentation

Before coding, separate this document into:
	•	Build Now: Active milestones (1–8)
	•	Defer: Voice and HUD mic input (Phase 4.2 +)
	•	Design/Test: Theme rules + UI validation.

2️⃣ Ambiguity Handling

If multiple modules reference BLE (e.g., core, hub, subtitles):
	•	Search for residual Bluetooth classes.
	•	Remove duplicates or mark //TODO Phase 4.1.

3️⃣ Even Realities Monochrome Theme

Element	Color	Purpose
Background	#000000	Root background
Surface / Card / Panel	#1A1A1A	Dialogs and bubbles
Border / Divider	#2A2A2A	Separators
Text / Icons (Primary)	#FFFFFF	Readable content
Text (Secondary)	#CCCCCC	Hints, timestamps
Disabled Text	#777777	Non-interactive labels
Accent (Console only)	#A691F2	Log highlight color

Typography — Header 12 sp white semi-bold · Body 14 sp white · Timestamp 10 sp gray.

⸻

🧩 BLE Telemetry and Bidirectional Communication Framework

Overview

Even Realities G1 glasses use Bluetooth Low Energy (BLE) via a Nordic UART-style service.
Two devices (L/R lenses) must be connected simultaneously.
This framework enables:
	•	Receiving live telemetry (battery, case, firmware, RSSI)
	•	Sending HUD messages (text or image)
	•	Maintaining heartbeat and event notifications
	•	Logging bidirectional data for diagnostics and assistant context

⸻

1️⃣ Service and Characteristics

Role	UUID	Notes
Primary Service	6e400001-b5a3-f393-e0a9-e50e24dcca9e	Nordic UART base service
TX (Write → Glasses)	6e400002-b5a3-f393-e0a9-e50e24dcca9e	Commands to glasses
RX (Notify ← Glasses)	6e400003-b5a3-f393-e0a9-e50e24dcca9e	Events + responses from glasses

Both sides (left/right) advertise individually and must be connected.

⸻

2️⃣ Outbound Commands (App → Glasses)

Action	Command	Description
Display Text	0x4E	UTF-8 text, ≤ 200 B chunks
Display Image	0x15	Bitmap 194 B chunks + CRC
Clear Screen	0x18	Resets HUD
Battery Query	0x2C 01	Returns battery %
Uptime Query	0x37	Seconds since boot
Heartbeat	0x25 <seq>	Keepalive
Set MTU	0x4D 0xFB	MTU = 251 bytes
Reboot	0x23 0x72	Soft reboot (no response)

Status bytes: 0xC9 = OK, 0xCA = Fail, 0xCB = Continue.

⸻

3️⃣ Inbound Responses and Events (Glasses → App)
	•	Responses echo command + status + payload.
	•	Example: Battery → [0x2C, 0xC9, 0x5A] → 90 %.
	•	Unsolicited events use 0xF5 prefix (e.g., touch or wear event).

when(firstByte) {
  0x2C -> handleBattery()
  0x37 -> handleUptime()
  0xF5 -> handleEvent()
  else -> logRawPacket()
}


⸻

4️⃣ Data Flow Cycle

App → TX.write(command)
        ↓
   Glasses process → RX.notify(response)
        ↓
App parses → updates console & diagnostics

	•	Writes are sequential with 5 ms delay between chunks.
	•	Send left then right to avoid radio collision.
	•	Handle responses asynchronously on notify callback.

⸻

5️⃣ Heartbeat and Connection Stability
	•	Send 0x25 <seq> every 15–30 s.
	•	Expect 0x25 0x04 ack.
	•	On timeout → flag disconnect and retry.
	•	Console log: [BLE] ❤️ Keepalive ACK.

⸻

6️⃣ Reliability and Timing

Constraint	Guideline
Chunk delay	≈ 5 ms between writes
Ack wait	Expect 0xC9 before next command
CRC	Send 0x16 + CRC32 for image validation
Failure	Auto-retry 3× then mark degraded


⸻

7️⃣ HUD Message Stub

fun sendHudMessage(text: String, durationMs: Int = 5000)

	•	Clears previous HUD buffer.
	•	Displays text for durationMs, then restores default HUD (status icons).
	•	Console → [HUD] Flash: "<text>".

⸻

8️⃣ Assistant Integration

Assistant messages distinguish their origin:

Source	Label	Example
LLM (online)	Assistant 🟢 (ChatGPT)	“Temperature is 29 °C.”
Device Telemetry	Assistant 🟣 (Device Only)	“Battery 87 %, Firmware v2.13.”

Offline diagnostics pull data from DiagnosticRepository.

⸻

9️⃣ Console and Diagnostics Logging

Prefix all BLE entries with tags:

Tag	Meaning
[BLE]	Connection / command state
[DIAG]	Telemetry summary
[HUD]	Display events

Store logs via MemoryRepository for offline review.

⸻

🔬 Acceptance Tests

Scenario	Expected Result
Send Battery Query	Console → [BLE] Battery 92 % Case 89 %
Heartbeat timeout	Console → [BLE] Timeout – Reconnecting
Touchpad tap	Console → [BLE] Event 0xF5 17 (Tap Right)
HUD text message	[HUD] Flash: "Connected" appears


⸻

🔧 Files to Implement or Modify

File	Purpose
core/bluetooth/G1BleClient.kt	Low-level UART connect, write, notify
hub/data/telemetry/BleTelemetryRepository.kt	Parses packets → battery/firmware
hub/assistant/DiagnosticRepository.kt	Assistant telemetry source
hub/console/ConsoleInterpreter.kt	Adds BLE log summaries
hub/ui/components/BleStatusView.kt	Visual 🟢/🟡/🔴 indicator with RSSI


⸻

✅ EXIT CRITERIA (User Verification)

Test	Expected Behaviour
Both sides connected	Status bar 🟢 Connected (L/R shown)
Battery query	Displays accurate % and updates every poll
Loss of connection	Auto-reconnect after heartbeat miss
HUD flash	Text appears for 5 s then restores default HUD
Touch gesture	Logged as event and callable by assistant
Console log	All [BLE]/[DIAG]/[HUD] entries visible
Color theme	Only black/gray backgrounds, white text/icons
Stability	No crashes or stale BLE locks


⸻

🔮 NEXT PHASE (PREVIEW)

Phase 4.1 — HUD Visual and Gesture Pipeline
• Implement actual HUD render on glasses (displays assistant text).
• Add hold-gesture and wake-word logic (left pad activation).
• Enable firmware version and diagnostic telemetry reporting.

Phase 4.2 — Voice and Microphone Bridge
• Integrate glasses microphone input for assistant queries.
• Implement low-latency speech transmission and TTS loopback.

⸻

📄 PROGRESS NOTES

(Codex appends here after each patch)

[4.0-r1] Initial BLE Core Fusion framework and bidirectional communication setup.
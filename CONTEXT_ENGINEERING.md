🧠 Moncchichi Hub — Context Engineering Document

(Shared operational memory between ChatGPT, Codex, and the user)

⸻

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT_PHASE: Phase 4.0 — BLE Core Fusion (Foundational Hardware Link)
PHASE_OBJECTIVE:
Establish live bidirectional communication between the Even Realities G1 glasses and the Moncchichi Hub app.
Decode incoming BLE telemetry, interpret handshake and data packets, and allow the assistant to describe live connection, battery, and firmware information both online and offline.
This phase lays the foundation for later phases that will send text to the G1 HUD and synchronize assistant feedback directly on the glasses.

⸻

🧩 CURRENT MILESTONES

#	Milestone	Status	Notes
1	BLE telemetry handshake and live exchange	🟡 In progress	App receives and logs data from glasses (battery, case, firmware).
2	Packet decoding and error handling	🟡 In progress	Define structure for BLE data frames and parse fields accurately.
3	Assistant BLE awareness	🟡 Pending	Assistant describes BLE state or diagnostic issues (e.g. “RSSI low – move closer”).
4	Console telemetry expansion	🟢 Planned	Add BLE signal quality, firmware version, and handshake logs to console.
5	HUD message trigger stub	🟢 Planned	Create placeholder method to push 5-second assistant messages to HUD (550 × 150 px area).
6	Diagnostic query support	🟢 Planned	Assistant answers “battery status” / “BLE connected?” from live data.
7	UI monochrome theme retention	🟢 Ongoing	Keep black + gray surfaces, white text only; purple for console accent only.
8	Voice and microphone routing	❌ Deferred	Moved to Phase 4.2 (voice integration).
9	Firmware OTA update hooks	❌ Deferred	Future implementation after BLE stability.


⸻

🧠 CODEX IMPLEMENTATION GUIDELINES

(Persistent framework for interpretation and action)

1️⃣ Segment Before Coding

Break this document into:
	•	What to build: active Phase 4.0 BLE tasks.
	•	What to skip: HUD text sync and voice routines (reserved for later phases).
	•	Verification: console log entries and assistant BLE responses.

Codex must not implement future-phase features without explicit instruction.

⸻

2️⃣ BLE Telemetry Implementation Scope
	•	Read raw packets from Even Realities G1.
	•	Parse and store the following fields:
• Glasses battery %
• Case battery %
• Firmware version and build string
• RSSI / signal strength
• Device ID and connection state
	•	Display these values in console and assistant responses.
	•	Log BLE keep-alive intervals and errors (e.g. timeout, CRC mismatch).

⸻

3️⃣ Assistant Integration Guidelines
	•	Assistant responses should differentiate between:
• 🟢 ChatGPT (LLM) – online responses.
• 🟣 Device Diagnostic – offline or BLE-only responses.
	•	Example phrasing: “Assistant 🟣 (Device Only): Battery 87 %, Firmware v2.13.”
	•	Both online and offline assistants can reference telemetry data from the diagnostic repository.

⸻

4️⃣ HUD Stub Specification
	•	Reserved HUD canvas: ≈ 550 × 150 px, green vector font render.
	•	HUD trigger will temporarily accept plain text ≤ 90 chars.
	•	When active, HUD displays assistant message for ≈ 5 s then restores previous HUD view.
	•	Stub must be non-blocking and log trigger to console (e.g. [HUD] Flash: "Battery Full")

⸻

5️⃣ Console Enhancements
	•	Add new console categories:
• [BLE] for device link and telemetry logs
• [DIAG] for assistant diagnostic replies
	•	Allow copy + clear controls (retain Phase 3.9.5 UI).
	•	Extend diagnostic summary to show: BLE state, signal quality, firmware version.

⸻

6️⃣ Even Realities Monochrome Theme Retention

Element	Color Code	Purpose
Background (global)	#000000	Root UI background
Surface / Panel	#1A1A1A	Cards and message bubbles
Border / Divider	#2A2A2A	Section lines
Primary Text	#FFFFFF	Readable text and icons
Secondary Text	#CCCCCC	Hints / timestamps
Disabled Labels	#777777	Inactive UI
Accent (Console only)	#A691F2	Highlight for log areas only

Typography: 12 sp headers, 14 sp body, 10 sp timestamp.

⸻

7️⃣ Environment Fallback Rules

If Java 17 or AGP 8.3+ is missing:

./gradlew lint --no-daemon

Codex must append to Progress Notes:
⚙️ Build skipped: Java 17 missing; lint completed successfully.

⸻

8️⃣ Progress Notes Discipline

Each patch must log concise entries:

[4.0-r1] Initial BLE telemetry parser and console expansion.  
[4.0-r2] Assistant BLE awareness added.  
[4.0-r3] HUD flash stub created.  

Mark unverified features as 🟡 Pending Review.

⸻

9️⃣ Output Validation Philosophy
	•	Confirm real-time BLE data appears in console.
	•	Assistant accurately reflects BLE state online/offline.
	•	HUD stub triggers visible in console only (no render yet).
	•	UI retains monochrome theme (black + gray, white text).

⸻

🧾 DESIGN SUMMARY

Core Purpose:
Link the Even Realities G1 hardware to the Moncchichi Hub through live BLE telemetry, expand diagnostic visibility, and prepare for future HUD data display and assistant sync.

⸻

✅ EXIT CRITERIA (User Verification)

Test Scenario	Expected Behaviour
Connect G1 glasses	Console shows handshake and battery data in real time.
Ask assistant “battery status”	Assistant 🟣 (Device Only) reports live battery and firmware.
Disconnect BLE	Assistant announces “device disconnected”; console logs event.
Send HUD flash command	Console shows [HUD] Flash: "message"; no error thrown.
Console UI	Retains black/gray theme, white text only.
No RECORD_AUDIO	Confirmed absent.


⸻

🔮 NEXT PHASE (Preview Only)

• Phase 4.1 — HUD Message Pipeline: Send assistant text to glasses display (550 × 150 px).
• Phase 4.2 — Voice Input Routing: Integrate microphone and wake gesture control.
• Phase 4.3 — Assistant Sync: Show assistant messages and live diagnostics on HUD.

⸻

📄 PROGRESS NOTES

(Codex appends here after each patch)

[4.0-r1] Initial BLE Core Fusion document – telemetry and HUD stub specification.
[4.0-r2] BLE telemetry parsing + diagnostic console/assistant updates.
⚙️ Build skipped: Java 17 missing; lint completed successfully.
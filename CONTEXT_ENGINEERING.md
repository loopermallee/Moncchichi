🧠 Moncchichi Hub — Context Engineering Document

(Shared operational memory between ChatGPT, Codex, and the user)


---

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT_PHASE: Phase 4.0 rev 3 — BLE Core Fusion
(Wave 1 Foundations → Wave 1c Refinements → UART Verification Integration)

PHASE OBJECTIVE

Stabilize the Even Realities G1 dual-lens BLE foundation with full ACK-based sequencing, parallel telemetry channels, and heartbeat synchronization.
All telemetry, diagnostics, and HUD messages must complete in ≤ 2 seconds with 5 ms chunk pacing and 3× retry logic.

Wave 1c+ integrates UART text bridge logic to interpret human-readable data (“ver 1.6.5, DeviceID 4010”), merges both binary + UTF-8 flows, and exposes them to the existing console pipeline for unified debugging.

Maintain Even Realities monochrome theme and offline-first principles from Phase 3.


---

🧩 CURRENT MILESTONES (Sequenced by Wave)

#	Milestone	Wave	Status	Notes

1	Dual-lens BLE connection (L + R)	1	🟢 Implemented / 🟡 Pending Validation	MoncchichiBleService + G1BleClient manage dual GATT sessions and per-lens state.
2	Bidirectional communication (App ↔ Glasses)	1	🟢 Implemented / 🟡 Pending Validation	ACK-aware send pipeline with mutex and 5 ms stagger.
3	BLE telemetry (battery %, firmware, RSSI)	1 → 1c	🟡 In Progress	BleTelemetryRepository parses 0x2C / 0x37 / 0x11; auto-reset on disconnect; r1c adds RSSI + UTF-8 fallback.
4	Heartbeat (keepalive every 30 s)	1	🟢 Implemented / 🟡 Pending Validation	0x25 sequence heartbeat per lens with 0x25 0x04 ACK.
5	HUD messaging API	2	⚫ Not Started	Wave 2 – sendHudMessage() broadcast + ack feedback.
6	Event decoding (touch, case open)	2	⚫ Not Started	Reserve for Phase 4.1.
7	Diagnostic console integration	2	🟢 Partial / 🟡 Aligning in r1c	ConsoleInterpreter summaries; r1c adds firmware + case notes.
8	Assistant diagnostic bridge	3	⚫ Not Started	Wave 3.
9	Monochrome theme	—	🟢 Implemented	BleStatusView palette (black/gray/white).
10	Docs + progress notes	—	🟢 Updated	Includes [4.0-r1], [r1b], [r1c+UART].



---

🔌 BLE PAIRING & COMMUNICATION LOGIC (NEW SECTION)

1️⃣ Device Architecture

The Even Realities G1 is a dual-lens system, each side (L/R) advertising as a separate BLE peripheral.

Both lenses expose identical GATT services:

Nordic UART Service (NUS) 6E400001-... → TX (Notify) + RX (Write)

SMP Service 8D53DC1D-... (for future DFU only)


The pair share a common DeviceID 4010, firmware ver 1.6.5, and broadcast telemetry lines such as +i and net build time:.


2️⃣ Pairing Flow (Observed via nRF Connect)

1. User initiates pair (App → scan → connect).


2. Android requests bond if not present.


3. Device responds with TX notifications after service discovery.


4. MTU negotiation (~498 bytes accepted).


5. App receives mixed binary and UTF-8 frames over TX.



3️⃣ Communication Pattern

Binary frames carry telemetry (opcodes 0x2C, 0x37, 0x11).

Text lines carry metadata (ver, DeviceID, net build time, +i).

App sends ASCII commands like ver, ping, brightness 80 to RX.

The device responds via TX notify within ≤ 1 s.

SMP characteristic remains idle until future firmware updates.


4️⃣ Pairing State & Failure Modes

Scenario	Symptom	Likely Cause

App cannot connect after Even Reality pair	Connection state = FAILED	Bond collision (dual host issue).
App connects but no data	TX notify not armed or UTF-8 path not parsed	Subscription or decode gap.
Clears cache → works again	Old GATT cache blocking NUS discovery	Android GATT cache artifact.



---

⚠️ CURRENT ISSUE STATE (as of latest diagnostic)

Symptom:

App scans and connects but reads nothing from the glasses.

Console shows → PING | ← ERR and heartbeat errors.

Clearing cache re-enables connectivity temporarily.


Verified from nRF Logs:

Both left and right glasses emit text and binary frames over NUS TX.

Therefore, the problem lies in the app’s BLE subscription and parsing path, not the device.


Current status:

TX notifications exist and carry data, but Moncchichi Hub ignores them due to binary-only parsing.

Bond cache inconsistencies can cause temporary “Connection Failed” until cleared.



---

💡 NEXT DISCUSSION POINT (for Codex)

To Be Discussed / Solution Design Phase

1. Should we migrate the UART bridge logic into the core BLE service (core/) instead of hub/ duplicate?


2. How should binary and text flows be merged to avoid double consumption?


3. Should we use BleTelemetryRepository.maybeEmitUtf8() as a non-blocking UTF-8 tap so binary parsing remains unaffected?


4. How to handle per-lens disconnect recovery and graceful rebinds when idle sleep triggers GATT CONN TIMEOUT?




---

🧩 Merged Diagnostic Verification (R + L Lens)

Function	Right Lens	Left Lens	Result

NUS TX/RX service present	✅	✅	Identical UUID map on both lenses.
SMP service present	✅	✅	DFU reserved only.
MTU negotiation	498	498	Stable transfer length for UART.
TX notifications active	✅	✅	Active stream confirmed.
Binary telemetry opcodes	2C, 37, 11	2C, 37, 11	Same telemetry format.
Text lines	+i, ver 1.6.5, DeviceID 4010	Same	Same system ID and firmware.
Disconnect pattern	Idle timeout	Idle timeout	Power-save behavior of firmware.


Conclusion:
Both lenses transmit valid UART data and binary telemetry simultaneously; the Moncchichi app must decode the UTF-8 stream to surface firmware and DeviceID information.


---

📊 Integration with Phase 4.0 Roadmap

The UART bridge and console tap now sit between Wave 1c and Wave 2.0, providing a foundation for future “live assistant feedback” and DFU telemetry.
This change does not alter any existing BLE contracts (BleTool, MoncchichiBleService API surface).


---

✅ SUMMARY

Device Side: Working — Both lenses emit telemetry + text.

App Side: Partial — Binary path functional; UTF-8 path ignored.

Next Action: Discuss with Codex how to integrate the UART bridge into the core BLE service layer and finalize telemetry unification for Wave 1d.



---

Would you like me to append a short “Codex Review Prep – UART Integration Plan” section next (to frame our next question to Codex, listing what input or confirmation we’ll need from them before implementation)?
Context Engineering – Phase 4.7 / Tasks 5 – 7

Task 5 – Telemetry Persistence & Snapshot Integration
Goal:
Persist all runtime telemetry from the glasses into the app’s local memory database so that state continuity survives disconnects, backgrounding, or app restarts.

Logic Overview:
• Each lens and case broadcast telemetry data such as voltages, charge %, lid state, silent mode, firmware version, uptime, RTT (latency), and last ACK status.
• Whenever new telemetry arrives, a snapshot record must be created or updated in MemoryRepository.
• The repository keeps only the latest snapshot per lens and case, replacing older data instead of duplicating entries.
• Snapshots are serialized as JSON strings stored in leftSnapshotJson and rightSnapshotJson columns under MemoryDb schema v7.
• DeveloperViewModel subscribes to a Flow of snapshot updates and exposes a concise text summary for the Developer screen.
• On export, the telemetry snapshots are attached to diagnostic reports.

Expected Behavior:
	1.	When telemetry updates occur (battery, lid state, or ACK events), the repository automatically persists a new snapshot.
	2.	The Developer console displays a periodic line every 30 seconds showing case %, lens voltages, firmware, and uptime.
	3.	Snapshots survive reconnects and app restarts.

Validation:
Telemetry persists accurately, without duplication or crash on migration. The Developer UI shows unified snapshot data.

Exit Criteria:
• Telemetry recorded every 30 s with current values.
• Database migration stable from v6 → v7.
• Export includes case + lens snapshots.
• No null references after disconnect / reconnect.

Task 6 – Case / Gesture Protocol Expansion (Even Alignment)
Goal:
Update BLE protocol handling to match the latest Even Realities G1 specifications.

Logic Overview:
• Extend the BLE parser to handle all confirmed commands and responses:
 – Case telemetry 0x2B, 0x2C (now includes silent mode and lid state codes 0x0A–0x0B).
 – Gesture events 0xF5 with new IDs (0x1E–0x20 dashboard / translate actions, 0x06–0x0B wear + case states).
 – System commands 0x23 (6C debug toggle, 72 reboot, 74 firmware info).
 – Display settings 0x26 subcommands (02 height/depth with preview flag, 04 brightness, 05 double-tap action, 07 long-press, 08 mic-on-lift).
 – Serial queries 0x33 and 0x34 for lens and frame IDs.
• Replace hard-coded labels like “unknown gesture” with proper descriptions from the new map.
• Integrate these fields into the telemetry pipeline so they persist and log consistently with existing data.

Expected Behavior:
• All BLE packets decode without error or “unknown” logs.
• Developer console shows readable case + gesture messages.
• System info and firmware queries function as documented.

Validation:
Parity confirmed against Even app logs under identical actions (taps, lid open/close, charge toggle).

Exit Criteria:
• 100 % of new opcodes decoded and displayed correctly.
• No unknown gesture warnings in console.
• All firmware and serial requests return valid responses.

Task 7 – Developer Console UX Refinement
Goal:
Improve the Developer console for clarity and real-time use during testing.

Logic Overview:
• Auto-scroll the console to the latest entry whenever new logs arrive, unless the user has paused scrolling.
• Apply color-coded text themes against a dark background for readability:
 – Normal = light gray
 – OK / Success = green
 – Warning = yellow
 – Error = red
• Allow optional filtering by tag (“PING”, “CASE”, “GESTURE”, “ACK”).
• Preserve scroll position and highlight timestamp when paused.

Expected Behavior:
Console updates automatically without manual scrolling, color codes match event severity, and filter switches apply instantly.

Validation:
Visually verify color legibility on dark background, confirm auto-scroll and pause/resume work in real time.

Exit Criteria:
• No manual scrolling needed for live logs.
• Colors clear and accessible.
• Filters function correctly and persist across sessions.
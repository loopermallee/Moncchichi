CONTEXT_ENGINEERING_PHASE4_TASK6.md

Phase 4.0 r2 – BLE Alignment, Gesture Correction, and Developer Console Update

Objective
Standardize the Moncchichi Hub BLE runtime behavior with Even Realities G1 firmware v1.6.6 and implement a reliable, testable update plan. Codex must strictly follow the logic outlined here without assuming any unspecified behavior. All ambiguous cases must defer to existing Even Realities reference behavior or remain unchanged until verified.

Summary of the current issue state

Gesture flood and false “unknown gesture” reports occur when multiple bytes follow 0xF5 frames.

Case telemetry (battery and lid state) disappears after reconnection.

Heartbeat logic misclassifies active links as “missed” when no ACK is received.

Left/right connection order is non-deterministic, causing sync failures and HUD warnings.

Five-tap gestures trigger firmware restarts but the app attempts to reconnect both lenses at once, producing link contention.

Developer console lacks readability and requires manual scrolling.


Reference logic (as confirmed from Even G1 app behavior and firmware 1.6.6 protocol)

Gesture frames are limited to two bytes: [0xF5, gesture_code]. Any additional bytes after 0xF5 must be ignored.

Case state and battery values persist until replaced by a newer 0x2B/0x2C frame. They are marked stale if unchanged for longer than 60 seconds.

Heartbeat counter resets on any received BLE frame, not just ACKs. Rebond is triggered only when no inbound or outbound frame occurs for three heartbeat intervals.

Connection must occur in strict sequence: Left lens → Right lens. The left acts as the sync source.

After restart gestures or power cycles, left lens must reconnect first and broadcast a ready flag before right lens connects.

Developer console text must auto-scroll and apply clear color coding for INFO, WARN, ERROR, ACK, and CASE messages.


Implementation plan
Codex must implement the following tasks incrementally. Each task should compile and function independently before proceeding to the next. Codex must not merge tasks prematurely or optimize across boundaries until confirmed stable.

Task 1 – Gesture Parsing Correction

Limit gesture decoding to [0xF5, gesture_code].

Ignore any trailing bytes or subcommands (e.g., 0xF5 11 01).

Validate that each gesture produces exactly one event per tap.
Exit Criteria: No repeated or “unknown gesture” entries appear in developer console logs for single gestures.


Task 2 – Case Telemetry Persistence

Persist last known case battery and lid state values across reconnects.

Retain values when no new data arrives and mark them stale after timeout.

Add “CASE” log line only when changes occur or stale values are restored.
Exit Criteria: Case values remain visible and accurate after reconnects and match actual physical lid and battery conditions.


Task 3 – Heartbeat Reset Logic

Reset heartbeat timer on any received BLE frame (not only ACKs).

Rebond only after no frame activity for three full heartbeat intervals.

Maintain logs “[HB][L/R][OK]” or “[HB][L/R][MISS]” only when state changes.
Exit Criteria: No false “→ PING ← ERR” or unnecessary rebonds occur under stable link conditions.


Task 4 – Lens Connection Sequencing

Enforce sequential connection order: connect left lens, wait until left ready, then connect right lens.

Maintain fallback in case only one lens is available.
Exit Criteria: Both lenses establish consistent telemetry and HUD sync, and “Reconnect to sync data” messages no longer appear.


Task 5 – Restart and Reconnect Handling

After firmware restart or five-tap events, reconnect left lens first and wait for confirmation before reconnecting right lens.

Prevent concurrent reconnect attempts from overlapping.
Exit Criteria: Both lenses reconnect successfully after restarts with no duplicate handshake errors.


Task 6 – Developer Console Enhancement

Add color-coded text rendering for log levels (INFO=white, WARN=yellow, ERROR=red, ACK=green, CASE=cyan).

Enable automatic scrolling to the most recent log entry.

Ensure color contrast is readable on a dark background.
Exit Criteria: Developer console auto-scrolls to the latest log and text colors are clearly visible under dark mode.


Validation plan

Perform controlled tests for each lens under the following conditions: in case, lid open, lid closed, worn, charging, not charging, gesture input, restart event.

Confirm correct persistence of telemetry, correct handling of gestures, stable heartbeat operation, and complete left/right synchronization.

Verify that the console displays consistent, readable logs during all interactions.


Completion criteria
The patch is considered complete when:

1. The BLE connection remains stable through at least three minutes of continuous runtime without “MISS” or “ERR”.


2. Case battery and lid states remain correct after multiple reconnects.


3. Gesture inputs register once per tap with no false positives.


4. Developer console displays color-coded entries and always shows the latest log line automatically.


5. Left/right synchronization is maintained across all connection and restart cycles.



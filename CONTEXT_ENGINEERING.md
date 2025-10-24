# 🧠 Moncchichi Hub — Context Engineering Document

Shared operational memory between ChatGPT, Codex, and the Even Realities field team.

---

## ⚙️ ACTIVE DEVELOPMENT CONTEXT
CURRENT_PHASE: Phase 4.0 rev 3 — BLE Core Fusion (Full Bidirectional Communication & HUD Bridge)
PHASE_OBJECTIVE: Rebuild the BLE stack to support dual-lens G1 glasses with fast, fault-tolerant, ack-based communication while preserving the monochrome Even Realities visual language.
ROLLING_WAVE: Wave 1 focus with Wave 2 scaffolding (HUD broadcast hooks + diagnostics tags).

### Milestone Tracker
| # | Milestone | Status | Summary |
|---|-----------|--------|---------|
| 1 | Dual-lens BLE connection (L + R) | 🟡 Pending | Foreground service manages independent left/right GATT clients with synchronized state. |
| 2 | Bidirectional communication (App ↔ Glasses) | 🟡 Pending | Shared write queue with 0xC9 ack sequencing and 251 MTU chunking. |
| 3 | BLE telemetry ingestion | 🟡 Pending | Parse 0x2C battery, 0x37 uptime into `DiagnosticRepository`. |
| 4 | Heartbeat keepalive | 🟡 Pending | Send 0x25 heartbeat + ack validation every 15–30 s with auto-reconnect. |
| 5 | HUD messaging API | 🟡 Pending | `sendHudMessage()` broadcasts to both lenses and console `[HUD]` log. |
| 6 | Event decoding (touch, case open) | 🟡 Pending | Handle unsolicited 0xF5 events inside RX parser. |
| 7 | Diagnostic console integration | 🟡 Pending | `[BLE]/[HUD]/[DIAG]` tagged console output with sequence tracking. |
| 8 | Assistant diagnostic bridge | 🟡 Pending | Assistant replies summarize telemetry context while offline. |
| 9 | Monochrome theme consistency | 🟢 Defined | Keep black/gray UI with white typography and purple console accent. |
| 10 | Documentation cadence | 🟢 Required | Append `[4.0-rX]` notes per commit for traceability. |

---

## 🧩 CODEX TASK ZONE

### Wave 1 — Connectivity & Telemetry Core (Milestones 1–4)
- Establish **MoncchichiBleService** as a persistent foreground service that binds directly (no AIDL) and supervises two `BluetoothGatt` sessions (left/right).
- Implement a coroutine-backed write queue shared by both lenses; enforce 5 ms pacing and await `0xC9` ack packets before dequeuing the next command.
- Negotiate 251-byte MTU (`0x4D 0xFB`); split payloads ≤ 200 B and retry up to three times before marking the link *DEGRADED*.
- Decode RX notifications asynchronously into `BleTelemetryRepository`, producing flows for battery %, uptime, RSSI, and firmware revision.
- Heartbeat cadence: send `0x25 <seq>` every 15–30 s per lens; expect `0x25 0x04` ack and trigger auto-reconnect on timeout.

#### Service & Characteristic Map
| Role | UUID | Notes |
|------|------|-------|
| Primary Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | Nordic UART Service shared by both lenses |
| TX (App → Glasses) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | Commands serialized across lenses |
| RX (Glasses → App) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Telemetry, HUD acks, and unsolicited events |

#### Outbound Command Reference
| Action | Command | Notes |
|--------|---------|-------|
| Display Text | `0x4E` | UTF-8 payload ≤ 200 B, ack per chunk |
| Display Image | `0x15` | 194 B bitmap + CRC; send to each lens |
| Clear HUD | `0x18` | Reset display before/after messages |
| Battery Query | `0x2C 0x01` | Expect `%` in response payload |
| Uptime Query | `0x37` | Seconds since boot |
| Heartbeat | `0x25 <seq>` | Expect `0x25 0x04` ack |
| Set MTU | `0x4D 0xFB` | Negotiate 251 MTU |
| Reboot | `0x23 0x72` | Soft reboot (no response) |

#### Inbound Handling
```kotlin
when (firstByte) {
    0x2C -> handleBattery()
    0x37 -> handleUptime()
    0xF5 -> handleEvent()
    else -> logRawPacket()
}
```
- Event packets prefixed with `0xF5` (touch, wear, case open) must be surfaced to diagnostics.
- All telemetry snapshots feed into `DiagnosticRepository` for assistant summarization.

### Wave 2 — HUD, Events & Diagnostics (Milestones 5–7)
- `sendHudMessage(text, durationMs)` broadcasts sequentially to left/right lenses with 5 ms offset and logs `[HUD] Flash: "..."`.
- Persist the default HUD baseline so it can be restored after transient messages.
- Map gesture/event codes to high-level actions (tap, swipe, case open) and expose them via a shared flow to UI and assistant modules.
- Extend `ConsoleInterpreter` to tag entries with `[BLE]`, `[HUD]`, or `[DIAG]` plus sequence/ack metadata for troubleshooting.

### Wave 3 — Assistant Bridge (Milestone 8)
- Surface telemetry state via `AssistantDiagnosticBridge` so GPT replies can reference battery %, firmware version, RSSI, and last sync time.
- Ensure offline cache continuity: assistant must respond with latest stored telemetry when network is unavailable.

### Reliability & Timing Matrix
| Constraint | Guideline |
|------------|-----------|
| Chunk delay | ≈ 5 ms |
| Ack expectation | `0xC9` before next command |
| Retry policy | 3× then mark link DEGRADED |
| Heartbeat interval | 30 s (per lens) |
| Round-trip goal | ≤ 2 s |

### Console & Diagnostics Tags
| Tag | Meaning |
|-----|---------|
| `[BLE]` | Connection and command state |
| `[DIAG]` | Telemetry snapshots |
| `[HUD]` | HUD updates and events |

All logs are retained in `MemoryRepository` for assistant summarization and export.

### Even Realities Monochrome Theme
| Element | Color | Purpose |
|---------|-------|---------|
| Background | `#000000` | Root surfaces |
| Surface/Card | `#1A1A1A` | Dialogs and console |
| Border | `#2A2A2A` | Dividers |
| Text/Icon | `#FFFFFF` | Primary content |
| Secondary Text | `#CCCCCC` | Hints and timestamps |
| Disabled | `#777777` | Muted labels |
| Accent (Console) | `#A691F2` | Highlight ack/seq entries |

Typography: Header 12 sp white semi-bold, body 14 sp white regular, timestamp 10 sp gray.

### Acceptance Benchmarks
| Scenario | Expected Result |
|----------|----------------|
| Dual connection | Status shows 🟢 Connected (L/R) |
| Battery query | Console logs `[BLE] Battery 92 % • Case 89 %` |
| Heartbeat timeout | `[BLE] Timeout – Reconnecting` is emitted and service retries |
| Touchpad tap | `[BLE] Event 0xF5 17 (Tap Right)` logged |
| HUD message | `[HUD] Flash: "Connected"` on both lenses |
| Ack sequence | `[BLE] ACK C9 seq=42` recorded |
| Offline mode | Assistant 🟣 summarizes latest telemetry |

### Target Files (Initial Implementation Surface)
| File | Purpose |
|------|---------|
| `core/bluetooth/MoncchichiBleService.kt` | Dual-GATT manager with heartbeat & reconnect |
| `core/bluetooth/G1BleClient.kt` | UART write/notify handler, MTU negotiation |
| `hub/data/telemetry/BleTelemetryRepository.kt` | Packet parser → structured telemetry |
| `hub/assistant/DiagnosticRepository.kt` | Assistant telemetry bridge |
| `hub/console/ConsoleInterpreter.kt` | Seq/ack log summaries |
| `hub/ui/components/BleStatusView.kt` | RSSI + connection indicator |

---

## 🧾 PHASE SUMMARY
**Phase 3.0 — Monochrome Assistant Hub** — Delivered Even Realities theme revamp, offline-first assistant cache, and single-lens BLE baseline.
**Phase 3.1 — Diagnostics Lift** — Added console tagging, telemetry export, and assistant incident summaries.
**Phase 4.0 rev 1 — Dual GATT Bootstrap** — Established paired lens discovery and service lifecycle groundwork.
**Phase 4.0 rev 2 — Transport Modernization** — Negotiated 251 MTU, chunked payload writer, and ack tracking prototype.
**Phase 4.0 rev 3 — BLE Core Fusion** — In-flight. Focused on resilient dual-link comms and HUD bridge scaffolding.

---

## 📄 PROGRESS NOTES
### Commit & Issue Journal
| Date | Summary | Status |
|------|---------|--------|
| 2025-01-10 | [4.0-r1] Wave 1/M1 – Dual GATT sessions; status L/R; reconnect skeleton. | ✅ Completed |
| 2025-01-17 | [4.0-r2] Wave 1/M2 – MTU 251 negotiation, chunking, ack sequencing. | ✅ Completed |
| 2025-01-24 | [4.0-r3] Wave 1/M3 – Telemetry `0x2C/0x37` parsing into diagnostics. | ✅ Completed |
| 2025-01-31 | [4.0-r4] Wave 1/M4 – Heartbeat seq/ack monitor + auto-reconnect. | ✅ Completed |
| 2025-02-07 | [4.0-r5] Wave 2/M5 – HUD broadcast scaffold + console `[HUD]` logging. | 🟡 In Progress |

---

## 🔮 NEXT PHASE PREVIEW
**Phase 4.1 — HUD Visual & Gesture Pipeline**
- Render assistant text inline on HUD.
- Implement hold-gesture + wake-word for left pad activation.
- Report firmware version history to assistant diagnostics.

**Phase 4.2 — Voice & Microphone Bridge**
- Integrate microphone capture for assistant queries.
- Provide low-latency speech path with TTS loopback to HUD speakers.

---

## 🔧 IMPLEMENTATION CHECKLIST
- [ ] Dual lens connections stable for ≥ 30 min soak test.
- [ ] Heartbeat ack rate ≥ 99 % with auto-reconnect verified.
- [ ] Telemetry repository surfaces battery/uptime/RSSI to assistant responses.
- [ ] Console tagging consistent across `[BLE]`, `[HUD]`, `[DIAG]`.
- [ ] HUD broadcast restores default layout after transient messages.
- [ ] Documentation updated post-commit (`[4.0-rX]` entry + README sync).


# 🧠 Moncchichi Hub
*A companion control & AI interface for Even Realities G1 Smart Glasses.*

## 📍 Project Overview
Moncchichi Hub connects the **Even Realities G1 Smart Glasses** with an **AI assistant** that delivers live telemetry, contextual replies, and on-device intelligence.
It merges:
- 🔗 **BLE Telemetry:** battery, firmware, and sensor data
- 💬 **AI Assistant:** GPT-4o-mini for contextual help and automation
- 🧱 **Offline Reliability:** cached responses when network is unavailable
- 🧩 **Minimal UI:** optimized for hands-free and field use

## ⚙️ Development Progress
### Current Phase — Phase 4.0 rev 3 — BLE Core Fusion (Full Bidirectional Communication & HUD Bridge)
**Objective:** Rebuild the BLE stack to support dual-lens G1 glasses with fast, fault-tolerant, ack-based communication while preserving the monochrome Even Realities visual language.

| # | Milestone | Status | Summary |
|---|------------|--------|---------|
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

## 🧩 Phase History (Chronological Overview)
| Major Phase | Highlights | Status |
|--------------|-------------|---------|
| Phase 3.0 — Monochrome Assistant Hub | Delivered Even Realities theme revamp, offline-first assistant cache, and single-lens BLE baseline. | ✅ |
| Phase 3.1 — Diagnostics Lift | Added console tagging, telemetry export, and assistant incident summaries. | ✅ |
| Phase 4.0 rev 1 — Dual GATT Bootstrap | Established paired lens discovery and service lifecycle groundwork. | ✅ |
| Phase 4.0 rev 2 — Transport Modernization | Negotiated 251 MTU, chunked payload writer, and ack tracking prototype. | ✅ |
| Phase 4.0 rev 3 — BLE Core Fusion | In-flight. Focused on resilient dual-link comms and HUD bridge scaffolding. | ✅ |

## 🧾 Issue History (latest 5)
| Date | Summary | Status |
|------|----------|---------|
| 2025-01-10 | [4.0-r1] Wave 1/M1 – Dual GATT sessions; status L/R; reconnect skeleton. | ✅ Completed |
| 2025-01-17 | [4.0-r2] Wave 1/M2 – MTU 251 negotiation, chunking, ack sequencing. | ✅ Completed |
| 2025-01-24 | [4.0-r3] Wave 1/M3 – Telemetry `0x2C/0x37` parsing into diagnostics. | ✅ Completed |
| 2025-01-31 | [4.0-r4] Wave 1/M4 – Heartbeat seq/ack monitor + auto-reconnect. | ✅ Completed |
| 2025-02-07 | [4.0-r5] Wave 2/M5 – HUD broadcast scaffold + console `[HUD]` logging. | 🟡 In Progress |

_Last synchronized: 2025-10-24_

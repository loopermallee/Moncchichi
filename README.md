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
### Current Phase — ** Phase 3.9.1 — Diagnostic & Refinement Pass
**Objective:** ** Finalize assistant reliability and diagnostic awareness.

| # | Milestone | Status | Summary |
|---|------------|--------|---------|
| 1 | Lock and mask API key field after validation | 🟡 In progress | Greyed-out text box, “Edit Key” toggle |
| 2 | Reset to Defaults clears key + restores temperature onboarding text | 🟡 In progress | Resets all OpenAI prefs |
| 3 | Remove model dropdown → static GPT-4o-mini label | ✅ Complete | Simplified UI |
| 4 | Implement refined temperature slider behaviour | ✅ Complete | Dynamic text after first move |
| 5 | Expand Offline Assistant to diagnostic agent | 🟡 In progress | Access BLE + Network + System state |
| 6 | Provenance label for each message (LLM / Offline / Device) | ✅ Complete | Headers above bubbles |
| 7 | Distinguish Offline vs Fallback tone & colour in UI | 🟡 In progress | Orange vs Yellow accents |
| 8 | Even Realities theme colour application | 🟡 In progress | Use `#A691F2` / `#272033` |
| 9 | Improve scroll and bubble alignment | 🟡 Todo | Guarantee auto-scroll and header alignment |
| 10 | Add timestamp below bubbles | ⏳ Todo | Small grey 10 sp font |
| 11 | Offline query queue & resync reply | ⏳ Todo | Store in `MemoryRepository` |
| 12 | BLE status → assistant conversion (device origin) | ⏳ Todo | BLE logs → chat |
| 13 | Stability & threading cleanup | 🟡 In progress | Ensure main-thread UI updates |

## 🧩 Phase History (Chronological Overview)
| Major Phase | Highlights | Status |
|--------------|-------------|---------|
| Previous Phase: | Clairvoyant Workflow (BLE + LLM merge) | ✅ |
| Current Phase: | Diagnostic & Refinement Pass | ✅ |
| Next Planned Phase: | BLE Core Fusion (Real telemetry stream + HUD sync) | ✅ |

## 🧾 Issue History (latest 5)
| Date | Summary | Status |
|------|----------|---------|
| 2025-10-25 | Added chat headers and message origins | ✅ Done |
| 2025-10-25 | Offline Assistant expanded with state context | 🟡 In progress |
| 2025-10-25 | Temperature slider onboarding restored | ✅ Done |
| 2025-10-25 | API key lock UI implemented | 🟡 Testing |

_Last synchronized: 2025-10-23_
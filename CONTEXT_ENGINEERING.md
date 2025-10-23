# 🧠 Moncchichi Hub — Context Engineering Document
*(Shared operational memory between Codex, ChatGPT, and the user)*  

---

## ⚙️ ACTIVE DEVELOPMENT CONTEXT
**CURRENT_PHASE:** Phase 3.8 — Clairvoyant Workflow  
**PHASE_OBJECTIVE:** Merge BLE telemetry + Assistant layers, refine contextual replies, simplify UI for stability, and enable local diagnostic intelligence.  

**PRIORITY_MILESTONES**
| # | Milestone | Status | Notes |
|---|------------|--------|-------|
| 1 | Integrate BLE telemetry with assistant layer | 🟡 In progress | Requires verification |
| 2 | Enable contextual replies referencing device state | 🟡 In progress | |
| 3 | Maintain stable offline fallback | ✅ Stable | Tested last build |
| 4 | Simplify settings layout (API key + temp slider only) | ✅ Complete | |
| 5 | Validate API key handling (sk-proj format) | 🟡 Pending review | |
| 6 | Verify assistant-device status banner rendering | 🟡 In progress | |
| 7 | Review BLE reconnect behaviour under idle conditions | ⏳ Todo | |
| 8 | Filter unrelated system/BLE logs from assistant chat UI | 🟡 In progress | Added 10-24-2025 |
| 9 | **Offline Assistant Diagnostics** | 🟡 In progress | Assistant interprets console logs and telemetry to explain what’s happening locally (e.g., connection attempts, missing data, stub detection, etc.) |

---

### 🧭 INSTRUCTION TO CODEX
- **Read this section before any code generation or modification.**  
- Work **only** on milestones or issues listed under `CURRENT_PHASE`.  
- Append new findings, bugs, or incomplete behaviours under **Known Issues / Errors**.  
- When completing a milestone, **do not** mark “✅” automatically — leave 🟡 until user or ChatGPT verifies it.  
- Respect architecture (core / hub / service / client).  
- Follow Kotlin + Android best practices and Moncchichi coding patterns.  

---

## 🧩 CODEX TASK ZONE  
*(Codex updates this section as it works)*  

### Known Issues / Errors
- The assistant chat log includes unrelated information that’s not between the AI and the user, or anything about the AI (such as issues).  
  It currently also includes `[BLE] → Ping` and other telemetry messages.  
  **Action:** Filter out or ignore non-chat logs so that only true AI–user interactions appear in the assistant chat view.  
  **Status:** 🟡 Pending — requires Codex cleanup of chat stream parsing logic.  

### Progress Notes  
_(Codex appends short summaries per commit here)_
| Date | Commit Summary | Status | Reviewed |
|------|----------------|--------|-----------|
| 2025-10-24 | Filtered BLE logs from chat UI | 🟡 Pending Review | — |
| 2025-10-24 | Added Offline Assistant Diagnostic framework | 🟡 In Progress | — |

---

## 🧠 CHATGPT REVIEW ZONE  
*(ChatGPT updates this section after each user review)*  

### Solutions / Ideas
- Use coroutine retry for BLE reconnect.  
- Introduce `TelemetryRepository.kt` abstraction.  
- Queue HUD messages when BLE reconnects mid-response.  
- Ensure chat adapter filters by `MessageSource.USER` and `MessageSource.ASSISTANT`.  
- Separate BLE/system log flows from chat message flows.  
- **Enable local diagnostic narration:** Assistant reads `console_log` to explain BLE/LLM status, connection failures, stub data, charging state, etc.  
- **Store offline queries and replay answers once online.**  

### Phase Review Log
| Date | Reviewed Item | Result | Notes |
|------|----------------|--------|-------|
| — | — | — | — |

---

## 🧾 PHASE SUMMARY (for reference)
**Previous phase:** 3.7 — Assistant Brain Bootstrap (core LLM integration stable).  
**Current phase:** 3.8 — Clairvoyant Workflow (BLE + LLM merge, UI refinement, offline diagnostic assistant).  
**Next planned phase:** 4.0 — BLE Core Fusion (real telemetry and HUD sync).  

---

## 🧱 DESIGN PRINCIPLES
- **Simplicity First:** Single model flow; minimal toggles.  
- **Reliability Over Complexity:** App must always recover connection.  
- **Privacy Centric:** Local-only preference storage; no analytics.  
- **Consistency:** Visuals follow Even Realities theme and typography.  
- **Transparency:** Each code update documented here before phase completion.  

---

## 🧩 INSTRUCTION TO CHATGPT
- Review new entries under *Codex Task Zone → Progress Notes* and *Known Issues*.  
- Propose solutions or architecture refinements under *ChatGPT Review Zone*.  
- Wait for **user confirmation** before updating milestone statuses.  
- When phase goals are met and verified, generate the next phase block (increment version).  

---

## 🧾 PROJECT RECAP
**Goal:** Deliver a seamless, privacy-centric bridge between the Even Realities G1 glasses and an intelligent assistant.  
**Core Focus:** Integrate BLE telemetry, LLM reasoning, and HUD display into one stable Android app experience, with local diagnostic narration to assist troubleshooting even offline.  

---

*End of Context File – Always read before committing code.*
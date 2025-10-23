# 🧠 Moncchichi Hub — Context Engineering Document
*(Shared operational memory between Codex, ChatGPT, and the user)*  

---

## ⚙️ ACTIVE DEVELOPMENT CONTEXT  
**CURRENT_PHASE:** Phase 3.9.1 — Diagnostic & Refinement Pass  
**PHASE_OBJECTIVE:** Finalize assistant reliability and diagnostic awareness.  
Refine Settings UX (slider + key logic), clarify assistant message origins, unify Even Realities visual theme, and enable Offline Assistant to self-diagnose BLE + network + LLM state autonomously.

---

## 🧩 PRIORITY MILESTONES
| # | Milestone | Status | Notes |
|---|------------|--------|-------|
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

---

## 🧭 INSTRUCTION TO CODEX  

### 1️⃣ Settings Screen UX & Logic
- Replace model dropdown with static label `GPT-4o-mini`.  
- After successful API-key validation → grey-out field + show “🔒 Saved”; require **Edit Key** button to unlock.  
- **Reset to Defaults** clears API key and restores temperature onboarding text.  
- **Temperature Slider Flow:**  
  - On load or reset → show default text `"Temperature controls how creative or precise the assistant’s answers are."`  
  - On first user move → switch to dynamic description (`Precise🧠` → `Creative🌈`).  
  - On reset → revert to default text and value 0.5.  

### 2️⃣ Assistant Chat UI Refinement
- Add speaker headers above bubbles:  
  - **You** (user)  
  - **Assistant 🟢 (ChatGPT)** (online)  
  - **Assistant ⚡ (Offline)** (diagnostic mode)  
  - **Assistant 🟣 (Device Only 🛠)** (BLE origin)  
- Align headers to speaker side; auto-scroll to last reply.  
- Bubble colours: User `#66FFB2`, Assistant `#2A2335`, Text white.  
- Optional timestamp below bubble (10 sp grey).  

### 3️⃣ Offline Diagnostic Intelligence Expansion
- Replace `generateDiagnostic()` → `generateResponse(prompt, state)`  
  - Keyword parsing for *connection, internet, bluetooth, battery, firmware, api key, status*.  
  - Pull real-time data from `AppState`, `DeviceInfo`, `ConnectivityManager`, system battery/network APIs.  
  - Natural-tone summaries (e.g. “Internet down but Bluetooth ok”).  
- Add `DiagnosticRepository.kt` to aggregate app + BLE + network + LLM status.  
- On reconnect → assistant auto-responds: “I’m back online ✅ — here’s what you asked earlier …”.

### 4️⃣ Provenance Routing and Device Awareness
- Extend `ChatMessage` with `origin = LLM / OFFLINE / DEVICE`.  
- Any BLE-generated updates (labelled DEVICE).  
- Route assistant chat via origin metadata to display proper header and icon.  

### 5️⃣ Even Realities Visual Consistency
- Replace all blue accents with violet `#A691F2`.  
- Primary background `#272033`.  
- Text contrast ensured for dark mode.  
- Status-bar and cards follow same palette (assistant green on dark violet).  

### 6️⃣ Stability and Threading Cleanup
- Guarantee UI updates on Main thread.  
- Cancel coroutines in `onCleared()`.  
- Debounce duplicate error toasts (5 s window).  

---

## 🧩 CODEX TASK ZONE
*(Codex updates these after each commit)*  

### Known Issues / Errors
- [ ] Offline Assistant still returns generic “Previously we talked about…” text instead of diagnostic.  
- [ ] BLE telemetry shows stub battery data (87 %) — verify real read.  
- [ ] Settings Reset button not clearing API key completely.  
- [ ] Timestamp alignment in chat bubbles inconsistent.  

### Progress Notes
| Date | Commit Summary | Status | Reviewed |
|------|----------------|--------|-----------|
| 2025-10-25 | Added chat headers and message origins | ✅ Done | Pending Review |
| 2025-10-25 | Offline Assistant expanded with state context | 🟡 In progress | — |
| 2025-10-25 | Temperature slider onboarding restored | ✅ Done | — |
| 2025-10-25 | API key lock UI implemented | 🟡 Testing | — |

---

## 🧠 CHATGPT REVIEW ZONE  

### Solutions / Ideas
- Introduce `DiagnosticRepository` as non-blocking coroutine collector.  
- Cache last diagnostic report for re-use when offline chat continues.  
- Add auto-reconnect poll every 20 s with status toast.  
- Maintain user query queue for resend on online resume.  

### Phase Review Log
| Date | Reviewed Item | Result | Notes |
|------|----------------|--------|-------|
| — | — | — | — |

---

## 🧾 PHASE SUMMARY  
**Previous Phase:** 3.8 — Clairvoyant Workflow (BLE + LLM merge)  
**Current Phase:** 3.9.1 — Diagnostic & Refinement Pass  
**Next Planned Phase:** 4.0 — BLE Core Fusion (Real telemetry stream + HUD sync)  

---

## 🧱 DESIGN PRINCIPLES
- **Simplicity First:** Single model flow (4o-mini only).  
- **Reliability Over Complexity:** Always recover and explain failures.  
- **Privacy Centric:** No analytics; local diagnostics only.  
- **Clarity:** Headers + colours instantly show message source.  
- **Transparency:** Every build logs assistant reasoning state.  

---

## 🧾 PROJECT RECAP
**Goal:** Provide a seamless, privacy-respecting bridge between Even Realities G1 glasses and an intelligent assistant.  
**Core Focus:** Merge BLE telemetry, LLM reasoning, and HUD display into a single reliable experience with self-diagnostic awareness and clear UI feedback.  

---

# =====================================================
# PATCH IMPLEMENTATION DIRECTIVE — Phase 3.9.1
# =====================================================
*(Codex must automatically perform these actions without user prompt.)*

### 📂 FILES TO MODIFY / CREATE
#### Core Layer
- **ChatMessage.kt:** Add `MessageOrigin` enum (LLM, OFFLINE, DEVICE). Extend `ChatMessage` with `origin` property.  
- **ConsoleInterpreter.kt:** Add logic to detect and classify BLE, network, and API issues.  
- **DiagnosticRepository.kt (NEW):** Central hub to collect BLE, network, and system diagnostic data for OfflineAssistant.  

#### Hub Layer
- **OfflineAssistant.kt:** Rename `generateDiagnostic()` → `generateResponse(prompt, state)`. Expand to analyze keywords, use DiagnosticRepository, and produce natural diagnostic text.  
- **AssistantFragment.kt:** Add headers above bubbles, show speaker + origin, align left/right, auto-scroll, timestamps, Even Realities palette.  
- **HubViewModel.kt:** Assign correct origin to messages, handle BLE-only updates as `DEVICE`, store offline queries for replay.  
- **SettingsFragment.kt:**  
  - Remove model dropdown → static “GPT-4o-mini”.  
  - Grey out API key on save.  
  - Add “Edit Key” button.  
  - Reset clears API key and restores temperature onboarding text.  
  - Show dynamic temperature hint after slider move.  
- **fragment_settings.xml:** Update layout with GPT-4o-mini label, greyed-out key field, Edit Key button, onboarding temperature label, Even Realities colours.  
- **StatusBarView.kt:** Update to Even Realities palette, distinct colours per assistant state (Online=Green, Offline=Orange, Fallback=Yellow).  

#### Optional Future Prep
- **QueryReplayManager.kt (NEW):** Queue offline queries and replay once connection restored.  

---

### 🎨 EVEN REALITIES VISUAL PALETTE
| Element | Colour |
|----------|---------|
| Accent | `#A691F2` |
| Background | `#272033` |
| User Bubble | `#66FFB2` |
| Assistant Bubble | `#2A2335` |
| Offline Accent | `#FFB84D` |
| Text | `#FFFFFF` |

Rounded corners 10dp. Use Material3 flat surface cards.

---

### ✅ EXIT CRITERIA
1. API key locks on validation and clears on reset.  
2. Temperature onboarding text restored correctly.  
3. Offline Assistant provides contextual diagnostics.  
4. Chat headers show correct origin and colour scheme.  
5. BLE DEVICE-origin messages labeled properly.  
6. Theme consistency verified across UI.  
7. No redundant logs in chat.  
8. App stable between offline/online transitions.

---

*End of Phase 3.9.1 — Codex must read, plan, and execute all above changes autonomously before next phase increment.*
# 🧠 Moncchichi Hub — Context Engineering Document  
*(Shared operational memory between ChatGPT, Codex, and the user)*  

---

## ⚙️ ACTIVE DEVELOPMENT CONTEXT  
**CURRENT_PHASE:** Phase 3.9.5 — Assistant Brain (Adaptive Offline Behaviour & UI Consistency)  
**PHASE_OBJECTIVE:**  
Refine the assistant’s offline reliability, UI contrast, connection state feedback, and color consistency.  
Ensure the assistant accurately reflects network status, queues and replays offline messages, and maintains clear visual distinction between online/offline states using the Even Realities **monochrome theme** (black + gray surfaces, white text/icons only).

---

## 🧩 CURRENT MILESTONES  
| # | Milestone | Status | Notes |
|---|------------|--------|-------|
| 1 | Accurate online/offline state indicator | 🟡 Pending | Must switch to “Assistant ⚡ (Offline)” when Wi-Fi is off. No false “GPT-4 Online” state. |
| 2 | Offline diagnostic context reply update | 🟡 Pending | When offline and user asks battery/status, header must change to Offline/🟣 Device. |
| 3 | Offline message queue and replay | 🟡 Pending | Queue ≤ 10 prompts while offline, replay after “I’m back online ✅”. |
| 4 | Offline fallback message frequency | 🟡 Pending | Show “Beep boop offline” only once per downtime period. |
| 5 | Offline announcement and recovery message | 🟡 Pending | Announce offline once; announce online once on reconnect. |
| 6 | Console “Clear + Copy” controls | ✅ Working | Feature stable and retained. |
| 7 | Assistant “thinking…” animation | ✅ Working | 300 ms dot cycle. |
| 8 | Input field text visibility | 🟡 Pending | Text in message box must be white on dark background. |
| 9 | “User is typing…” indicator | 🟡 Pending | Animated hint below chat; disappears when input cleared or sent. |
| 10 | Voice permission removal | ✅ Confirmed | No `RECORD_AUDIO` anywhere. |
| 11 | Greeting routing | 🟡 Pending | “Hi” / “Good morning” must reach GPT, not filtered. |
| 12 | Even Realities color theme update | 🟡 Pending | Apply black + gray theme; white only for text/icons; purple accent for console only. |
| 13 | Temperature slider reset | ✅ Working | Shows default hint on reset. |
| 14 | Build tool fallback rules | 🟢 Defined | `./gradlew lint` allowed if Java 17 missing. |
| 15 | Progress Notes logging | 🟢 Required | Append after each commit. |

---

## 🧠 CODEX IMPLEMENTATION GUIDELINES  
*(Use this section as a permanent framework for how to interpret and act on context.)*

### 1️⃣  **Read and Segment the Context First**  
Before coding, split this document into:  
- **What to build** (active milestones)  
- **What to skip** (future phases or PHASE 4 placeholders)  
- **Design and testing expectations** (how success is measured).  

> Codex must not merge future-phase features unless explicitly stated.

---

### 2️⃣  **Expand on Ambiguity Before Coding**  
If a directive spans multiple modules (e.g., voice removal or network listener), Codex should:  
- Search all packages for references,  
- Flag residual code with `//TODO Phase 4`, or remove if safe.  

---

### 3️⃣  **Even Realities Monochrome Theme Specification**

**Base Principle:**  
The interface follows a **dark monochrome** design — **black and gray** surfaces with **white text only**.  
White is used *solely* for readable text and key icons, never as a panel or button background.

| Element | Color Code | Purpose |
|----------|-------------|----------|
| **Background (global)** | `#000000` | Root UI background |
| **Surface / Card / Panel** | `#1A1A1A` | Cards, buttons, message bubbles, sliders |
| **Divider / Border** | `#2A2A2A` | Section separators |
| **Primary Text / Icons** | `#FFFFFF` | All readable text, chat input, headers |
| **Secondary Text** | `#CCCCCC` | Hints, timestamps, placeholders |
| **Disabled Text / Labels** | `#777777` | Non-interactive UI labels |
| **Accent (Console only)** | `#A691F2` | Optional highlight for console/log areas only |

Typography:  
- **Headers:** 12 sp semi-bold white  
- **Body:** 14 sp white  
- **Timestamps:** 10 sp light gray  

> 🟢 Rule: Only text and essential icons may use white.  
> 🟡 Never use white as a background for cards, buttons, or panels.  
> 🟣 Purple accent limited to console/log sections only.

---

### 4️⃣  **Offline Behaviour Flow & Acceptance Examples**

**Logic Overview:**  
- On offline → announce once: `We are offline. Reverting back to fallback Beep boop!`  
- Show diagnostic summary once per downtime.  
- Queue ≤ 10 prompts for replay.  
- On reconnect → insert “I’m back online ✅” then replay.

**Acceptance Samples:**  
| User Prompt | Expected Response |
|--------------|------------------|
| “Battery status?” | `🔋 Glasses 85 %  💼 Case 92 %  📱 Phone 78 %` |
| “Check Wi-Fi” | `📶 Wi-Fi Offline  ⚙️ API Check  🧠 LLM Fallback` |
| “Hi” / “Good morning” | ChatGPT responds normally (should not be filtered). |

---

### 5️⃣  **Environment Fallback Rules**  
If Java 17 or AGP 8.3+ is unavailable:  

./gradlew lint –no-daemon

Codex must record in Progress Notes:  
`⚙️ Build skipped: Java 17 missing; lint completed successfully.`  

---

### 6️⃣  **Progress Notes Discipline**  
Each patch must append concise entries like:  

[3.9.5-r1] Fixed offline state label and fallback message limit.
[3.9.5-r2] White input text and “User is typing…” animation added.
[3.9.5-r3] Applied black/gray UI and restricted white to text only.

Mark unfinished items as “🟡 Pending Review”.  

---

### 7️⃣  **Output Validation Philosophy**
 • Visually verify online/offline labels and color contrast.  
 • Confirm queued prompt replay and announcement frequency.  
 • Ensure greetings route to LLM.  
 • Retain console and thinking animations.  
 • Do not add voice features until Phase 4.  

---

### 8️⃣  **When Uncertainty Exists**  
If behaviour is unclear:  
 1. Default to stable behaviour.  
 2. Implement minimal safe fix.  
 3. Document reasoning in Progress Notes.  

---

## 🧾 DESIGN SUMMARY  
**Core Purpose:**  
Deliver a stable assistant experience that accurately tracks connectivity, stores and replays offline messages, and uses a clean, dark monochrome Even Realities UI where only text and icons are white.

---

## ✅ EXIT CRITERIA (User Verification)  

| Test Scenario | Expected Behaviour |
|----------------|-------------------|
| Switch off Wi-Fi | Assistant header changes to “⚡ Offline”; fallback message appears once. |
| Reconnect Wi-Fi | “I’m back online ✅” + queued prompts replayed. |
| Ask battery offline | Shows compact icon summary only. |
| Send “Hi” | LLM responds normally (confirmed delivery). |
| Text input | Visible white text on dark background. |
| Typing animation | “User is typing…” appears and disappears correctly. |
| Console clear | ✅ Working. |
| Theme | Only black and gray backgrounds; white for text/icons; purple for console accent only. |
| Permissions | No `RECORD_AUDIO` present. |

---

## 🔮 NEXT PHASE (Preview Only)
 • Phase 4.0 — BLE Core Fusion  
 • Add real telemetry and HUD feedback.  
 • Introduce speech from glasses microphone.  

---

## 📄 PROGRESS NOTES  

(Codex appends here after each patch)  

[3.9.5-r1] Initial Adaptive Offline update – offline state accuracy and UI contrast.  
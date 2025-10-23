# 🧠 Moncchichi Hub — Context Engineering Document  
*(Shared operational memory between ChatGPT, Codex, and the user)*  

---

## ⚙️ ACTIVE DEVELOPMENT CONTEXT  
**CURRENT_PHASE:** Phase 3.9.4 — Assistant Brain (Precision Codex Scope)  
**PHASE_OBJECTIVE:**  
Ensure the assistant, diagnostics, and console systems function stably under all conditions.  
Clarify the level of detail, scope boundaries, design tokens, and testing workflow expected in future prompts so Codex can generate consistent and verifiable code.

---

## 🧩 CURRENT MILESTONES  
| # | Milestone | Status | Notes |
|---|------------|--------|-------|
| 1 | Reconnection replay + “I’m back online ✅” sequence | ✅ Implemented / 🟡 Pending user confirmation | Verify auto-replay of ≤ 10 prompts |
| 2 | Offline diagnostics + compact summary | ✅ Implemented / 🟡 Pending user confirmation | Confirm icon summary + context-aware tips |
| 3 | Console “Clear + Copy” controls | ✅ Implemented / 🟡 Pending user confirmation | Functional buttons + visual feedback |
| 4 | Assistant “thinking…” animation | ✅ Implemented / 🟡 Pending user confirmation | 300 ms dot cycle |
| 5 | UI headers with labels and icons | ✅ Implemented / 🟡 Pending user confirmation | “You:” / “Assistant 🟢 ChatGPT” |
| 6 | Temperature slider reset behavior | ✅ Working | Shows default hint on reset |
| 7 | Color palette application | ✅ Partial (offlineCard pending) | Switch amber to Even Realities tokens |
| 8 | Voice permission removal (scope-wide) | 🟡 Hub done; check core/subtitles | Remove RECORD_AUDIO if found |
| 9 | Build tool fallback rules | 🟢 Defined | Lint allowed if Java 17 missing |
| 10 | Progress Notes logging | 🟢 Required per commit | Append at bottom of this file |

---

## 🧠 CODEX IMPLEMENTATION GUIDELINES  
*(Use this section as a permanent framework for how to interpret and act on context.)*

### 1️⃣  **Read and Segment the Context First**  
Before coding, break the document into:
- **What to build** (active milestones)  
- **What to skip** (future phases or “PHASE4” placeholders)  
- **Design and testing expectations** (how success will be measured).  

> Codex must not merge speculative or future features into the current phase unless explicitly written.

---

### 2️⃣  **Expand on Ambiguity Before Coding**  
If any section references a concept that spans multiple modules (e.g., “remove voice”),  
Codex should automatically:
- Search across all packages for that permission/class,  
- Note where it still exists,  
- And either remove or flag it with a `//TODO` comment marked with the next phase.

---

### 3️⃣  **Always Reference Canonical Design Tokens**  
All colors and fonts must come from:
```xml
@color/er_accent_primary      #A691F2  
@color/er_user_bubble         #5AFFC6  
@color/er_assistant_bubble    #2A2335  
@color/er_timestamp_text      #B0AFC8  
@color/er_background          #1B1530  

Typography:
	•	12sp semi-bold header (@style/ErHeaderText)
	•	14sp body text
	•	10sp timestamp text

If these tokens are missing, Codex should create or reference them in core/res/values/colors.xml and styles.xml.

⸻

4️⃣  Include Acceptance Examples When Logic Is Heuristic

For logic that interprets language (e.g., offline diagnostics classifier), always provide examples:

User Prompt	Expected Topic	Expected Output
“Battery status?”	BATTERY	“🔋 Glasses 85 %  💼 Case 92 %”
“Check Wi-Fi”	INTERNET	“📶 Wi-Fi Offline ⚙️ API Check”
“Firmware update”	FIRMWARE	“🛠 Awaiting firmware data”

This ensures Codex can unit-test or at least simulate expected string results.

⸻

5️⃣  Respect Environment Fallback Rules

If Java 17 toolchain or AGP 8.3+ is unavailable:

./gradlew lint --no-daemon

is the valid substitute for assembleDebug.
Codex must append a line in Progress Notes:

⚙️ Build skipped: Java 17 missing; lint completed successfully.


⸻

6️⃣  Progress Notes Discipline

Every new patch or fix must be documented like this:

[3.9.4-r1] Updated offlineCard to Even Realities tokens.  
[3.9.4-r2] Removed RECORD_AUDIO from core manifest.  
[3.9.4-r3] Validated offline classifier with sample prompts.  

If a feature is partially complete, include “🟡 Pending Review”.

⸻

7️⃣  Output Validation Philosophy
	•	Prioritize visual confirmation (UI states and color themes).
	•	Verify functional logic (assistant queue replay, offline responses).
	•	Defer BLE telemetry and voice features to Phase 4+.
	•	Never silently discard existing features—replace or mark deprecated.

⸻

8️⃣  When Uncertainty Exists

If context or logic is ambiguous:
	1.	Assume stability > innovation.
	2.	Implement the smallest safe improvement.
	3.	Document rationale in Progress Notes.

⸻

🧾 DESIGN SUMMARY

Core Purpose:
Provide a seamless, privacy-centric bridge between the Even Realities G1 glasses and an intelligent assistant that:
	•	Functions fully offline with diagnostics narration,
	•	Syncs smoothly online with contextual reasoning, and
	•	Presents a unified, minimal, polished UI.

⸻

✅ EXIT CRITERIA (User Verification)

Test Scenario	Expected Behavior
Disconnect Wi-Fi → Ask “battery”	Short icon summary; no repeated offline paragraph
Reconnect Wi-Fi	“I’m back online ✅” + queued questions replayed
Use “Clear Console”	Console empties immediately
Observe assistant thinking	“• •• •••” loop visible until reply
Check offlineCard colors	Matches Even Realities palette
Review permissions	No RECORD_AUDIO anywhere


⸻

🔮 NEXT PHASE (Preview Only)
	•	Phase 4.0 — BLE Core Fusion
	•	Replace stub BLE data with real telemetry.
	•	Integrate glasses microphone input path.
	•	Add HUD ack and speech loopback.

⸻

📄 PROGRESS NOTES

(Codex appends here after each patch)

[3.9.4-r1] Initial Precision Scope guidelines integrated.  
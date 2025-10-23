# 🧠 Moncchichi Hub — Context Engineering Document
*(Shared operational memory between ChatGPT, Codex, and the user)*

---

## ⚙️ ACTIVE DEVELOPMENT CONTEXT
**CURRENT_PHASE:** Phase 3.9.3 — Assistant Brain (Stabilization Refinement)  
**PHASE_OBJECTIVE:**  
Finalize the Assistant Brain’s stabilization, UI polish, and offline diagnostic intelligence.  
Codex must ensure all logic works as intended and is verifiable by user testing before proceeding to Phase 4 (BLE telemetry and voice input from glasses).

---

## 🧭 ENGINEERING PRINCIPLES (from Codex feedback)

To prevent ambiguity, all future phases **must** adhere to these clarity standards:

| Principle | Description |
|------------|-------------|
| **1. Clear Data Flow** | Every context must describe how events travel through `HubViewModel`, repositories, and fragments. |
| **2. Logical Granularity** | Define small, modular logic steps (e.g., how topic classification happens, how the queue replays). |
| **3. Explicit Boundaries** | Clearly mark what belongs to *current* phase and what is *placeholder for next phase*. |
| **4. Design Token Consistency** | Always link UI color/typography changes to shared constants or XML themes, never hardcode without note. |
| **5. Testability and Environment Notes** | If `assembleDebug` cannot run due to missing Java 17 toolchain, fallback to `gradlew lint` validation and state this explicitly in the patch. |
| **6. State Integrity** | Always describe which ViewModel fields are updated, how they reset, and expected post-conditions. |
| **7. Visual Verification** | When changes affect UI, describe what the user should visually see or confirm manually. |

---

## 🧱 SYSTEM OVERVIEW

### 🔁 Assistant & Diagnostic Flow

User prompt → HubViewModel.assistantAsk() ├─► Online (LlmTool) └─► Offline (OfflineAssistant) OfflineAssistant → DiagnosticRepository.snapshot() → ConsoleInterpreter.quickSummary() → Compact icon summary + contextual reply

### ⚙️ State Model (HubViewModel)
| Property | Description |
|-----------|-------------|
| `assistant.isThinking` | True while waiting for ChatGPT or offline response |
| `assistant.isOffline` | True if fallback mode is active |
| `offlineQueue` | Holds up to 10 unsent prompts while offline |
| `ClearConsole()` | Purges console logs from Room DB |
| `updateAssistantStatus()` | Detects connection change, announces “I’m back online ✅” and replays queued items |

---

## 🧩 IMPLEMENTATION TARGETS

| # | Area | File | Task | Status |
|---|------|------|-------|--------|
| 1 | **Reconnection Flow** | `HubViewModel.kt` | Add online-recovery logic that posts “I’m back online ✅”, replay up to 10 queued prompts. | ✅ Implemented<br>🟡 Pending verification |
| 2 | **Thinking Indicator** | `AssistantFragment.kt` | Animated dots “• •• •••” loop while `assistant.isThinking == true`. | ✅ Implemented<br>🟡 Pending verification |
| 3 | **Offline Diagnostics** | `OfflineAssistant.kt` | Add compact summary + contextual responses using `ConsoleInterpreter.quickSummary()`. Skip repeating “offline” for direct topics (battery, status, general). | ✅ Implemented<br>🟡 Pending verification |
| 4 | **Console Controls** | `ConsoleFragment.kt` / `Memory.kt` | Add “Clear Console” button, link to `MemoryRepository.clearConsole()`. | ✅ Implemented<br>🟡 Pending verification |
| 5 | **UI Formatting** | `AssistantFragment.kt` | Add “You:” / “Assistant:” labels with icons (🟢 ChatGPT / ⚡ Offline / 🟣 Device). | ✅ Implemented<br>🟡 Pending verification |
| 6 | **Color Theme** | `AssistantFragment.kt` + XML | Apply Even Realities palette:<br>• User: #5AFFC6<br>• Assistant: #2A2335<br>• Accent: #A691F2 | ✅ Implemented<br>🟡 Pending verification |
| 7 | **Voice Removal** | `SpeechTool.kt`, `SpeechToolImpl.kt`, `AppLocator.kt`, manifests | Remove voice input classes and RECORD_AUDIO permission. | ✅ Implemented |
| 8 | **Temperature Hint Behavior** | `SettingsFragment.kt` | Default message shown on reset; updates dynamically on slider change. | ✅ Implemented |
| 9 | **Offline Queue Limit** | `HubViewModel.kt` | Queue auto-trim to max 10 prompts, FIFO. | ✅ Implemented |
| 10 | **Java 17 Toolchain Handling** | — | If missing, skip assembleDebug, perform lint validation. | 🟡 Pending build check |

---

### 📝 PROGRESS NOTES

- 2025-10-23: Synced Phase 3.9.3 reconnection replay logic, offline diagnostics summary, and Even Realities palette usage across assistant + console surfaces.
- 2025-10-23: Added offline fallback intro gating in OfflineAssistant to prevent repeated “offline mode” notices within a session.

---

## 🎨 DESIGN TOKENS (REFERENCE)
| Element | Color | Purpose |
|----------|--------|---------|
| Primary Accent | `#A691F2` | Assistant name & icons |
| User Bubble | `#5AFFC6` | User message background |
| Assistant Bubble | `#2A2335` | Assistant response background |
| Timestamp Text | `#B0AFC8` | Subtle gray for timestamps |
| Background | `#1B1530` | App base tone |

Typography:  
- Header (speaker labels): 12sp semi-bold  
- Body text: 14sp regular  
- Timestamp: 10sp light gray  

---

## 🧩 LOGIC EXAMPLES FOR CODEX REFERENCE

### 🧠 Reconnection Flow Logic
```kotlin
val cameOnline = previous.state != AssistantConnState.ONLINE &&
                 updated.state == AssistantConnState.ONLINE

if (cameOnline) {
    val queued = offlineQueue.toList()
    offlineQueue.clear()
    appendChatMessage(MessageSource.ASSISTANT, "I’m back online ✅ and ready to continue.")
    if (queued.isNotEmpty()) {
        appendChatMessage(MessageSource.ASSISTANT, "Here’s what you asked while offline:")
        queued.forEachIndexed { i, q ->
            viewModelScope.launch {
                delay(400L * i)
                post(AppEvent.AssistantAsk(q))
            }
        }
    }
}

⚡ OfflineAssistant Topic Rules

private val directResponseTopics = setOf(
    DiagnosticTopic.BATTERY,
    DiagnosticTopic.STATUS,
    DiagnosticTopic.GENERAL,
    DiagnosticTopic.CONNECTION,
)

🧾 Quick Summary Example

🔋 Glasses 84 %  💼 Case 93 %  📶 Wi-Fi Good  ⚙️ API OK  🧠 LLM Ready


---

✅ EXIT CRITERIA FOR USER TESTING

Area	Verification Task	Expected Outcome

Assistant UI	Check “You:” and “Assistant 🟢 ChatGPT” labels visible	✅ Clean separation, accent color applied
Thinking Indicator	Send a prompt → dots appear while waiting	🟡 Pending verification
Offline Diagnostics	Turn off internet → ask “battery”	🟡 Should show short icon summary without repeating offline paragraph
Auto Reconnect	Disable & re-enable Wi-Fi → see “I’m back online ✅”	🟡 Verify queued questions replay
Console Tools	Use Clear & Copy buttons	✅ Both functional
Theme Colors	Verify colors match Even Realities palette	🟡 Pending confirmation
Temperature Reset	Reset → default hint restored	✅ Working
Build Validation	./gradlew lint passes without Java errors	🟡 To confirm



---

🧪 BUILD & TEST GUIDANCE

Minimum Java version: 17

Command if toolchain unavailable:

./gradlew lint --no-daemon

Report via Codex log:

⚙️ Build skipped: Missing Java 17; lint completed successfully



---

🔮 NEXT PHASE PREVIEW (DO NOT IMPLEMENT YET)

Phase 4.0 → BLE Telemetry + Glasses Microphone

Integrate live battery, MTU, and keepalive packets.

Implement speech wake route through BLE mic.

Route BLE device context into Assistant for “device-only” intelligence.

Mirror assistant replies to G1 HUD.




---

✅ INSTRUCTION FOR CODEX

1. Follow logic flow exactly as described.


2. No placeholder removal unless explicitly marked PHASE4.


3. Reference color constants and existing XML styles, do not inline arbitrary values.


4. If any build fails due to environment (Java toolchain), log fallback validation results.


5. After patching, append a “Progress Notes” summary in this file for traceability.




---

📄 PROGRESS NOTES (to be appended by Codex)

(Codex will append short status like “OfflineAssistant optimized” or “Verified auto reconnect flow” here after each build.)
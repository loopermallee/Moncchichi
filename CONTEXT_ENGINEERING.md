Excellent — below is your fully updated CONTEXT_ENGINEERING.md for Codex to follow.
It incorporates every feedback point you mentioned, and clearly tells Codex which files to modify, what logic to add/remove, and what to leave for Phase 4.
It’s structured exactly how Codex expects: top context summary → implementation goals → file-specific change tasks → QA/exit criteria.

⸻

🧠 Moncchichi Hub — Context Engineering (Updated for Phase 3 Completion)

(Shared operational memory between ChatGPT, Codex, and Moncchichi developers)

⸻

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT_PHASE: Phase 3.9.3 — Assistant Brain (Refined Stabilization)
PHASE_OBJECTIVE:
Clarify logic structures and UI tokens, finalize reconnection flow, offline diagnostics, and queued message replay ahead of Phase 4 BLE telemetry work.

⸻

🎯 HIGH-LEVEL GOALS
	1.	Make assistant conversations more natural and visually clear.
	2.	Add reconnection detection and “I’m back online ✅” announcement.
	3.	Expand offline queue → retain up to 10 pending messages.
	4.	Summarize offline diagnostic replies with icons and compact layout.
	5.	Add colon formatting (“You:” / “Assistant:”) and proper name + origin display.
	6.	Add “typing / thinking…” indicator while awaiting LLM response.
	7.	Add “Clear Console” icon beside Copy button.
	8.	Remove all voice/microphone input features from the app.
	9.	Tweak color palette for Even Realities consistency (user bubble contrast).
	10.	Leave BLE telemetry (real values and keepalive auto-summary) to Phase 4.

⸻

🧩 FILES TO EDIT / CREATE

File	Action	Description
hub/src/main/java/com/loopermallee/moncchichi/hub/viewmodel/HubViewModel.kt	Modify	• Add reconnection listener → trigger “I’m back online ✅” message and replay queued prompts.• Increase offline queue from 3→10.• When assistant goes ONLINE, prepend summary of queued questions.• Add assistantThinking state (Boolean) used by UI for typing indicator.
hub/src/main/java/com/loopermallee/moncchichi/hub/assistant/OfflineAssistant.kt	Modify	• Summarize output (icons + short phrases).• If prompt topic == “device/troubleshoot/battery” → skip repeating “offline” paragraph, respond directly.• Show compact diagnostic line: “🔋 Glasses 85 %  📶 Wi-Fi Good  ⚙️ API OK”.
core/src/main/java/com/loopermallee/moncchichi/core/utils/ConsoleInterpreter.kt	Modify	• Add method quickSummary() returning one-line icon string for OfflineAssistant.• Ensure battery / network / API icons available.
hub/src/main/java/com/loopermallee/moncchichi/hub/ui/assistant/AssistantFragment.kt	Modify	• Add “thinking …” animation bubble when assistantThinking == true.• Add colons after speaker names.• Add icons beside header based on MessageOrigin (🟢 LLM, ⚡ Offline, 🟣 Device).• Add color tweak for user bubble (light Even Realities green variant).
hub/src/main/java/com/loopermallee/moncchichi/hub/ui/ConsoleFragment.kt	Modify	• Add new Clear Console icon/button beside Copy.• On press → vm.clearConsole() → purge MemoryRepository lines + refresh view.
hub/src/main/java/com/loopermallee/moncchichi/hub/viewmodel/AppEvent.kt	Modify	• Add ClearConsole event.
hub/src/main/java/com/loopermallee/moncchichi/hub/viewmodel/HubViewModel.kt	Modify	• Handle ClearConsole by clearing Room logs.
hub/src/main/res/layout/fragment_console.xml	Modify	• Add small trash-bin icon (MaterialIcon delete_outline) beside Copy button.
hub/src/main/res/layout/fragment_assistant.xml	Modify	• Add subtle typing indicator (View or ProgressBar) under assistant chat area.
hub/src/main/java/com/loopermallee/moncchichi/hub/ui/settings/SettingsFragment.kt	Modify	• Ensure temperature hint resets to default on reset.• Remove voice toggle and mic permissions related logic.
AndroidManifest.xml (all modules)	Modify	• Remove RECORD_AUDIO permission.• Remove speech service entries if present.
hub/src/main/java/com/loopermallee/moncchichi/hub/tools/SpeechTool.kt + SpeechToolImpl.kt	Delete / Deprecate	• Remove or stub out speech input functions (no mic).


⸻

🔄 ADDITIONAL IMPLEMENTATION DETAILS

🧠 Assistant Thinking Animation
	•	Introduce assistantThinking: Boolean in AppState.assistant.
	•	When LLM request starts, set to true; when reply arrives, set to false.
	•	UI shows 3 pulsing dots bubble until reply is displayed.

🌐 Auto Reconnect and Queued Messages
	•	Monitor network state via ConnectivityManager.
	•	When state changes to connected AND assistant was offline:
	•	Post system reply: “I’m back online ✅ and ready to continue.”
	•	Re-send up to 10 queued prompts from offlineQueue.
	•	Clear queue after successful replay.

⚡ Offline Diagnostics Refinement
	•	When prompt topic ∈ {battery, charge, troubleshoot, status}:
→ skip intro lines about being offline.
	•	Add icons and compact layout (one line summary then optional detail).
Example:

🔋 Glasses 87 %  |  💼 Case 93 %  |  📶 Wi-Fi Good  |  🧠 LLM Offline


	•	Use ConsoleInterpreter.quickSummary() for this one-liner.

💬 Header Formatting
	•	“You:” and “Assistant:” labels use Even Realities accent #A691F2.
	•	Add icon beside Assistant name based on origin:
	•	🟢 = ChatGPT (LLM)
	•	⚡ = Offline mode
	•	🟣 = Device/Telemetry

🧹 Console Enhancement
	•	Add trash-bin icon (top-right or beside Copy).
	•	vm.clearConsole() clears memory.consoleLines and updates UI.

🎨 Color Tuning
	•	User bubble → #5AFFC6 (soft mint green).
	•	Assistant bubble → #2A2335.
	•	Status bars remain Even Realities violet (A691F2).

🧏🏽‍♀️ Voice Removal
	•	Delete SpeechTool usage and mic UI from Assistant Fragment and Settings.
	•	Remove all startListening() / stopListening() calls.
	•	Remove RECORD_AUDIO and Speech Service intents.

⸻

✅ EXIT CRITERIA FOR PHASE 3 COMPLETION

Area	Requirement	Status after patch
Assistant UI	Shows “You:” / “Assistant:” + icons; correct color contrast	⬜
Thinking Indicator	Visible animation during LLM processing	⬜
Offline Diagnostics	Compact summary with icons; contextual responses	⬜
Auto Reconnect	Assistant announces “I’m back online ✅” + replays ≤ 10 messages	⬜
Console Tools	Copy + Clear buttons both functional	⬜
Voice Layer	Fully removed from app level (code + permissions)	⬜
Color Theme	Matches Even Realities green & violet scheme	⬜
Build Status	./gradlew assembleDebug → BUILD SUCCESSFUL	⬜


⸻

📝 PROGRESS NOTES
- 2025-10-23: Synced Phase 3.9.3 reconnection replay logic, offline diagnostics summary, and Even Realities palette usage across assistant + console surfaces.
- 2025-10-23: Added offline fallback intro gating in OfflineAssistant to prevent repeated “offline mode” notices within a session.

⸻

🔮 PREPARATION FOR PHASE 4 (Voice Wake & BLE Telemetry)

Leave placeholders for:
	•	BLE keepalive metrics to feed ConsoleInterpreter (“❤️ Keepalive → ACK”).
	•	G1 microphone speech capture to be forwarded to Assistant API.
	•	HUD feedback synchronization pipeline.

⸻

✅ Instruction for Codex:
Implement all changes above exactly as specified in the listed files.
Use existing Even Realities color theme (#A691F2 / #2A2335 / #5AFFC6).
Ensure the build compiles without Kotlin errors and passes Phase 3 exit criteria.

⸻

Would you like me to produce the exact diff patch format (like the previous ones) for Codex to apply directly?
That version would include concrete code blocks and insertion lines for each file.
Perfect — here’s your updated Context Engineering Document for the Moncchichi Hub project, now upgraded to Phase 3.9.1.
This version merges the speaker-label system and provenance indicators (ChatGPT / Offline / Device) into the current phase so Codex can implement them directly.

⸻

🧠 Moncchichi Hub — Context Engineering Document

(Shared operational memory between Codex, ChatGPT and the user)

⸻

⚙️ ACTIVE DEVELOPMENT CONTEXT

CURRENT PHASE: Phase 3.9.1 — Assistant Stability & Speaker Provenance
PHASE OBJECTIVE: Finalize stable assistant connectivity and enhance chat-level transparency with speaker headers and origin indicators.

⸻

✅ MILESTONE STATUS

#	Milestone	Status	Notes
1	Lock model to GPT-4o-mini	✅ Done	Stable online connectivity verified after billing activation
2	Fix API-key reset clearing	🟡 Pending	Ensure prefs are fully wiped on Reset
3	Grey key field after save + “Key Saved ✅” label	🟡 Planned	UI state binding required
4	Handle rate-limit errors gracefully	✅ Done	LLM tested OK post-billing
5	Humanize offline diagnostics	🟡 Planned	Rewrite OfflineAssistant responses
6	Offline message acknowledgment	🟡 Planned	Confirm message stored for later reply
7	Speaker labels (“You / Assistant”)	✅ In progress	Messenger-style chat layout
8	Origin indicators (ChatGPT / Offline / Device)	🟢 New	Add visual icon per response
9	Console telemetry parser	✅ Stable	From 3.8 baseline


⸻

🧭 INSTRUCTION TO CODEX

Implement the following updates:

🧩 1️⃣ Speaker Headers and Icons
	•	Each message bubble must display a header above it.

You:
What is the battery made of?

Assistant 🟢 (ChatGPT):
It uses a lithium-polymer cell inside the temple arms.


	•	Header style = Material Typography overline (11–12 sp).
	•	Assistant icons:
	•	🟢 Online → (ChatGPT)
	•	⚡ Offline → (Offline Mode)
	•	🟣 Device Only → (Device Logic 🛠)

🧩 2️⃣ Message Origin Tracking

Add enum to ChatMessage model:

enum class MessageOrigin { LLM, OFFLINE, DEVICE }

Store this in each assistant reply for UI labeling.

🧩 3️⃣ ViewModel Enhancement

In HubViewModel.recordAssistantReply() determine origin:

val origin = when {
  offline -> MessageOrigin.OFFLINE
  text.contains("[Status]") || text.contains("Battery") -> MessageOrigin.DEVICE
  else -> MessageOrigin.LLM
}
appendChatMessage(MessageSource.ASSISTANT, text, origin = origin)

🧩 4️⃣ UI Rendering Logic

In AssistantFragment.renderMessages() read entry.origin and insert header:

val header = when(entry.origin){
  MessageOrigin.LLM -> "Assistant 🟢 (ChatGPT)"
  MessageOrigin.OFFLINE -> "Assistant ⚡ (Offline)"
  MessageOrigin.DEVICE -> "Assistant 🟣 (Device Only) 🛠"
  else -> "Assistant"
}
addSpeakerHeader(header)

🧩 5️⃣ Offline Tone Revision

Revise OfflineAssistant.kt replies to use natural phrasing such as:

“I’m offline right now but I’ve saved your question. I’ll respond once I’m back online.”

⸻

🧩 CODEX TASK ZONE

Issue	Description	Status
Speaker headers missing	Add You/Assistant labels	🟡 Implementing
Response origin untracked	Add MessageOrigin enum	🟡 New
Offline tone robotic	Rewrite responses	🟡 Planned
API-key reset logic	Prefs retain values	🟡 Pending


⸻

Progress Notes

Date	Commit Summary	Status
2025-10-25	Online LLM connectivity verified	✅ Done
2025-10-26	Add speaker headers + origin icons	🟡 In progress
2025-10-26	Offline tone rewrite	🟡 Pending


⸻

🧠 CHATGPT REVIEW ZONE

Enhancement Ideas
	•	Animate headers with fade-in for new messages.
	•	Colour header text per origin (🟢 green / ⚡ amber / 🟣 purple).
	•	In HUD mode, show only icon (e.g., 🟢 ⚡ 🟣) for minimal space.
	•	Cache origin data for diagnostic review later.

⸻

🧾 PHASE SUMMARY

Previous: 3.8 — Clairvoyant Workflow
Current: 3.9.1 — Assistant Stability & Speaker Provenance
Next: 4.0 — BLE Core Fusion (Real telemetry + HUD sync)

⸻

🧱 DESIGN PRINCIPLES
	•	Transparency First: User always knows who is talking and how the reply was generated.
	•	Humanized Offline: Offline responses sound empathetic and natural.
	•	Consistency: All labels and icons follow Even Realities colour theme.

⸻

🧾 PROJECT RECAP

Goal: Provide clear, trustworthy feedback during every assistant interaction — users see who spoke, what mode was used, and where the answer came from.
Core Focus: UI/UX transparency for assistant provenance and stability prior to Phase 4 BLE telemetry integration.

⸻

✅ New Task: Implement speaker headers + origin icons in Phase 3.9.1 patch.
Once verified, phase will graduate to 4.0 (BLE Core Fusion).

⸻

End of Phase 3.9.1 Context Engineering Document — Use before next Codex commit.
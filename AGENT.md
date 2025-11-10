Got it. Below is your final, production-ready AGENT.md, rewritten to include a new post-implementation reflection directive — requiring Codex to list out issues, uncertainties, or potential runtime risks at the end of every patch.

This version stays lean, professional, and aligned with how top engineering teams write for AI-assisted code generation (Builder.io, W&B, Upsun). It’s clean enough to live permanently in your repo.

⸻

AGENT.md — Moncchichi Hub Agent Operations Manual

Version: 1.2
Purpose:
Defines how AI agents such as Codex must interpret, modify, and extend the Moncchichi Hub Android repository — maintaining stability, BLE integrity, and architectural consistency while implementing patches defined in CONTEXT_ENGINEERING.md.

⸻

🧭 1. Mission

Codex’s mission is to:
	•	Implement incremental, buildable patches that stabilize BLE, telemetry, and audio routing for Even Realities G1 glasses.
	•	Follow the phase roadmap in CONTEXT_ENGINEERING.md precisely — never skip or merge phases.
	•	Maintain non-breaking builds and consistent repository logic.
	•	Avoid speculation: implement what’s written, nothing more.

⸻

⚙️ 2. Codebase Overview

Module	Location	Purpose
core/	BLE + low-level protocol stack	G1BleClient, G1BleUartClient, G1Protocols
hub/	Application layer	BLE orchestration, telemetry, dashboard encoder
hub/audio/	Audio routing	MicStreamManager, AudioOutManager, mic fallback
hub/ui/	Interface layer	Developer console, voice & audio settings
hub/telemetry/	Telemetry decoders	BleTelemetryParser, ProtocolMap, G1ReplyParser
context/	Engineering docs	CONTEXT_ENGINEERING.md, AGENT.md

New code must always be placed in the correct package hierarchy — never create new root modules.

⸻

🧩 3. Agent Conduct

3.1 Behavior Rules
	•	Follow the repository’s current architecture (Repository → ViewModel → UI).
	•	Preserve BLE connection flow and ACK reliability at all times.
	•	Prefer extension or delegation over rewriting.
	•	Maintain backward compatibility with textual and binary ACKs.
	•	Use coroutines + flows only (no RxJava, no LiveData).
	•	All new jobs must cancel cleanly when unbind() or disconnect() is called.

3.2 Phase Priority
	1.	Fix BLE ACK and watchdog behavior.
	2.	Re-enable HUD text & clear commands.
	3.	Expand telemetry (battery, uptime, gestures).
	4.	Implement audio routing & UI permissions.
	5.	Integrate MCP memory after BLE stability confirmed.

3.3 Modification Boundaries

Operation	Policy
Edit BLE, telemetry, or HUD logic	✅ Allowed
Modify Gradle or manifest	⚠️ Only if required by context
Add new dependency/library	🚫 Requires human approval
Delete or replace existing BLE logic	🚫 Never
Add test files	✅ If testing new parser or ACK logic
Change app ID, signing, or API keys	🚫 Forbidden


⸻

🧠 4. Context Interpretation Hierarchy

Codex resolves ambiguity in this order:
	1.	CONTEXT_ENGINEERING.md (latest) — definitive instruction.
	2.	Existing Kotlin code — follow idioms and coroutines style.
	3.	Previous diffs or patches — continue existing patterns.
	4.	AGENT.md — behavioral rules and constraints.
	5.	Kotlin/Android conventions — only if unspecified elsewhere.

⸻

⚙️ 5. Commands & Build Workflow

Codex may simulate or rely on these commands:

Command	Function
./gradlew assembleDebug	Verify successful build
./gradlew test	Run unit & parser tests
./gradlew lint	Check style conformity
./gradlew clean	Clean environment before rebuild

Language: Kotlin (Android API 31+)
Testing: JUnit 5
Concurrency: Coroutines with structured cancellation

⸻

🧱 6. Implementation Protocol

Each patch to Codex must follow this structure:

Prompt to Codex:
# CONTEXT SUMMARY
(brief reference to CONTEXT_ENGINEERING.md)
# TASK
(explicit, non-ambiguous implementation goal)

Codex must:
	1.	Parse CONTEXT_ENGINEERING.md and apply the described logic directly.
	2.	Create or modify only the files listed under Files: in the prompt.
	3.	Ensure the patch compiles without missing imports or unhandled types.
	4.	Leave inline comments (// reason: or // TODO:) where assumptions are made.
	5.	Maintain log consistency using standard prefixes [BLE], [AUDIO], [HUD], [TELEMETRY].

⸻

🔐 7. Safety & Permissions
	•	Always check for and request android.permission.RECORD_AUDIO when using the phone mic.
	•	Never modify unrelated permissions or add new manifest entries without context approval.
	•	Never log personally identifiable or sensitive data.
	•	Only diagnostic tags allowed in logs: [BLE], [HUD], [AUDIO], [TELEMETRY], [WATCHDOG].

⸻

🔄 8. Validation & Testing

Codex must ensure:
	•	The app builds successfully after each patch.
	•	BLE pairing is stable for >30 s.
	•	HUD text (0x09, 0x25, 0x26) displays and clears reliably.
	•	Telemetry updates appear live in DeveloperViewModel.
	•	Mic watchdog restores capture within 2 s of inactivity.
	•	No unhandled exceptions or coroutine leaks occur.

⸻

🪶 9. Style Standards
	•	Follow Kotlin best practices (ktlint compatible).
	•	Use KDoc comments for public classes and enums.
	•	Naming convention: PascalCase for classes, camelCase for functions.
	•	Prefer pure functions and immutable data classes.
	•	Never hardcode timing values unless defined in G1Protocols.
	•	When adding logs:

logger("[BLE] ACK received opcode=0x%02X".format(opcode))



⸻

🧰 10. Fallback Policy

If Codex encounters a missing reference or dependency:
	1.	Create a minimal stub with TODO() or default returns.
	2.	Annotate with // TODO: link to section § in CONTEXT_ENGINEERING.md.
	3.	Keep the build functional.
	4.	Never remove existing methods or break public interfaces.

⸻

📋 11. Post-Implementation Reflection (MANDATORY)

At the end of every patch, Codex must append a short section:

# POST-IMPLEMENTATION NOTES
- Known issues:
- Uncertainties:
- Potential risks:

Examples:
	•	Known issue: “Textual ‘OK’ ACKs unverified on firmware v1.6.3.”
	•	Uncertainty: “Mic watchdog threshold may need calibration.”
	•	Risk: “Reconnection delay may exceed 500 ms on dual lens.”

This ensures traceability, helps future debugging, and guides the next phase review.

⸻

✅ 12. Success Definition

A patch is successful when:
	•	✅ It compiles and runs with no regression.
	•	✅ BLE and telemetry remain synchronized between lenses.
	•	✅ HUD commands work end-to-end.
	•	✅ Audio routing responds to UI settings.
	•	✅ All new flows emit stable telemetry.
	•	✅ Post-implementation notes are included and clear.

⸻

🧩 13. Philosophy

“Codex builds what CONTEXT_ENGINEERING.md defines;
AGENT.md defines how Codex behaves.”

Codex is not a summarizer — it is an executor of defined phases.
Every commit should be measurable, reversible, and traceable through this framework.

⸻

End of AGENT.md
All AI code generation within this repository must comply with these operational and behavioral rules.
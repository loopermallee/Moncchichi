🚌 Phase 5.1 — Transport Intelligence (ArriveLah Integration)

Objective:
Enable the Moncchichi Assistant to deliver real-time Singapore bus arrival updates hands-free via voice and HUD display, powered by the ArriveLah API.

⸻

🧠 1️⃣ Purpose & User Goal

Users wearing G1 smart glasses can say:

“Hey Moncchichi, when is the next bus 190?”

and immediately see and hear:

“🚌 Bus 190 arrives in 3 minutes at bus stop 12345.”

Displayed on HUD • Spoken via TTS • Optionally vibrates on arrival.

⸻

🧩 2️⃣ System Architecture Overview

🎤 Voice Input
   ↓
[Wake Word Listener] (Porcupine)
   ↓
[Android SpeechRecognizer]
   ↓
[AssistantBrain → CommandRouter]
   ↓
[ArriveLahHandler → OkHttp/Retrofit]
   ↓
[Response Parser → HUD Formatter]
   ↓
[HudViewModel → Repository.displayTextPage()]
   ↓
[HUD Overlay + TTS Output]


⸻

🧩 3️⃣ New Components to Add

File	Purpose
assistant/intent/TransportIntentRouter.kt	Detects “bus” or “next bus” queries and routes to ArriveLahHandler
assistant/api/ArriveLahHandler.kt	Handles REST calls to ArriveLah API
assistant/model/BusArrival.kt	Data model for parsed results
assistant/utils/LocationHelper.kt	(optional) fetch GPS coordinates for nearest bus stop
hud/ui/HudTransportFormatter.kt	Converts arrival data into readable HUD lines
assistant/scheduler/TransportNotifier.kt	Manages “remind me when it arrives” alerts


⸻

⚙️ 4️⃣ Implementation Details

🧱 A. Intent Routing

// TransportIntentRouter.kt
if (query.contains("bus", ignoreCase = true)) {
    return ArriveLahHandler.handleBusQuery(query)
}

🧱 B. ArriveLah API Handler

object ArriveLahHandler {
    suspend fun handleBusQuery(query: String): String {
        val stopCode = inferBusStopCode() // from GPS or prefs
        val json = URL("https://arrivelah.busrouter.sg/?id=$stopCode").readText()
        val data = JSONObject(json)
        val services = data.getJSONArray("services")
        val first = services.getJSONObject(0)
        val next = first.getJSONObject("next")
        val mins = next.getInt("duration_ms") / 60000
        val busNo = first.getString("no")
        return "Bus $busNo arrives in $mins minutes."
    }
}

🧱 C. HUD Integration

repository.displayTextPage(
    "🚌 Bus 190 → 3 min\nNext: 8 min • 12 min"
)

Display is rendered in HudViewModel.sendHudMessage(text).

🧱 D. Voice Output

ttsTool.speak("Bus 190 arrives in three minutes.")

🧱 E. Optional Notifier

TransportNotifier.scheduleReminder(busNo = "190", minutes = 2) {
    ttsTool.speak("Bus 190 is arriving now.")
    repository.displayTextPage("🚌 Bus 190 → Arriving Now!")
}


⸻

🌐 5️⃣ API Integration Spec

Parameter	Example	Description
Endpoint	https://arrivelah.busrouter.sg/?id=04168	Bus stop code or lat/lng
Response fields	services[].no, services[].next.duration_ms	Bus number & ETA (ms)
HTTP Client	OkHttpClient (shared from AppLocator)	
Timeout	10 s	
Caching	In-memory 5 min TTL for offline fallback	


⸻

💡 6️⃣ Voice Flow Example

User Speech	System Action	Output
“Hey Moncchichi, when’s bus 190?”	Intent router → ArriveLahHandler	“Bus 190 arrives in 3 minutes.” (TTS + HUD)
“Show nearby buses.”	LocationHelper → ArriveLah(lat,lng)	HUD list of closest bus routes
“Remind me when it’s arriving.”	TransportNotifier set delay	Vibration + voice alert before arrival


⸻

📍 7️⃣ Enhancement Plan (Phase 5.2+)

Category	Enhancement	Description
Context Memory	Save frequent stops	“Home Bus Stop: 04168” auto-detected
Offline Mode	Cache previous ETAs	Fallback if no network
Smart Triggers	Predictive ETA	Auto-refresh when near stop
Multi-API	Add LTA DataMall / MyTransport.sg	Redundancy + MRT integration
Personal Routine	“Morning Bus” Schedule	Daily announcement routine
Multilingual TTS	English / Mandarin	Context switch for tourists
Haptics	Vibration alerts	1-min pre-arrival buzz pattern


⸻

🧭 8️⃣ Folder Structure (Additions)

assistant/
 ├─ intent/
 │   └─ TransportIntentRouter.kt
 ├─ api/
 │   └─ ArriveLahHandler.kt
 ├─ model/
 │   └─ BusArrival.kt
 ├─ utils/
 │   └─ LocationHelper.kt
 └─ scheduler/
     └─ TransportNotifier.kt
hud/
 └─ ui/
     └─ HudTransportFormatter.kt


⸻

🔐 9️⃣ Dependencies to Add

In hub/build.gradle.kts:

implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("org.json:json:20231013")

If GPS access needed:

implementation("com.google.android.gms:play-services-location:21.2.0")


⸻

🔎 🔧 10️⃣ Testing Checklist

Test Area	Expected Behavior
Voice Recognition	“When’s bus 190?” → Intent trigger
API Call	JSON parsed without crash
HUD Output	Displays ETA correctly
TTS	Reads ETA audibly
Permission	Network + Location granted
Reminder	Vibrates near arrival time
Offline Mode	Cached response shown


⸻

📘 11️⃣ Context Engineering Summary

🧩 Component Links
	•	Voice Pipeline: Wake-Word → ASR → AssistantBrain → Intent Router
	•	Transport Logic: ArriveLahHandler + TransportNotifier
	•	Display Output: HudViewModel → displayTextPage()
	•	Feedback: TTS + Vibration + HUD

🧪 Validation Objective
	1.	Verify Assistant correctly parses and routes “bus” queries.
	2.	Confirm HUD and TTS output synchronously.
	3.	Observe end-to-end latency < 1.5 s from speech to HUD display.
	4.	Confirm notification reminder triggers at configured ETA.

⸻

💬 12️⃣ User Scenarios in Practice

Scenario	Example Command	Expected Response
Commuting	“Hey Moncchichi, when’s bus 858?”	“Bus 858 arrives in 4 minutes at Block 123.”
Leaving Office	“Remind me when my bus is here.”	Vibration + voice alert before arrival
Exploring	“Nearest bus to Gardens by the Bay.”	Combines Places API + ArriveLah for directions
Rain Check	“Will it rain before my bus?”	Merged weather + ETA report
Tourist	“Show bus to Marina Bay Sands.”	Displays routes and arrival times on HUD


⸻

🚀 13️⃣ Outcome & Next Phase

Phase 5.1 establishes real-time transport intelligence for Moncchichi.
Phase 5.2 will extend this foundation to predictive mobility, integrating:
	•	SG DataMall APIs
	•	Routine scheduler (“Morning Bus”)
	•	Adaptive vibration and TTS cues

Success Criteria
	•	Voice → HUD response < 1.5 s.
	•	90%+ intent accuracy for bus queries.
	•	Successful ETA retrieval from ArriveLah API ≥ 99%.

⸻

✅ End of Memo — Phase 5.1 Transport Intelligence
(Approved for Codex integration and repository implementation.)
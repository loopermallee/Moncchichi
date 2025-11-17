CONTEXT ENGINEERING – EVEN REALITY PARITY (SLEEP / WAKE / HANDSHAKE)

Patch: Sleep/Wake Parity, ACK Parity, LEFT-first Warmup, and Reconnect Behaviour

Purpose of this CE Section

Codex must understand what the EvenReality app does, how your current code differs, and what the patch must correct.

This CE provides:
	•	definitive behavior requirements
	•	references to the authoritative protocol source
	•	explanation of each failure mode
	•	constraints Codex must follow
	•	file locations in your repo where logic must change

Codex must use this CE when modifying files, not summarise it.

⸻

1. Authoritative Protocol Source

Codex must treat the following as the primary BLE reference:

Even Reality G1 BLE Protocol Wiki

https://github.com/JohnRThomas/EvenDemoApp/wiki/BLE-Protocol

This defines:
	•	actual opcodes
	•	actual UART messages
	•	real Sleep/Wake signalling
	•	binary vs text ACK responses
	•	correct notification descriptors
	•	real G1 warmup behavior
	•	handshake message ordering
	•	expected timing between steps

⸻

2. What the Official Even App Actually Does (must be matched)

2.1 Sleep and Wake

EvenReality produces explicit UART messages:

Sleep
Wake

These messages are the only correct signals for sleep/wake.

Codex must ensure:
	•	parse these UART lines
	•	generate a telemetry event
	•	store them in BleTelemetryRepository
	•	drive the connection orchestrator into IdleSleep
	•	suppress reconnects while in IdleSleep
	•	exit IdleSleep only when a “Wake” message arrives

Fold state, case state, RSSI, battery, or heartbeat MUST NOT be used to infer sleep.

⸻

2.2 LEFT-first Warmup

EvenReality always connects lenses in this order:
	1.	Connect LEFT
	2.	Enable notifications for LEFT (in specific order)
	3.	Wait ~150–300ms
	4.	Send HELLO to LEFT
	5.	After LEFT ACKs → connect RIGHT
	6.	Enable notifications for RIGHT
	7.	Send HELLO to RIGHT

Codex must enforce:
	•	RIGHT must NOT connect if LEFT has not completed warmup
	•	No parallel connections
	•	No HELLO before notifications
	•	Maintain delay between steps
	•	Follow correct descriptor enabling order

Codex must reference the wiki section:
“Connection Flow → Warmup”.

⸻

2.3 ACK Handling – Text ACK vs Binary ACK

EvenReality uses two types of ACK:

Text ACK (UART)

OK

Binary ACK (Notify frame)

C9 04

The official app accepts both.

Codex must:
	•	Add dual-mode ACK validator
	•	Accept “OK” as valid
	•	Accept C9 04 as valid
	•	Never mark “OK” as failure

This fixes all:
	•	“PING ERR”
	•	“Handshake ERR”
false negatives.

⸻

2.4 Notification Enable Order

Official app enables GATT notifications in the same predictable order:
	1.	control characteristic
	2.	uart characteristic
	3.	battery characteristic
	4.	state characteristic

With deliberate spacing (~150–200ms).

Your app currently:
	•	does not maintain order
	•	sometimes sends HELLO before descriptors apply
	•	causes GATT “ENABLE_NOTIFICATION_FAILED”

Codex must:
	•	reorder descriptor writes
	•	add small delays
	•	ensure sequencing before HELLO

⸻

2.5 Reconnect Backoff + Sleep Suppression

EvenReality reconnect behavior:
	•	If awake:
	•	retry → 0ms
	•	retry → 500ms
	•	retry → 1000ms
	•	retry → 5000ms
	•	If sleeping:
	•	STOP reconnecting entirely
	•	Wait for “Wake” UART line
	•	Resume LEFT-first warmup

Codex must implement the same:
	•	Backoff logic
	•	No reconnect while sleeping
	•	Wake message restores normal flow

⸻

3. Where These Behaviors Must Be Implemented (Your Repo)

3.1 BLE telemetry parsing

File:
hub/src/main/java/com/loopermallee/moncchichi/hub/data/telemetry/BleTelemetryRepository.kt

Changes required:
	•	add fields:
	•	lastSleepTimestamp
	•	lastWakeTimestamp
	•	isSleeping helper
	•	parse UART “Sleep” / “Wake”
	•	store last seen sleep/wake state
	•	expose a flow or getter for orchestrator and service

⸻

3.2 Connection orchestrator

File:
service/src/main/java/com/loopermallee/moncchichi/bluetooth/DualLensConnectionOrchestrator.kt

Changes required:
	•	add IdleSleep state
	•	stop scanning when IdleSleep is active
	•	wait for telemetry wake event
	•	enforce LEFT-first warmup
	•	block RIGHT until LEFT finishes
	•	implement state transitions:
	•	Awake → IdleSleep
	•	IdleSleep → Awake

⸻

3.3 BLE service (top level)

File:
core/src/main/java/com/loopermallee/moncchichi/bluetooth/MoncchichiBleService.kt

Changes required:
	•	listen for Sleep/Wake telemetry updates
	•	log "[SLEEP]" and "[WAKE]"
	•	cancel heartbeat job during sleep
	•	cancel reconnect scheduling during sleep
	•	resume on wake

⸻

3.4 G1BleClient heartbeat

File:
core/src/main/java/com/loopermallee/moncchichi/bluetooth/G1BleClient.kt

Changes required:
	•	heartbeat coroutine must check isSleeping
	•	if sleeping → do not send PING
	•	ignore ACK timeouts during sleep
	•	restart heartbeat only when awake

⸻

3.5 ACK logic

File:
Likely in:
core/.../G1BleClient.kt
or
core/.../bluetooth/CommandRouter.kt

Changes required:
	•	treat UART “OK” as valid ACK
	•	treat binary C9 04 as valid ACK
	•	replace “binary-only” logic

⸻

3.6 Descriptor enabling order

File:
core/.../G1BleClient.kt
Function area:
	•	enableNotifications()
	•	configureDescriptors()

Changes required:
	•	reorder
	•	insert delay
	•	ensure HELLO only sent after all descriptors enabled

⸻

4. Constraints Codex Must Follow

Codex cannot:
	•	run Gradle
	•	execute app
	•	verify MTU
	•	get logs
	•	test Bluetooth
	•	run the app on a device

Codex can only:
	•	read files
	•	update files
	•	create new files
	•	follow CE instructions
	•	ask you questions when unclear

If code needs testing, Codex must write:

“User must run ./gradlew assembleDebug locally to validate.”

⸻

5. Questions Codex Should Ask If Unsure

Codex must ask when unclear:
	•	“Which file should contain the IdleSleep state enum?”
	•	“Where should the Sleep/Wake event flow be exposed?”
	•	“Is this HELLO message timing acceptable?”
	•	“Should RIGHT wait for LEFT only on wake-up, or on all connections?”
	•	“Where should delays be implemented — in orchestrator or client?”

⸻

6. Definition of Success (Exit Criteria)

The patch is successful when:
	1.	Connection order is: LEFT → delay → RIGHT
	2.	All HELLO messages only run after descriptor enable
	3.	“Sleep” message instantly triggers IdleSleep state
	4.	“Wake” message instantly resumes orchestrator
	5.	Heartbeats stop during sleep
	6.	Reconnect scheduling stops during sleep
	7.	Reconnect uses backoff pattern
	8.	“OK” and “C9 04” both count as ACK
	9.	No reconnect storm appears in logs
	10.	Lens warmup succeeds consistently

⸻

7. Codex Implementation Sequence (What Codex Must Do in Order)
	1.	Add telemetry sleep/wake parsing
	2.	Add IdleSleep state + transitions
	3.	Wire service to send sleep/wake signals to orchestrator
	4.	Gate heartbeats
	5.	Gate reconnects
	6.	Enforce LEFT-first warmup ordering
	7.	Add ACK logic (text + binary)
	8.	Fix descriptor order + delays
	9.	Add logs
	10.	Ask user to run assembleDebug
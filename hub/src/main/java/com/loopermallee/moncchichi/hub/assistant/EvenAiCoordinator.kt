package com.loopermallee.moncchichi.hub.assistant

import android.util.Log
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.core.SendTextPacketBuilder
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.tools.LlmTool
import com.loopermallee.moncchichi.telemetry.G1ReplyParser
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "EvenAiCoordinator"
private const val MAX_AUDIO_DURATION_MS = 30_000L

class EvenAiCoordinator(
    private val service: MoncchichiBleService,
    private val llm: LlmTool,
    private val display: DisplayTool,
    private val scope: CoroutineScope,
) {

    private val sessionMutex = Mutex()
    private var session: Session? = null
    private var audioGuard: Job? = null
    private val textBuilder = SendTextPacketBuilder()

    private data class Session(
        val lens: MoncchichiBleService.Lens,
        val startedAt: Long,
        val audio: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    fun start() {
        scope.launch { collectEvents() }
        scope.launch { collectAudio() }
    }

    private suspend fun collectEvents() {
        service.evenAiEvents.collect { event ->
            when (val type = event.event) {
                is G1ReplyParser.EvenAiEvent.ActivationRequested -> handleActivation(event.lens)
                is G1ReplyParser.EvenAiEvent.RecordingStopped -> finishSession("device-stop")
                is G1ReplyParser.EvenAiEvent.ManualExit -> finishSession("manual-exit")
                is G1ReplyParser.EvenAiEvent.ManualPaging -> log("Manual page tap from ${event.lens}")
                is G1ReplyParser.EvenAiEvent.SilentModeToggle -> log("Silent mode toggle gesture on ${event.lens}")
                is G1ReplyParser.EvenAiEvent.Unknown -> log("Unknown Even AI event 0x%02X".format(Locale.US, type.subcommand))
            }
        }
    }

    private suspend fun collectAudio() {
        service.audioFrames.collect { frame ->
            val active = sessionMutex.withLock { session }
            if (active == null) {
                return@collect
            }
            if (frame.lens != MoncchichiBleService.Lens.RIGHT) {
                return@collect
            }
            sessionMutex.withLock {
                session?.let { current ->
                    current.audio.write(frame.payload)
                    val elapsed = System.currentTimeMillis() - current.startedAt
                    if (elapsed >= MAX_AUDIO_DURATION_MS) {
                        scope.launch { finishSession("timeout") }
                    }
                }
            }
        }
    }

    private suspend fun handleActivation(lens: MoncchichiBleService.Lens) {
        sessionMutex.withLock {
            if (session != null) {
                log("Restarting Even AI session")
            }
            session = Session(lens = MoncchichiBleService.Lens.RIGHT, startedAt = System.currentTimeMillis())
        }
        audioGuard?.cancel()
        audioGuard = scope.launch {
            while (true) {
                val elapsed = sessionMutex.withLock { session }?.let { System.currentTimeMillis() - it.startedAt }
                if (elapsed == null || elapsed >= MAX_AUDIO_DURATION_MS) {
                    finishSession("timeout")
                    break
                }
                kotlinx.coroutines.delay(1_000)
            }
        }
        val micEnabled = service.setMicEnabled(MoncchichiBleService.Lens.RIGHT, true)
        if (!micEnabled) {
            log("Failed to enable microphone on Even AI start")
        } else {
            log("Microphone enabled for Even AI session")
        }
    }

    private suspend fun finishSession(reason: String) {
        val snapshot = sessionMutex.withLock {
            val current = session
            session = null
            current
        } ?: return
        audioGuard?.cancel()
        audioGuard = null
        scope.launch {
            try {
                processSession(snapshot, reason)
            } catch (error: Throwable) {
                log("Even AI session failed: ${error.message}")
                service.setMicEnabled(MoncchichiBleService.Lens.RIGHT, false)
                service.sendEvenAiStop(MoncchichiBleService.Lens.RIGHT)
            }
        }
    }

    private suspend fun processSession(snapshot: Session, reason: String) {
        val audioBytes = snapshot.audio.toByteArray()
        val transcript = transcribeAudio(audioBytes).ifBlank { "(silence detected)" }
        log("Even AI session completed ($reason) with ${audioBytes.size} bytes of audio")

        val reply = llm.answer(transcript, emptyList())
        val assistantLines = reply.text.chunked(42).take(3)
        display.showLines(listOf("You: $transcript") + assistantLines.map { "AI: $it" })

        sendTextToGlasses(reply.text)

        val micDisabled = service.setMicEnabled(MoncchichiBleService.Lens.RIGHT, false)
        if (!micDisabled) {
            log("Failed to disable microphone after Even AI session")
        }
        val stopSent = service.sendEvenAiStop(MoncchichiBleService.Lens.RIGHT)
        if (!stopSent) {
            log("Failed to notify glasses of Even AI completion")
        }
    }

    private suspend fun sendTextToGlasses(text: String) {
        val normalized = text.trim().ifEmpty { "(no response)" }
        val truncated = if (normalized.length > 200) normalized.take(197) + "…" else normalized
        val payload = textBuilder.buildSendText(
            currentPage = 1,
            totalPages = 1,
            screenStatus = SendTextPacketBuilder.DEFAULT_SCREEN_STATUS,
            textBytes = truncated.encodeToByteArray()
        )
        val sent = service.send(payload, MoncchichiBleService.Target.Both)
        if (!sent) {
            log("Failed to send AI response to glasses")
        }
    }

    private suspend fun transcribeAudio(audio: ByteArray): String = withContext(Dispatchers.Default) {
        if (audio.isEmpty()) return@withContext ""
        val preview = audio.take(12).joinToString(separator = " ") { byte -> "%02X".format(byte) }
        "Captured ${audio.size} bytes of audio (preview: $preview)"
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}

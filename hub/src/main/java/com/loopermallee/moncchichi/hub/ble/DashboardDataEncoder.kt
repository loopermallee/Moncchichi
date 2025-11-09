package com.loopermallee.moncchichi.hub.ble

import com.loopermallee.moncchichi.bluetooth.G1Protocols
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DashboardDataEncoder(
    scope: CoroutineScope,
    private val writer: suspend (ByteArray, MoncchichiBleService.Target) -> Boolean,
    private val interPacketDelayMs: Long = DEFAULT_DELAY_MS,
    private val chunkPayloadSize: Int = DEFAULT_CHUNK_PAYLOAD_SIZE,
    private val logger: (String) -> Unit = {},
) {

    data class BurstStatus(
        val active: Boolean = false,
        val sentChunks: Int = 0,
        val totalChunks: Int = 0,
        val lastSequence: Int? = null,
        val result: Result = Result.Idle,
    ) {
        sealed interface Result {
            data object Idle : Result
            data object Success : Result
            data class Failure(val reason: String? = null) : Result
        }
    }

    private data class BurstRequest(
        val subcommand: Int,
        val payload: ByteArray,
        val target: MoncchichiBleService.Target,
    )

    private data class EncodedChunk(val sequence: Int, val bytes: ByteArray)

    private val sequence = AtomicInteger(0)
    private val queue = Channel<BurstRequest>(Channel.UNLIMITED)
    private val _status = MutableStateFlow(BurstStatus())
    val status: StateFlow<BurstStatus> = _status.asStateFlow()

    private val processor: Job = scope.launch { processQueue() }

    fun enqueue(
        subcommand: Int,
        payload: ByteArray,
        target: MoncchichiBleService.Target = MoncchichiBleService.Target.Right,
    ) {
        if (!queue.trySend(BurstRequest(subcommand and 0xFF, payload.copyOf(), target)).isSuccess) {
            logger("Dashboard burst dropped — queue full")
        }
    }

    fun sendText(text: String, lens: Lens = Lens.RIGHT): Boolean {
        val bytes = text.encodeToByteArray()
        val header = byteArrayOf(
            G1Protocols.CMD_HUD_TEXT.toByte(),
            bytes.size.toByte(),
            0x00,
        )
        return runBlocking { writer(header + bytes, targetFor(lens)) }
    }

    fun sendClear(lens: Lens = Lens.RIGHT): Boolean {
        val payload = byteArrayOf(
            G1Protocols.CMD_CLEAR.toByte(),
            0x00,
        )
        return runBlocking { writer(payload, targetFor(lens)) }
    }

    fun estimateChunkCount(payloadSize: Int): Int {
        if (payloadSize <= 0) return 1
        return (payloadSize + chunkPayloadSize - 1) / chunkPayloadSize
    }

    fun dispose() {
        queue.close()
        processor.cancel()
    }

    private suspend fun processQueue() {
        for (request in queue) {
            sendBurst(request)
        }
    }

    private suspend fun sendBurst(request: BurstRequest) {
        val chunks = buildChunks(request.subcommand, request.payload)
        val total = chunks.size
        if (total == 0) {
            return
        }
        _status.value = BurstStatus(active = true, sentChunks = 0, totalChunks = total, lastSequence = null)
        var success = true
        chunks.forEachIndexed { index, chunk ->
            val wrote = writer(chunk.bytes, request.target)
            if (!wrote) {
                success = false
                _status.value = BurstStatus(
                    active = false,
                    sentChunks = index,
                    totalChunks = total,
                    lastSequence = chunk.sequence,
                    result = BurstStatus.Result.Failure("write failed"),
                )
                return
            }
            val nextActive = index + 1 < total
            _status.value = BurstStatus(
                active = nextActive,
                sentChunks = index + 1,
                totalChunks = total,
                lastSequence = chunk.sequence,
                result = BurstStatus.Result.Idle,
            )
            if (nextActive) {
                delay(interPacketDelayMs)
            }
        }
        _status.value = BurstStatus(
            active = false,
            sentChunks = total,
            totalChunks = total,
            lastSequence = chunks.last().sequence,
            result = if (success) BurstStatus.Result.Success else BurstStatus.Result.Failure(null),
        )
    }

    private fun buildChunks(subcommand: Int, payload: ByteArray): List<EncodedChunk> {
        if (payload.isEmpty()) {
            return listOf(buildChunk(subcommand, 0, 1, ByteArray(0)))
        }
        val total = estimateChunkCount(payload.size)
        val chunks = ArrayList<EncodedChunk>(total)
        var offset = 0
        var index = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkPayloadSize, payload.size)
            val slice = payload.copyOfRange(offset, end)
            chunks += buildChunk(subcommand, index, total, slice)
            offset = end
            index += 1
        }
        return chunks
    }

    private fun buildChunk(subcommand: Int, index: Int, total: Int, chunkPayload: ByteArray): EncodedChunk {
        val seq = sequence.getAndUpdate { (it + 1) and 0xFF } and 0xFF
        val length = HEADER_SIZE + chunkPayload.size
        val header = byteArrayOf(
            COMMAND,
            (length and 0xFF).toByte(),
            ((length shr 8) and 0xFF).toByte(),
            seq.toByte(),
            (subcommand and 0xFF).toByte(),
            (total and 0xFF).toByte(),
            (index and 0xFF).toByte(),
        )
        val bytes = ByteArray(header.size + chunkPayload.size)
        header.copyInto(bytes)
        chunkPayload.copyInto(bytes, HEADER_SIZE)
        return EncodedChunk(seq, bytes)
    }

    companion object {
        private const val COMMAND: Byte = 0x06
        private const val HEADER_SIZE: Int = 7
        private const val DEFAULT_CHUNK_PAYLOAD_SIZE: Int = 180
        private const val DEFAULT_DELAY_MS: Long = 30L
    }

    private fun targetFor(lens: Lens): MoncchichiBleService.Target = when (lens) {
        Lens.LEFT -> MoncchichiBleService.Target.Left
        Lens.RIGHT -> MoncchichiBleService.Target.Right
    }
}

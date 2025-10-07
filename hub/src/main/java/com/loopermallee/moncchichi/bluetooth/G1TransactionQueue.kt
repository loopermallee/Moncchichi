package com.loopermallee.moncchichi.bluetooth

import com.loopermallee.moncchichi.MoncchichiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

private const val BLE_QUEUE_TAG = "[BLEQueue]"

class G1TransactionQueue(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 5_000L,
    private val maxRetries: Int = 5,
) {
    private data class QueueItem(
        val label: String,
        val task: suspend () -> Boolean,
        val result: CompletableDeferred<Boolean>,
    )

    private val channel = Channel<QueueItem>(Channel.UNLIMITED)
    private val worker = scope.launch {
        for (item in channel) {
            process(item)
        }
    }

    fun enqueue(label: String, block: suspend () -> Boolean): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        val offered = channel.trySend(QueueItem(label, block, deferred))
        if (offered.isFailure) {
            deferred.completeExceptionally(offered.exceptionOrNull() ?: IllegalStateException("Queue unavailable"))
        }
        return deferred
    }

    suspend fun run(label: String, block: suspend () -> Boolean): Boolean {
        return enqueue(label, block).await()
    }

    private suspend fun process(item: QueueItem) {
        var attempt = 0
        var backoff = 200L
        while (attempt < maxRetries && scope.isActive && !item.result.isCompleted) {
            attempt += 1
            MoncchichiLogger.d(BLE_QUEUE_TAG, "${item.label} attempt $attempt/$maxRetries")
            val success = try {
                withTimeoutOrNull(timeoutMs) {
                    item.task()
                } ?: false
            } catch (t: Throwable) {
                MoncchichiLogger.e(BLE_QUEUE_TAG, "${item.label} crashed", t)
                false
            }
            if (success) {
                MoncchichiLogger.i(BLE_QUEUE_TAG, "${item.label} completed")
                item.result.complete(true)
                return
            }
            MoncchichiLogger.w(BLE_QUEUE_TAG, "${item.label} failed on attempt $attempt")
            if (attempt >= maxRetries) {
                break
            }
            delay(backoff)
            backoff = min(backoff * 2, 5_000L)
        }
        if (!item.result.isCompleted) {
            MoncchichiLogger.e(BLE_QUEUE_TAG, "${item.label} exhausted retries")
            item.result.complete(false)
        }
    }

    fun close() {
        channel.close()
        worker.cancel()
    }
}

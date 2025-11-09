package com.loopermallee.moncchichi.hub.telemetry

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.telemetry.G1ReplyParser
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class BleTelemetryParser(
    private val protocolMap: ProtocolMap = ProtocolMap,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _events = MutableSharedFlow<TelemetryEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<TelemetryEvent> = _events.asSharedFlow()

    data class Result(
        val handlerFound: Boolean,
        val eventsEmitted: Boolean,
    ) {
        val handled: Boolean get() = handlerFound && eventsEmitted
    }

    sealed interface TelemetryEvent {
        val lens: MoncchichiBleService.Lens
        val timestampMs: Long
        val rawFrame: ByteArray

        data class StateEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val flags: G1ReplyParser.StateFlags,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class BatteryEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val batteryPercent: Int?,
            val caseBatteryPercent: Int?,
            val info: G1ReplyParser.BatteryInfo?,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class UptimeEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val uptimeSeconds: Long,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class GestureEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val gesture: G1ReplyParser.GestureEvent,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class AudioPacketEvent(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val sequence: Int?,
            val payload: ByteArray,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent

        data class F5Event(
            override val lens: MoncchichiBleService.Lens,
            override val timestampMs: Long,
            val opcode: Int,
            val subcommand: Int?,
            val vitals: G1ReplyParser.DeviceVitals?,
            val evenAiEvent: G1ReplyParser.EvenAiEvent?,
            override val rawFrame: ByteArray,
        ) : TelemetryEvent
    }

    fun parse(
        lens: MoncchichiBleService.Lens,
        frameBytes: ByteArray,
        timestampMs: Long = clock(),
        onEvent: ((TelemetryEvent) -> Unit)? = null,
    ): Result {
        if (frameBytes.isEmpty()) {
            return Result(handlerFound = false, eventsEmitted = false)
        }
        val frame = G1ReplyParser.decodeFrame(frameBytes)
            ?: return Result(handlerFound = false, eventsEmitted = false)
        val subOpcode = frame.payload.firstOrNull()?.toUnsignedInt()
        val handler = protocolMap.handlerFor(frame.opcode, subOpcode)
            ?: return Result(handlerFound = false, eventsEmitted = false)
        val events = handler.invoke(lens, frame, timestampMs)
        events.forEach { event ->
            onEvent?.invoke(event)
            _events.tryEmit(event)
        }
        return Result(handlerFound = true, eventsEmitted = events.isNotEmpty())
    }

    private fun Byte.toUnsignedInt(): Int = toUByte().toInt()
}

package com.loopermallee.moncchichi.hub.telemetry

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.telemetry.G1ReplyParser
import kotlin.collections.buildList

internal typealias ProtocolHandler = (
    MoncchichiBleService.Lens,
    G1ReplyParser.NotifyFrame,
    Long,
) -> List<BleTelemetryParser.TelemetryEvent>

data class ProtocolKey(val opcode: Int, val subOpcode: Int? = null)

object ProtocolMap {
    private val handlers: Map<ProtocolKey, ProtocolHandler> = mapOf(
        ProtocolKey(0x2B) to { lens, frame, timestamp ->
            val state = G1ReplyParser.parseState(frame) ?: return@to emptyList()
            listOf(
                BleTelemetryParser.TelemetryEvent.StateEvent(
                    lens = lens,
                    timestampMs = timestamp,
                    opcode = frame.opcode,
                    flags = state,
                    rawFrame = frame.raw.copyOf(),
                ),
            )
        },
        ProtocolKey(0x2C, 0x01) to { lens, frame, timestamp ->
            val battery = G1ReplyParser.parseBattery(frame)
            val info = if (frame.payload.isNotEmpty()) {
                G1ReplyParser.parseBattery(frame.payload)
            } else {
                null
            }
            if (battery == null && info == null) {
                return@to emptyList()
            }
            listOf(
                BleTelemetryParser.TelemetryEvent.BatteryEvent(
                    lens = lens,
                    timestampMs = timestamp,
                    opcode = frame.opcode,
                    batteryPercent = battery?.batteryPercent,
                    caseBatteryPercent = battery?.caseBatteryPercent,
                    info = info,
                    rawFrame = frame.raw.copyOf(),
                ),
            )
        },
        ProtocolKey(0x37) to { lens, frame, timestamp ->
            val uptime = G1ReplyParser.parseUptime(frame) ?: return@to emptyList()
            val parsed = if (frame.payload.isNotEmpty()) {
                G1ReplyParser.parseUptime(frame.payload).toLong()
            } else {
                uptime
            }
            listOf(
                BleTelemetryParser.TelemetryEvent.UptimeEvent(
                    lens = lens,
                    timestampMs = timestamp,
                    opcode = frame.opcode,
                    uptimeSeconds = parsed,
                    rawFrame = frame.raw.copyOf(),
                ),
            )
        },
        ProtocolKey(0xF1) to { lens, frame, timestamp ->
            val packet = G1ReplyParser.parseAudio(frame) ?: return@to emptyList()
            listOf(
                BleTelemetryParser.TelemetryEvent.AudioPacketEvent(
                    lens = lens,
                    timestampMs = timestamp,
                    opcode = frame.opcode,
                    sequence = packet.sequence,
                    payload = packet.payload.copyOf(),
                    rawFrame = frame.raw.copyOf(),
                ),
            )
        },
        ProtocolKey(0xF5) to { lens, frame, timestamp ->
            val parsed = G1ReplyParser.parseF5Payload(frame)
            val gesture = if (frame.payload.isNotEmpty()) {
                G1ReplyParser.parseGesture(frame.payload)
            } else {
                null
            }
            if (parsed == null && gesture == null) {
                return@to emptyList()
            }
            buildList {
                parsed?.let {
                    if (it.vitals != null || it.evenAiEvent != null) {
                        add(
                            BleTelemetryParser.TelemetryEvent.F5Event(
                                lens = lens,
                                timestampMs = timestamp,
                                opcode = frame.opcode,
                                subcommand = it.subcommand,
                                vitals = it.vitals,
                                evenAiEvent = it.evenAiEvent,
                                rawFrame = frame.raw.copyOf(),
                            ),
                        )
                    }
                }
                gesture?.let {
                    add(
                        BleTelemetryParser.TelemetryEvent.GestureEvent(
                            lens = lens,
                            timestampMs = timestamp,
                            opcode = frame.opcode,
                            gesture = it,
                            rawFrame = frame.raw.copyOf(),
                        ),
                    )
                }
            }
        },
    )

    fun handlerFor(opcode: Int, subOpcode: Int?): ProtocolHandler? {
        val specific = subOpcode?.let { handlers[ProtocolKey(opcode, it)] }
        return specific ?: handlers[ProtocolKey(opcode, null)]
    }
}

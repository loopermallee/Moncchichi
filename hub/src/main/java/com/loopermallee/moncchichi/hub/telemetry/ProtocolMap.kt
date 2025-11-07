package com.loopermallee.moncchichi.hub.telemetry

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.telemetry.G1ReplyParser

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
            val battery = G1ReplyParser.parseBattery(frame) ?: return@to emptyList()
            listOf(
                BleTelemetryParser.TelemetryEvent.BatteryEvent(
                    lens = lens,
                    timestampMs = timestamp,
                    opcode = frame.opcode,
                    batteryPercent = battery.batteryPercent,
                    caseBatteryPercent = battery.caseBatteryPercent,
                    rawFrame = frame.raw.copyOf(),
                ),
            )
        },
        ProtocolKey(0x37) to { lens, frame, timestamp ->
            val uptime = G1ReplyParser.parseUptime(frame) ?: return@to emptyList()
            listOf(
                BleTelemetryParser.TelemetryEvent.UptimeEvent(
                    lens = lens,
                    timestampMs = timestamp,
                    opcode = frame.opcode,
                    uptimeSeconds = uptime,
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
            val parsed = G1ReplyParser.parseF5Payload(frame) ?: return@to emptyList()
            if (parsed.vitals == null && parsed.evenAiEvent == null) {
                return@to emptyList()
            }
            listOf(
                BleTelemetryParser.TelemetryEvent.F5Event(
                    lens = lens,
                    timestampMs = timestamp,
                    opcode = frame.opcode,
                    subcommand = parsed.subcommand,
                    vitals = parsed.vitals,
                    evenAiEvent = parsed.evenAiEvent,
                    rawFrame = frame.raw.copyOf(),
                ),
            )
        },
    )

    fun handlerFor(opcode: Int, subOpcode: Int?): ProtocolHandler? {
        val specific = subOpcode?.let { handlers[ProtocolKey(opcode, it)] }
        return specific ?: handlers[ProtocolKey(opcode, null)]
    }
}

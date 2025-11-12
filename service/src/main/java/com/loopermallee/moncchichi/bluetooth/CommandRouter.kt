package com.loopermallee.moncchichi.bluetooth

import com.loopermallee.moncchichi.bluetooth.G1Protocols.BATT_SUB_DETAIL
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_BATT_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_CASE_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_DISPLAY
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_DISPLAY_GET
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_SYS_INFO
import com.loopermallee.moncchichi.bluetooth.G1Protocols.CMD_WEAR_DETECT
import com.loopermallee.moncchichi.bluetooth.G1Protocols.EVT_GESTURE
import com.loopermallee.moncchichi.bluetooth.G1Protocols.OPC_EVENT
import com.loopermallee.moncchichi.bluetooth.G1Protocols.SYS_SUB_INFO

/**
 * Determines how high-level command frames should be routed across the two G1 lenses.
 */
class CommandRouter {

    enum class Family {
        RIGHT_ONLY,
        BOTH,
        EVENTS,
        UNKNOWN,
    }

    data class RoutingDecision(
        val family: Family,
        val mirror: Boolean,
    )

    fun classify(opcode: Int, subOpcode: Int? = null): RoutingDecision {
        return when (opcode and 0xFF) {
            CMD_DISPLAY, CMD_DISPLAY_GET -> RoutingDecision(Family.RIGHT_ONLY, mirror = false)
            CMD_SYS_INFO -> {
                val mirror = subOpcode?.let { it and 0xFF } == SYS_SUB_INFO
                RoutingDecision(Family.RIGHT_ONLY, mirror = mirror)
            }
            CMD_CASE_GET, CMD_BATT_GET, CMD_WEAR_DETECT -> {
                val mirror = opcode == CMD_BATT_GET && subOpcode?.and(0xFF) == BATT_SUB_DETAIL
                RoutingDecision(Family.BOTH, mirror = mirror)
            }
            OPC_EVENT, EVT_GESTURE -> RoutingDecision(Family.EVENTS, mirror = false)
            else -> RoutingDecision(Family.UNKNOWN, mirror = false)
        }
    }
}

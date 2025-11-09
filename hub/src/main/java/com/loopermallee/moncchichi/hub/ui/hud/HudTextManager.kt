package com.loopermallee.moncchichi.hub.ui.hud

import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens

class HudTextManager(private val service: MoncchichiBleService) {
    fun show(text: String, lens: Lens = Lens.RIGHT) {
        service.sendHudText(lens, text)
    }

    fun clear(lens: Lens = Lens.RIGHT) {
        service.clearDisplay(lens)
    }
}

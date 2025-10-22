package com.loopermallee.moncchichi.hub.tools

interface TtsTool {
    fun speak(text: String)
    fun stop()
    fun isReady(): Boolean
}

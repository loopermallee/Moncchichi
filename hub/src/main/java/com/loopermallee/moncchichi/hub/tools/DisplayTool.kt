package com.loopermallee.moncchichi.hub.tools

interface DisplayTool {
    suspend fun showLines(lines: List<String>)
    suspend fun toast(msg: String)
}

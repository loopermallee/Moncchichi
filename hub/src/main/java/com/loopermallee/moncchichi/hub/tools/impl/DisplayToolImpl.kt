package com.loopermallee.moncchichi.hub.tools.impl

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import com.loopermallee.moncchichi.hub.tools.DisplayTool

class DisplayToolImpl(private val context: Context) : DisplayTool {
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun showLines(lines: List<String>) {
        val message = lines.joinToString("\n")
        Log.d("HubDisplay", message)
    }

    override suspend fun toast(msg: String) {
        mainHandler.post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

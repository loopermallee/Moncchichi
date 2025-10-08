package com.loopermallee.moncchichi.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ServiceDebugHUD(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var view: TextView? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var hideJob: Job? = null

    fun show(message: String, color: Int = Color.YELLOW, autoHide: Boolean = true) {
        if (view == null) {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            view = TextView(context).apply {
                textSize = 14f
                setPadding(28, 14, 28, 14)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(180, 0, 0, 0))
                text = message
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 120
            windowManager?.addView(view, params)
        }
        update(message, color, autoHide)
    }

    fun update(message: String, color: Int, autoHide: Boolean = true) {
        view?.apply {
            text = message
            setBackgroundColor(color)
        }
        scheduleHide(autoHide)
    }

    fun hide() {
        hideJob?.cancel()
        hideJob = null
        view?.let { windowManager?.removeView(it) }
        view = null
    }

    fun destroy() {
        hide()
        scope.cancel()
    }

    private fun scheduleHide(autoHide: Boolean) {
        hideJob?.cancel()
        if (!autoHide) return
        hideJob = scope.launch {
            delay(5_000L)
            hide()
        }
    }
}

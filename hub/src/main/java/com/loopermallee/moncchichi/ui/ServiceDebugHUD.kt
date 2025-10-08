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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ServiceDebugHUD(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var view: TextView? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    fun show(message: String, color: Int = Color.YELLOW) {
        if (view == null) {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            view = TextView(context).apply {
                textSize = 14f
                setPadding(24, 12, 24, 12)
                setBackgroundColor(Color.argb(180, 0, 0, 0))
                setTextColor(Color.WHITE)
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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 100
            windowManager?.addView(view, params)
        }

        view?.apply {
            text = message
            setBackgroundColor(color)
        }

        scope.launch {
            delay(6000)
            hide()
        }
    }

    fun hide() {
        view?.let { windowManager?.removeView(it) }
        view = null
    }
}

package com.loopermallee.moncchichi

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class TestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "✅ Minimal build started successfully"
        setContentView(tv)
    }
}

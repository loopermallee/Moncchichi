package io.texne.g1.basis.service

import android.util.Log

class G1Service {

    init {
        Log.i("G1Service", "Even G1 core service initialized.")
    }

    fun start() {
        Log.i("G1Service", "Starting G1 protocol core…")
    }

    fun stop() {
        Log.i("G1Service", "Stopping G1 protocol core…")
    }
}

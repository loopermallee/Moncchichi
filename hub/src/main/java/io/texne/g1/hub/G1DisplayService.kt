package io.texne.g1.hub

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class G1DisplayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d("G1Service", "onCreate()")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d("G1Service", "onBind()")
        return Binder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("G1Service", "onUnbind()")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d("G1Service", "onDestroy()")
        scope.cancel()
        super.onDestroy()
    }
}

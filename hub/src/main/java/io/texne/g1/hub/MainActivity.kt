package io.texne.g1.hub

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.ui.ApplicationFrame
import io.texne.g1.hub.ui.ApplicationViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var repository: Repository

    private val viewModel: ApplicationViewModel by viewModels()

    private lateinit var statusView: TextView
    private lateinit var rootView: FrameLayout
    private var composeAttached = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    viewModel.onBluetoothStateChanged(true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )

        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.boot_status)
        rootView = findViewById(R.id.root)
        statusView.text = getString(R.string.app_boot_status_rendering)

        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        lifecycleScope.launch {
            statusView.text = getString(R.string.app_boot_status_binding)
            Log.d("Boot", "Attempting to bind service")
            val bound = repository.bindService()
            if (!bound) {
                Log.e("Boot", "Unable to bind service")
                statusView.text = getString(R.string.app_boot_status_failed)
                return@launch
            }

            attachComposeIfNeeded()

            statusView.text = getString(R.string.app_boot_status_waiting)
            val firstState = withTimeoutOrNull(5000) {
                repository.getServiceStateFlow()
                    .filterNotNull()
                    .first()
            }

            if (firstState == null) {
                Log.e("Boot", "Service bind timeout")
                statusView.text = getString(R.string.app_boot_status_timeout)
            } else {
                Log.d("Boot", "Service connected, status=${firstState.status}")
                statusView.text = getString(R.string.app_boot_status_ready)
                statusView.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothReceiver)
        repository.unbindService()
    }

    private fun attachComposeIfNeeded() {
        if (composeAttached) {
            return
        }
        val composeView = ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent { ApplicationFrame() }
        }
        rootView.addView(composeView, 0)
        composeAttached = true
    }
}

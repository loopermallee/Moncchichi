package com.loopermallee.moncchichi.subtitles

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.nabinbhandari.android.permissions.PermissionHandler
import com.nabinbhandari.android.permissions.Permissions
import dagger.hilt.android.AndroidEntryPoint
import com.loopermallee.moncchichi.client.G1ServiceClient
import com.loopermallee.moncchichi.subtitles.model.Repository
import com.loopermallee.moncchichi.subtitles.ui.SubtitlesScreen
import com.loopermallee.moncchichi.subtitles.ui.theme.SubtitlesTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity: ComponentActivity() {
    @Inject
    lateinit var repository: Repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository.bindService(lifecycleScope)

        withPermissions {
            repository.initializeSpeechRecognizer(lifecycleScope)
        }

        enableEdgeToEdge()
        setContent {
            SubtitlesTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(Modifier.padding(innerPadding).fillMaxSize()) {
                        SubtitlesScreen {
                            G1ServiceClient.openHub(this@MainActivity)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.destroySpeechRecognizer()
        repository.unbindService()
    }

    private fun withPermissions(block: () -> Unit) {
        Permissions.check(this, arrayOf(
            Manifest.permission.RECORD_AUDIO,
        ),
        "Please provide the permissions so the app can recognize speech",
        Permissions.Options().setCreateNewTask(true),
        object: PermissionHandler() {
            override fun onGranted() {
                block()
            }
        })
    }
}
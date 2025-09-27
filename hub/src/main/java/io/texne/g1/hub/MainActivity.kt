package io.texne.g1.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import io.texne.g1.hub.model.Repository
import io.texne.g1.hub.ui.ApplicationFrame
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var repository: Repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository.bindService()

        setContent {
            ApplicationFrame()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.unbindService()
    }
}

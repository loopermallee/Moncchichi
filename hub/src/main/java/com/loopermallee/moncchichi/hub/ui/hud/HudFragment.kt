package com.loopermallee.moncchichi.hub.ui.hud

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.ui.theme.G1HubTheme

class HudFragment : Fragment() {

    private val viewModel: HudViewModel by viewModels {
        HudVmFactory(
            appContext = AppLocator.appContext,
            repository = AppLocator.repository,
            prefs = AppLocator.prefs,
            httpClient = AppLocator.httpClient,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                G1HubTheme {
                    val state by viewModel.state.collectAsState()
                    HudScreen(
                        state = state,
                        onMessageChange = viewModel::updateMessageDraft,
                        onTargetChange = viewModel::selectTarget,
                        onSendMessage = viewModel::sendHudMessage,
                        onStopMessage = viewModel::stopHudMessage,
                        onToggleTile = viewModel::toggleTile,
                        onMoveTileUp = viewModel::moveTileUp,
                        onMoveTileDown = viewModel::moveTileDown,
                        onRefreshWeather = viewModel::refreshWeather,
                        onRequestNotificationPermission = ::openNotificationSettings,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshNotificationAccess()
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }
}

package com.loopermallee.moncchichi.hub.ui.hud

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.loopermallee.moncchichi.hub.di.AppLocator
import kotlinx.coroutines.launch

class HudFragment : Fragment() {

    private val requestPostNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.onPostNotificationPermissionResult(granted)
            viewModel.refreshPermissions()
        }
    }

    private val viewModel: HudViewModel by viewModels {
        HudVmFactory(
            requireContext().applicationContext,
            AppLocator.repository,
            AppLocator.prefs,
            AppLocator.httpClient,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state = viewModel.uiState.collectAsStateWithLifecycle().value
                HudScreen(
                    state = state,
                    onSendMessage = viewModel::sendHudMessage,
                    onStopMessage = viewModel::stopHudMessage,
                    onTargetSelected = viewModel::updateSelectedTarget,
                    onToggleTile = viewModel::toggleTile,
                    onMoveTile = viewModel::moveTile,
                    onRefreshWeather = viewModel::refreshWeather,
                    onRequestPostNotifications = ::requestPostNotificationsIfNeeded,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onDismissError = viewModel::clearError,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        requestPostNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }
}

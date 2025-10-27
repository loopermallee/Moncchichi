package com.loopermallee.moncchichi.hub.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.permissions.PermissionRequirement
import com.loopermallee.moncchichi.hub.permissions.PermissionRequirements
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PermissionsFragment : Fragment() {

    private val vm: HubViewModel by activityViewModels {
        HubVmFactory(
            AppLocator.router,
            AppLocator.ble,
            AppLocator.llm,
            AppLocator.display,
            AppLocator.memory,
            AppLocator.diagnostics,
            AppLocator.perms,
            AppLocator.tts,
            AppLocator.prefs,
            AppLocator.telemetry,
        )
    }

    private lateinit var statusView: TextView
    private lateinit var helpView: TextView
    private lateinit var requestButton: Button
    private lateinit var settingsButton: Button
    private lateinit var permissionList: LinearLayout
    private var requirementRows: List<PermissionRow> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_permissions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        statusView = view.findViewById(R.id.text_status)
        helpView = view.findViewById(R.id.text_help)
        requestButton = view.findViewById(R.id.btn_request)
        settingsButton = view.findViewById(R.id.btn_settings)
        permissionList = view.findViewById(R.id.permission_list)

        val inflater = LayoutInflater.from(view.context)
        val requirements = PermissionRequirements.forDevice()
        requirementRows = requirements.map { requirement ->
            val item = inflater.inflate(R.layout.view_permission_status, permissionList, false)
            item.findViewById<TextView>(R.id.permission_title).setText(requirement.titleRes)
            item.findViewById<TextView>(R.id.permission_description).setText(requirement.descriptionRes)
            val status = item.findViewById<TextView>(R.id.permission_state)
            permissionList.addView(item)
            PermissionRow(requirement, status)
        }

        requestButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                vm.post(AppEvent.RequestRequiredPermissions)
            }
            vm.refreshPermissionsState()
        }

        settingsButton.setOnClickListener {
            openAppSettings()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest {
                    updatePermissionsUi()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissionsState()
        updatePermissionsUi()
    }

    private fun updatePermissionsUi() {
        val ctx = context ?: return
        val rows = requirementRows
        val hasRuntime = rows.isNotEmpty()

        val grantedStatuses = rows.map { row ->
            val granted = ContextCompat.checkSelfPermission(ctx, row.requirement.permission) == PackageManager.PERMISSION_GRANTED
            val statusText = if (granted) {
                R.string.permission_status_granted
            } else {
                R.string.permission_status_missing
            }
            row.statusView.setText(statusText)
            val color = if (granted) {
                MaterialColors.getColor(row.statusView, com.google.android.material.R.attr.colorPrimary)
            } else {
                MaterialColors.getColor(row.statusView, com.google.android.material.R.attr.colorError)
            }
            row.statusView.setTextColor(color)
            granted
        }

        val allGranted = grantedStatuses.all { it }
        val summaryRes = when {
            !hasRuntime -> R.string.permissions_summary_none_required
            allGranted -> R.string.permissions_screen_all_granted
            else -> R.string.permissions_screen_missing
        }
        statusView.setText(summaryRes)

        permissionList.isVisible = hasRuntime
        helpView.isVisible = hasRuntime && !allGranted
        if (helpView.isVisible) {
            helpView.setText(R.string.permissions_screen_help)
        }

        requestButton.apply {
            isVisible = hasRuntime
            isEnabled = hasRuntime && !allGranted
            setText(R.string.permissions_request_button)
        }

        settingsButton.apply {
            isVisible = hasRuntime && !allGranted
            setText(R.string.permissions_settings_button)
        }
    }

    private fun openAppSettings() {
        val ctx = context ?: return
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", ctx.packageName, null)
        )
        startActivity(intent)
    }

    private data class PermissionRow(
        val requirement: PermissionRequirement,
        val statusView: TextView,
    )
}

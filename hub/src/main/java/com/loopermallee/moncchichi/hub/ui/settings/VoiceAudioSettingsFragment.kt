package com.loopermallee.moncchichi.hub.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.audio.AudioSink
import com.loopermallee.moncchichi.hub.data.repo.SettingsRepository
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel.PermissionRequest
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import com.loopermallee.moncchichi.core.ui.components.StatusBarView
import com.loopermallee.moncchichi.core.ui.state.AssistantConnInfo
import com.loopermallee.moncchichi.core.ui.state.DeviceConnInfo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class VoiceAudioSettingsFragment : Fragment() {

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

    private var suppressAudibleListener = false
    private var suppressPhoneMicListener = false
    private var pendingEnablePhoneMic = false
    private var suppressSinkSelection = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_voice_audio_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val statusBar = view.findViewById<StatusBarView>(R.id.status_bar)
        val audibleSwitch = view.findViewById<MaterialSwitch>(R.id.switch_audible_responses)
        val phoneMicSwitch = view.findViewById<MaterialSwitch>(R.id.switch_prefer_phone_mic)
        val phoneMicStatus = view.findViewById<TextView>(R.id.text_phone_mic_status)
        val sinkDropdown = view.findViewById<MaterialAutoCompleteTextView>(R.id.dropdown_audio_output)

        val sinkOptions = listOf(
            SinkOption(AudioSink.GLASSES, getString(R.string.voice_audio_sink_auto)),
            SinkOption(AudioSink.WEARABLE, getString(R.string.voice_audio_sink_wearable)),
            SinkOption(AudioSink.PHONE, getString(R.string.voice_audio_sink_phone)),
        )
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, sinkOptions.map { it.label })
        sinkDropdown.setAdapter(adapter)
        sinkDropdown.setOnClickListener { sinkDropdown.showDropDown() }

        sinkDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position in sinkOptions.indices && !suppressSinkSelection) {
                val selected = sinkOptions[position]
                vm.setAudioSink(selected.sink)
            }
        }

        audibleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressAudibleListener) return@setOnCheckedChangeListener
            vm.setAudibleResponsesEnabled(isChecked)
        }

        phoneMicSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressPhoneMicListener) return@setOnCheckedChangeListener
            if (isChecked) {
                if (hasRecordAudioPermission()) {
                    vm.setPreferPhoneMicEnabled(true)
                } else {
                    pendingEnablePhoneMic = true
                    suppressPhoneMicListener = true
                    phoneMicSwitch.isChecked = false
                    suppressPhoneMicListener = false
                    vm.requestAudioPermission()
                    showToast(getString(R.string.voice_audio_permission_request))
                }
            } else {
                vm.setPreferPhoneMicEnabled(false)
            }
        }

        updatePhoneMicStatus(phoneMicStatus)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.assistantConn, vm.deviceConn) { assistant: AssistantConnInfo, device: DeviceConnInfo ->
                    assistant to device
                }.collectLatest { (assistant, device) ->
                    statusBar.render(assistant, device)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SettingsRepository.audibleResponsesFlow.collectLatest { enabled ->
                    if (audibleSwitch.isChecked != enabled) {
                        suppressAudibleListener = true
                        audibleSwitch.isChecked = enabled
                        suppressAudibleListener = false
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SettingsRepository.preferPhoneMicFlow.collectLatest { enabled ->
                    if (phoneMicSwitch.isChecked != enabled) {
                        suppressPhoneMicListener = true
                        phoneMicSwitch.isChecked = enabled
                        suppressPhoneMicListener = false
                    }
                    updatePhoneMicStatus(phoneMicStatus)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SettingsRepository.audioSinkFlow.collectLatest { sink ->
                    val index = sinkOptions.indexOfFirst { it.sink == sink }
                    if (index >= 0) {
                        val label = sinkOptions[index].label
                        if (sinkDropdown.text?.toString() != label) {
                            suppressSinkSelection = true
                            sinkDropdown.setText(label, false)
                            suppressSinkSelection = false
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.permissionRequests.collectLatest { request ->
                    when (request) {
                        PermissionRequest.RecordAudio -> requestPermissions(
                            arrayOf(Manifest.permission.RECORD_AUDIO),
                            REQUEST_RECORD_AUDIO,
                        )
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            vm.onAudioPermissionResult(granted)
            if (pendingEnablePhoneMic) {
                if (granted) {
                    vm.setPreferPhoneMicEnabled(true)
                } else {
                    showToast(getString(R.string.voice_audio_permission_denied))
                }
            }
            pendingEnablePhoneMic = false
            view?.findViewById<TextView>(R.id.text_phone_mic_status)?.let { updatePhoneMicStatus(it) }
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun updatePhoneMicStatus(label: TextView) {
        label.text = if (hasRecordAudioPermission()) {
            getString(R.string.voice_audio_permission_granted)
        } else {
            getString(R.string.voice_audio_permission_needed)
        }
    }

    private fun showToast(message: String) {
        if (!isAdded) return
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private data class SinkOption(val sink: AudioSink, val label: String)

    companion object {
        private const val REQUEST_RECORD_AUDIO = 2001
    }
}

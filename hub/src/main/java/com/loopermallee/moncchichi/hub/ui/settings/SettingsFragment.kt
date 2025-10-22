package com.loopermallee.moncchichi.hub.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private val prefs by lazy { AppLocator.prefs }

    private val vm: HubViewModel by activityViewModels {
        HubVmFactory(
            AppLocator.router,
            AppLocator.ble,
            AppLocator.speech,
            AppLocator.llm,
            AppLocator.display,
            AppLocator.memory,
            AppLocator.perms,
            AppLocator.tts,
            AppLocator.prefs
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.input_api_key)
        val modelInput = view.findViewById<TextInputEditText>(R.id.input_model)
        val tempInput = view.findViewById<TextInputEditText>(R.id.input_temperature)
        val saveButton = view.findViewById<MaterialButton>(R.id.button_save)
        val voiceSwitch = view.findViewById<SwitchMaterial>(R.id.switch_voice)
        val statusText = view.findViewById<TextView>(R.id.text_status)

        apiKeyInput.setText(prefs.getString("openai_api_key", ""))
        modelInput.setText(prefs.getString("openai_model", ""))
        tempInput.setText(prefs.getString("openai_temperature", ""))
        voiceSwitch.isChecked = prefs.getBoolean("assistant_voice_enabled", true)

        saveButton.setOnClickListener {
            prefs.edit()
                .putString("openai_api_key", apiKeyInput.text?.toString()?.trim())
                .putString("openai_model", modelInput.text?.toString()?.trim())
                .putString("openai_temperature", tempInput.text?.toString()?.trim())
                .apply()
            Toast.makeText(requireContext(), "Assistant credentials saved", Toast.LENGTH_SHORT).show()
            vm.logSystemEvent("[Settings] LLM configuration updated")
        }

        voiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!voiceSwitch.isPressed) return@setOnCheckedChangeListener
            viewLifecycleOwner.lifecycleScope.launch {
                vm.post(AppEvent.AssistantVoiceToggle(isChecked))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { state ->
                    val offline = state.assistant.isOffline
                    val speechReady = if (AppLocator.tts.isReady()) "ready" else "initializing"
                    statusText.text = if (offline) {
                        "Offline mode – fallback responses ($speechReady TTS)"
                    } else {
                        "Online mode – $speechReady TTS"
                    }
                    if (voiceSwitch.isChecked != state.assistant.voiceEnabled) {
                        voiceSwitch.isChecked = state.assistant.voiceEnabled
                    }
                }
            }
        }
    }
}

package com.loopermallee.moncchichi.hub.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.slider.Slider
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.loopermallee.moncchichi.core.llm.ApiKeyValidator
import com.loopermallee.moncchichi.core.llm.ModelCatalog
import com.loopermallee.moncchichi.core.ui.components.StatusBarView
import com.loopermallee.moncchichi.core.ui.state.AssistantConnInfo
import com.loopermallee.moncchichi.core.ui.state.DeviceConnInfo
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class SettingsFragment : Fragment() {

    private val prefs by lazy { AppLocator.prefs }

    private val apiKeyValidator by lazy { ApiKeyValidator(OkHttpClient()) }

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
        val statusBar = view.findViewById<StatusBarView>(R.id.status_bar)
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.input_api_key)
        val modelInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.input_model)
        val temperatureLabel = view.findViewById<TextView>(R.id.text_temperature_value)
        val temperatureSlider = view.findViewById<Slider>(R.id.slider_temperature)
        val saveButton = view.findViewById<MaterialButton>(R.id.button_save)
        val resetButton = view.findViewById<MaterialButton>(R.id.button_reset)
        val voiceSwitch = view.findViewById<SwitchMaterial>(R.id.switch_voice)
        val statusText = view.findViewById<TextView>(R.id.text_status)

        apiKeyInput.setText(prefs.getString("openai_api_key", ""))
        val storedModel = prefs.getString("openai_model", null)?.takeIf { it.isNotBlank() }
            ?: ModelCatalog.defaultModel()
        var selectedModel = storedModel
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, ModelCatalog.presets)
        modelInput.setAdapter(adapter)
        modelInput.setText(selectedModel, false)
        modelInput.setOnItemClickListener { _, _, position, _ ->
            selectedModel = ModelCatalog.presets.getOrNull(position) ?: selectedModel
        }

        val initialTemp = prefs.getString("openai_temperature", null)?.toFloatOrNull() ?: 0.5f
        temperatureSlider.value = initialTemp
        temperatureLabel.text = "Temperature: %.1f".format(initialTemp)
        temperatureSlider.addOnChangeListener { _, value, _ ->
            temperatureLabel.text = "Temperature: %.1f".format(value)
        }
        voiceSwitch.isChecked = prefs.getBoolean("assistant_voice_enabled", true)

        saveButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val key = apiKeyInput.text?.toString()?.trim().orEmpty()
                val temperature = temperatureSlider.value
                if (key.isBlank()) {
                    Toast.makeText(requireContext(), "Invalid API Key – please check", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                saveButton.isEnabled = false
                val valid = apiKeyValidator.validate(key)
                if (!valid) {
                    Toast.makeText(requireContext(), "Invalid API Key – please check", Toast.LENGTH_SHORT).show()
                    saveButton.isEnabled = true
                    return@launch
                }
                prefs.edit()
                    .putString("openai_api_key", key)
                    .putString("openai_model", selectedModel)
                    .putString("openai_temperature", temperature.toString())
                    .apply()
                Toast.makeText(requireContext(), "API key validated • Settings saved", Toast.LENGTH_SHORT).show()
                vm.logSystemEvent("[Settings] LLM configuration updated")
                saveButton.isEnabled = true
            }
        }

        resetButton.setOnClickListener {
            val defaultModel = ModelCatalog.defaultModel()
            selectedModel = defaultModel
            modelInput.setText(defaultModel, false)
            temperatureSlider.value = 0.5f
            temperatureLabel.text = "Temperature: 0.5"
            prefs.edit()
                .putString("openai_model", defaultModel)
                .putString("openai_temperature", 0.5f.toString())
                .apply()
            Toast.makeText(requireContext(), "Defaults restored", Toast.LENGTH_SHORT).show()
        }

        voiceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!voiceSwitch.isPressed) return@setOnCheckedChangeListener
            viewLifecycleOwner.lifecycleScope.launch {
                vm.post(AppEvent.AssistantVoiceToggle(isChecked))
            }
        }

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

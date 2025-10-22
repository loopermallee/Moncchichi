package com.loopermallee.moncchichi.hub.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
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
        val statusBar = view.findViewById<StatusBarView>(R.id.status_bar)
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.input_api_key)
        val modelInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.modelDropdown)
        val temperatureLabel = view.findViewById<TextView>(R.id.text_temperature_value)
        val temperatureSlider = view.findViewById<Slider>(R.id.temperatureSlider)
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
        modelInput.setMaxLines(1)
        modelInput.setSingleLine(true)
        modelInput.setText(selectedModel, false)
        modelInput.setOnItemClickListener { _, _, position, _ ->
            selectedModel = ModelCatalog.presets.getOrNull(position) ?: selectedModel
        }

        val initialTemp = prefs.getString("openai_temperature", null)?.toFloatOrNull() ?: 0.5f
        temperatureSlider.value = initialTemp
        temperatureLabel.text = formatTemperature(initialTemp)
        val temperatureHint = view.findViewById<TextView>(R.id.text_temperature_hint)
        temperatureHint.text = describeTemp(initialTemp)
        temperatureSlider.addOnChangeListener { _, value, _ ->
            temperatureLabel.text = formatTemperature(value)
            temperatureHint.text = describeTemp(value)
        }
        voiceSwitch.isChecked = prefs.getBoolean("assistant_voice_enabled", true)

        saveButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val key = apiKeyInput.text?.toString()?.trim().orEmpty()
                val temperature = temperatureSlider.value
                if (key.isBlank()) {
                    showToast("Invalid API key – please check")
                    return@launch
                }
                saveButton.isEnabled = false
                try {
                    val (valid, reason) = try {
                        ApiKeyValidator.validate(key)
                    } catch (se: SecurityException) {
                        showToast("Missing INTERNET permission")
                        return@launch
                    }
                    if (!valid) {
                        showToast(reason ?: "Validation failed ⚠️")
                        return@launch
                    }
                    prefs.edit()
                        .putString("openai_api_key", key)
                        .putString("openai_model", selectedModel)
                        .putString("openai_temperature", temperature.toString())
                        .apply()
                    showToast("API key valid ✅")
                    vm.logSystemEvent("[Settings] LLM configuration updated")
                    vm.refreshAssistantStatus(forceOnline = true)
                } finally {
                    saveButton.isEnabled = true
                }
            }
        }

        resetButton.setOnClickListener {
            val defaultModel = ModelCatalog.defaultModel()
            selectedModel = defaultModel
            modelInput.setText(defaultModel, false)
            temperatureSlider.value = 0.5f
            temperatureLabel.text = formatTemperature(0.5f)
            temperatureHint.text = describeTemp(0.5f)
            prefs.edit()
                .putString("openai_model", defaultModel)
                .putString("openai_temperature", 0.5f.toString())
                .apply()
            showToast("Defaults restored")
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

    private fun formatTemperature(value: Float): String = "Temperature: %.1f".format(value)

    private fun describeTemp(v: Float): String = when {
        v <= 0.2f -> "Very precise, deterministic replies"
        v <= 0.5f -> "Balanced and pragmatic"
        v <= 0.8f -> "More exploratory and varied"
        else -> "Highly creative and unpredictable"
    }

    private fun showToast(message: String) {
        if (!isAdded) return
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

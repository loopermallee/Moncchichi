package com.loopermallee.moncchichi.hub.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.loopermallee.moncchichi.core.llm.ApiKeyValidator
import com.loopermallee.moncchichi.core.llm.ModelCatalog
import com.loopermallee.moncchichi.core.ui.components.StatusBarView
import com.loopermallee.moncchichi.core.ui.state.AssistantConnInfo
import com.loopermallee.moncchichi.core.ui.state.DeviceConnInfo
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private val prefs by lazy { AppLocator.prefs }
    private val defaultTemperatureHint =
        "Temperature controls how creative or precise the assistant’s answers are."

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
        val layoutApiKey = view.findViewById<TextInputLayout>(R.id.layout_api_key)
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.input_api_key)
        val editKeyButton = view.findViewById<MaterialButton>(R.id.button_edit_key)
        val temperatureLabel = view.findViewById<TextView>(R.id.text_temperature_value)
        val temperatureSlider = view.findViewById<Slider>(R.id.temperatureSlider)
        val saveButton = view.findViewById<MaterialButton>(R.id.button_save)
        val resetButton = view.findViewById<MaterialButton>(R.id.button_reset)
        val statusText = view.findViewById<TextView>(R.id.text_status)
        val modelLabel = view.findViewById<TextView>(R.id.text_model_label)
        val temperatureHint = view.findViewById<TextView>(R.id.text_temperature_hint)

        modelLabel.text = ModelCatalog.defaultModel()

        val storedKey = prefs.getString("openai_api_key", "").orEmpty()
        apiKeyInput.setText(storedKey)
        var keyLocked = storedKey.isNotBlank()

        fun updateKeyLockState(locked: Boolean) {
            keyLocked = locked
            layoutApiKey.isEnabled = !locked
            apiKeyInput.isEnabled = !locked
            apiKeyInput.isFocusable = !locked
            apiKeyInput.isFocusableInTouchMode = !locked
            apiKeyInput.isCursorVisible = !locked
            layoutApiKey.helperText = if (locked) "🔒 Saved" else "Paste your OpenAI key"
            editKeyButton.text = if (locked) "Edit Key" else "Cancel Edit"
            if (locked) {
                apiKeyInput.clearFocus()
            } else {
                apiKeyInput.requestFocus()
                apiKeyInput.setSelection(apiKeyInput.text?.length ?: 0)
            }
        }

        updateKeyLockState(keyLocked)

        val initialTemp = prefs.getString("openai_temperature", null)?.toFloatOrNull() ?: 0.5f
        temperatureSlider.value = initialTemp
        temperatureLabel.text = formatTemperature(initialTemp)
        var hasAdjustedTemp = initialTemp != 0.5f
        temperatureHint.text = if (hasAdjustedTemp) describeTemp(initialTemp) else defaultTemperatureHint
        temperatureSlider.addOnChangeListener { _, value, fromUser ->
            temperatureLabel.text = formatTemperature(value)
            if (fromUser && !hasAdjustedTemp) {
                hasAdjustedTemp = true
            }
            temperatureHint.text = if (hasAdjustedTemp) describeTemp(value) else defaultTemperatureHint
        }

        editKeyButton.setOnClickListener {
            if (keyLocked) {
                updateKeyLockState(false)
            } else {
                apiKeyInput.setText(prefs.getString("openai_api_key", ""))
                updateKeyLockState(true)
            }
        }

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
                        .putString("openai_model", ModelCatalog.defaultModel())
                        .putString("openai_temperature", temperature.toString())
                        .apply()
                    showToast("API key valid ✅")
                    updateKeyLockState(true)
                    vm.logSystemEvent("[Settings] LLM configuration updated")
                    vm.refreshAssistantStatus(forceOnline = true)
                } finally {
                    saveButton.isEnabled = true
                }
            }
        }

        resetButton.setOnClickListener {
            temperatureSlider.value = 0.5f
            temperatureLabel.text = formatTemperature(0.5f)
            hasAdjustedTemp = false
            temperatureHint.text = defaultTemperatureHint
            prefs.edit()
                .remove("openai_api_key")
                .putString("openai_model", ModelCatalog.defaultModel())
                .putString("openai_temperature", 0.5f.toString())
                .apply()
            apiKeyInput.setText("")
            updateKeyLockState(false)
            showToast("Defaults restored")
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
                    statusText.text = if (offline) {
                        "Offline mode – queued prompts will auto-send when back online"
                    } else {
                        "Online mode – up to 10 offline prompts are remembered"
                    }
                }
            }
        }
    }

    private fun formatTemperature(value: Float): String = "Temperature: %.1f".format(value)

    private fun describeTemp(v: Float): String = when {
        v <= 0.2f -> "Precise 🧠"
        v <= 0.5f -> "Balanced ⚖️"
        v <= 0.8f -> "Adaptive ✨"
        else -> "Creative 🌈"
    }

    private fun showToast(message: String) {
        if (!isAdded) return
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

package com.loopermallee.moncchichi.hub.ui.teleprompter

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class TeleprompterFragment : Fragment() {

    private val viewModel: TeleprompterViewModel by viewModels {
        TeleprompterVmFactory(
            AppLocator.repository,
            AppLocator.memory,
        )
    }

    private var isUpdatingScript = false
    private var isAutoScrolling = false
    private var latestScroll = 0
    private var isUpdatingMirror = false
    private var isUpdatingHudSync = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_teleprompter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val scriptInput = view.findViewById<TextInputEditText>(R.id.input_script)
        val previewText = view.findViewById<TextView>(R.id.text_preview)
        val previewScroll = view.findViewById<ScrollView>(R.id.scroll_preview)
        val startButton = view.findViewById<MaterialButton>(R.id.button_start)
        val pauseButton = view.findViewById<MaterialButton>(R.id.button_pause)
        val resetButton = view.findViewById<MaterialButton>(R.id.button_reset)
        val speedLabel = view.findViewById<TextView>(R.id.text_speed)
        val speedDown = view.findViewById<MaterialButton>(R.id.button_speed_down)
        val speedUp = view.findViewById<MaterialButton>(R.id.button_speed_up)
        val speedSlider = view.findViewById<Slider>(R.id.slider_speed)
        val mirrorSwitch = view.findViewById<MaterialSwitch>(R.id.switch_mirror)
        val hudCheckbox = view.findViewById<CheckBox>(R.id.checkbox_hud_sync)
        val hudStatus = view.findViewById<TextView>(R.id.text_hud_status)

        scriptInput.doAfterTextChanged { text ->
            if (!isUpdatingScript) {
                viewModel.updateText(text?.toString().orEmpty())
            }
        }

        startButton.setOnClickListener { viewModel.start() }
        pauseButton.setOnClickListener { viewModel.pause() }
        resetButton.setOnClickListener { viewModel.reset() }

        speedDown.setOnClickListener { viewModel.decreaseSpeed() }
        speedUp.setOnClickListener { viewModel.increaseSpeed() }
        speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setSpeed(value)
            }
        }

        mirrorSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingMirror) {
                viewModel.toggleMirror(isChecked)
            }
        }

        hudCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingHudSync) {
                viewModel.toggleHudSync(isChecked)
            }
        }

        previewScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!isAutoScrolling) {
                viewModel.onScrollPositionChanged(scrollY)
            }
            updateVisibleLine(previewText, previewScroll)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        val text = state.text
                        if (scriptInput.text?.toString() != text) {
                            isUpdatingScript = true
                            scriptInput.setText(text)
                            scriptInput.setSelection(text.length)
                            isUpdatingScript = false
                        }
                        if (previewText.text.toString() != text) {
                            previewText.text = text
                            previewText.post { updateVisibleLine(previewText, previewScroll) }
                        }
                        if (mirrorSwitch.isChecked != state.isMirror) {
                            isUpdatingMirror = true
                            mirrorSwitch.isChecked = state.isMirror
                            isUpdatingMirror = false
                        }
                        applyMirror(previewScroll, previewText, state.isMirror)

                        if (hudCheckbox.isChecked != state.isHudSyncEnabled) {
                            isUpdatingHudSync = true
                            hudCheckbox.isChecked = state.isHudSyncEnabled
                            isUpdatingHudSync = false
                        }

                        val speedValue = state.speed.roundToInt()
                        if (speedSlider.value != state.speed) {
                            speedSlider.value = state.speed
                        }
                        speedLabel.text = getString(R.string.teleprompter_speed_value, speedValue)

                        val startLabel = when {
                            state.isPlaying -> getString(R.string.teleprompter_running)
                            latestScroll > 0 -> getString(R.string.teleprompter_resume)
                            else -> getString(R.string.teleprompter_start)
                        }
                        startButton.text = startLabel
                        startButton.isEnabled = !state.isPlaying
                        pauseButton.isEnabled = state.isPlaying

                        val lensCountText = resources.getQuantityString(
                            R.plurals.teleprompter_lens_count,
                            state.connectedLensCount,
                            state.connectedLensCount,
                        )
                        hudStatus.text = getString(
                            R.string.teleprompter_hud_status,
                            state.hudStatusMessage,
                            lensCountText,
                        )
                    }
                }

                launch {
                    viewModel.scrollOffset.collect { offset ->
                        latestScroll = offset
                        if (previewScroll.scrollY != offset) {
                            isAutoScrolling = true
                            previewScroll.smoothScrollTo(0, offset)
                            previewScroll.post { isAutoScrolling = false }
                        }
                        updateVisibleLine(previewText, previewScroll)
                    }
                }
            }
        }
    }

    private fun applyMirror(scrollView: ScrollView, textView: TextView, enabled: Boolean) {
        val scale = if (enabled) -1f else 1f
        if (scrollView.scaleX != scale) {
            scrollView.scaleX = scale
        }
        if (textView.scaleX != scale) {
            textView.scaleX = scale
        }
    }

    private fun updateVisibleLine(previewText: TextView, previewScroll: ScrollView) {
        val layout = previewText.layout ?: return
        if (layout.lineCount == 0) {
            viewModel.updateVisibleLine("")
            return
        }
        val scrollY = previewScroll.scrollY
        val centerY = scrollY + previewScroll.height / 2
        val lineIndex = layout.getLineForVertical(centerY.coerceAtLeast(0))
        val start = layout.getLineStart(lineIndex)
        val end = layout.getLineEnd(lineIndex)
        val text = layout.text?.subSequence(start, end)?.toString()?.trim().orEmpty()
        viewModel.updateVisibleLine(text)
    }
}

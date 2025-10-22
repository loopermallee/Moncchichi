package com.loopermallee.moncchichi.hub.ui.assistant

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.data.db.AssistantMessage
import com.loopermallee.moncchichi.hub.data.db.AssistantRole
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AssistantFragment : Fragment() {

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

    private lateinit var inputField: TextInputEditText
    private lateinit var sendButton: MaterialButton
    private lateinit var listenButton: MaterialButton
    private lateinit var offlineBadge: TextView
    private lateinit var partialView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var messageContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_assistant, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        offlineBadge = view.findViewById(R.id.text_offline)
        partialView = view.findViewById(R.id.text_partial)
        scrollView = view.findViewById(R.id.scroll_conversation)
        messageContainer = view.findViewById(R.id.container_messages)
        inputField = view.findViewById(R.id.input_message)
        sendButton = view.findViewById(R.id.button_send)
        listenButton = view.findViewById(R.id.button_listen)

        sendButton.setOnClickListener {
            val text = inputField.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                viewLifecycleOwner.lifecycleScope.launch { vm.post(AppEvent.AssistantAsk(text)) }
                inputField.setText("")
            }
        }

        listenButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val listening = vm.state.value.assistant.isListening
                val event = if (listening) AppEvent.AssistantStopListening else AppEvent.AssistantStartListening
                vm.post(event)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { state ->
                    renderMessages(state.assistant.history)
                    offlineBadge.isVisible = state.assistant.isOffline
                    partialView.isVisible = !state.assistant.partialTranscript.isNullOrBlank()
                    partialView.text = state.assistant.partialTranscript.orEmpty()
                    sendButton.isEnabled = !state.assistant.isBusy
                    listenButton.isEnabled = !state.assistant.isBusy
                    listenButton.text = if (state.assistant.isListening) "⏹" else "🎙"
                }
            }
        }
    }

    private fun renderMessages(history: List<AssistantMessage>) {
        val signature = history.hashCode()
        if (messageContainer.tag == signature) return

        messageContainer.removeAllViews()
        if (history.isEmpty()) {
            messageContainer.tag = signature
            return
        }

        val horizontal = resources.getDimensionPixelSize(R.dimen.assistant_spacing)
        val vertical = horizontal / 2

        history.forEach { entry ->
            val bubble = TextView(requireContext()).apply {
                text = entry.text
                background = createBubble(entry.role == AssistantRole.USER)
                setPadding(horizontal, vertical, horizontal, vertical)
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = vertical
                gravity = if (entry.role == AssistantRole.USER) Gravity.END else Gravity.START
            }
            messageContainer.addView(bubble, params)
        }
        messageContainer.tag = signature
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun createBubble(isUser: Boolean): GradientDrawable {
        val colorRes = if (isUser) android.R.color.holo_blue_dark else android.R.color.darker_gray
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimension(R.dimen.assistant_bubble_radius)
            setColor(ContextCompat.getColor(requireContext(), colorRes))
        }
    }
}

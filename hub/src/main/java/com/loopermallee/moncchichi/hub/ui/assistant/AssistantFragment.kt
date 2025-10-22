package com.loopermallee.moncchichi.hub.ui.assistant

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import com.loopermallee.moncchichi.core.errors.ErrorAction
import com.loopermallee.moncchichi.core.errors.UiError
import com.loopermallee.moncchichi.core.ui.components.StatusBarView
import com.loopermallee.moncchichi.core.ui.state.AssistantConnInfo
import com.loopermallee.moncchichi.core.ui.state.AssistantConnState
import com.loopermallee.moncchichi.core.ui.state.DeviceConnInfo
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.core.model.ChatMessage
import com.loopermallee.moncchichi.core.model.MessageSource
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

    private lateinit var statusBar: StatusBarView
    private lateinit var inputField: TextInputEditText
    private lateinit var sendButton: MaterialButton
    private lateinit var listenButton: MaterialButton
    private lateinit var offlineCard: View
    private lateinit var errorCard: View
    private lateinit var errorTitle: TextView
    private lateinit var errorDetail: TextView
    private lateinit var errorActionButton: MaterialButton
    private var currentError: UiError? = null
    private lateinit var partialView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var messageContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_assistant, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusBar = view.findViewById(R.id.status_bar)
        offlineCard = view.findViewById(R.id.card_offline)
        errorCard = view.findViewById(R.id.card_error)
        errorTitle = view.findViewById(R.id.text_error_title)
        errorDetail = view.findViewById(R.id.text_error_detail)
        errorActionButton = view.findViewById(R.id.button_error_action)
        partialView = view.findViewById(R.id.text_partial)
        scrollView = view.findViewById(R.id.scroll_conversation)
        messageContainer = view.findViewById(R.id.container_messages)
        inputField = view.findViewById(R.id.input_message)
        sendButton = view.findViewById(R.id.button_send)
        listenButton = view.findViewById(R.id.button_listen)

        errorActionButton.setOnClickListener {
            val error = currentError ?: return@setOnClickListener
            when (error.action) {
                ErrorAction.RETRY -> vm.retryLastAssistant()
                ErrorAction.RECONNECT_DEVICE -> vm.requestDeviceReconnect()
                ErrorAction.OPEN_MIC_PERMS -> {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", requireContext().packageName, null)
                    }
                    startActivity(intent)
                }
                ErrorAction.NONE -> Unit
            }
            vm.dismissUiError()
        }

        sendButton.setOnClickListener {
            val text = inputField.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                vm.dismissUiError()
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
                combine(vm.assistantConn, vm.deviceConn) { assistant: AssistantConnInfo, device: DeviceConnInfo ->
                    assistant to device
                }.collectLatest { (assistant, device) ->
                    statusBar.render(assistant, device)
                    offlineCard.isVisible = assistant.state == AssistantConnState.OFFLINE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { state ->
                    renderMessages(vm.filteredAssistantHistory())
                    partialView.isVisible = !state.assistant.partialTranscript.isNullOrBlank()
                    partialView.text = state.assistant.partialTranscript.orEmpty()
                    sendButton.isEnabled = !state.assistant.isBusy
                    listenButton.isEnabled = !state.assistant.isBusy
                    listenButton.text = if (state.assistant.isListening) "⏹" else "🎙"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiError.collectLatest { error ->
                    currentError = error
                    if (error == null) {
                        errorCard.isVisible = false
                        errorActionButton.isVisible = false
                    } else {
                        errorCard.isVisible = true
                        errorTitle.text = error.title
                        errorDetail.text = error.detail
                        if (error.action == ErrorAction.NONE) {
                            errorActionButton.isVisible = false
                        } else {
                            errorActionButton.isVisible = true
                            errorActionButton.text = when (error.action) {
                                ErrorAction.RETRY -> "Retry"
                                ErrorAction.RECONNECT_DEVICE -> "Reconnect device"
                                ErrorAction.OPEN_MIC_PERMS -> "Open mic settings"
                                ErrorAction.NONE -> ""
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        currentError = null
    }

    private fun renderMessages(history: List<ChatMessage>) {
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
                background = createBubble(entry.source == MessageSource.USER)
                setPadding(horizontal, vertical, horizontal, vertical)
                setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = vertical
                gravity = if (entry.source == MessageSource.USER) Gravity.END else Gravity.START
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

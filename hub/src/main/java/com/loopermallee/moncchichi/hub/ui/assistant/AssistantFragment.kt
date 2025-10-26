package com.loopermallee.moncchichi.hub.ui.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.loopermallee.moncchichi.core.errors.ErrorAction
import com.loopermallee.moncchichi.core.errors.UiError
import com.loopermallee.moncchichi.core.ui.components.StatusBarView
import com.loopermallee.moncchichi.core.ui.state.AssistantConnInfo
import com.loopermallee.moncchichi.core.ui.state.AssistantConnState
import com.loopermallee.moncchichi.core.ui.state.DeviceConnInfo
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AssistantFragment : Fragment() {

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

    private lateinit var statusBar: StatusBarView
    private lateinit var inputField: TextInputEditText
    private lateinit var sendButton: MaterialButton
    private lateinit var offlineCard: View
    private lateinit var errorCard: View
    private lateinit var errorTitle: TextView
    private lateinit var errorDetail: TextView
    private lateinit var errorActionButton: MaterialButton
    private var currentError: UiError? = null
    private lateinit var messageList: RecyclerView
    private lateinit var thinkingContainer: View
    private lateinit var thinkingText: TextView
    private lateinit var typingIndicator: TextView
    private var thinkingJob: Job? = null
    private var typingJob: Job? = null
    private val chatAdapter = ChatMessageAdapter()

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
        messageList = view.findViewById(R.id.list_messages)
        inputField = view.findViewById(R.id.input_message)
        sendButton = view.findViewById(R.id.button_send)
        thinkingContainer = view.findViewById(R.id.container_thinking)
        thinkingText = view.findViewById(R.id.text_thinking)
        thinkingText.background = AppCompatResources.getDrawable(
            requireContext(),
            R.drawable.bg_bubble_assistant_online,
        )
        typingIndicator = view.findViewById(R.id.text_typing_indicator)

        messageList.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            adapter = chatAdapter
            setHasFixedSize(false)
            val spacing = resources.getDimensionPixelSize(R.dimen.assistant_spacing) / 2
            addItemDecoration(ChatBubbleSpacingDecoration(spacing))
            addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
                if (bottom < oldBottom && chatAdapter.itemCount > 0) {
                    post { scrollToPosition(chatAdapter.itemCount - 1) }
                }
            }
        }

        errorActionButton.setOnClickListener {
            val error = currentError ?: return@setOnClickListener
            when (error.action) {
                ErrorAction.RETRY -> vm.retryLastAssistant()
                ErrorAction.RECONNECT_DEVICE -> vm.requestDeviceReconnect()
                ErrorAction.OPEN_MIC_PERMS -> openAppSettings()
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

        inputField.addTextChangedListener { text ->
            setTypingIndicatorVisible(!text.isNullOrBlank())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.assistantConn, vm.deviceConn) { assistant: AssistantConnInfo, device: DeviceConnInfo ->
                    assistant to device
                }.collectLatest { (assistant, device) ->
                    statusBar.render(assistant, device)
                    offlineCard.isVisible =
                        assistant.state == AssistantConnState.OFFLINE || assistant.state == AssistantConnState.FALLBACK
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { state ->
                    val history = vm.filteredAssistantHistory()
                    chatAdapter.submitList(history) {
                        if (history.isNotEmpty()) {
                            messageList.post { messageList.scrollToPosition(history.size - 1) }
                        }
                    }
                    sendButton.isEnabled = !state.assistant.isBusy
                    updateThinkingIndicator(state.assistant.isThinking)
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
                                ErrorAction.OPEN_MIC_PERMS -> "Open settings"
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
        thinkingJob?.cancel()
        thinkingJob = null
        typingJob?.cancel()
        typingJob = null
    }

    private fun updateThinkingIndicator(isThinking: Boolean) {
        thinkingContainer.isVisible = isThinking
        if (isThinking) {
            if (thinkingJob == null) {
                thinkingJob = viewLifecycleOwner.lifecycleScope.launch {
                    val frames = listOf("•", "••", "•••")
                    var index = 0
                    while (isActive) {
                        thinkingText.text = frames[index % frames.size]
                        index++
                        delay(300)
                    }
                }
            }
        } else {
            thinkingJob?.cancel()
            thinkingJob = null
            thinkingText.text = ""
        }
    }

    private fun setTypingIndicatorVisible(show: Boolean) {
        typingIndicator.isVisible = show
        if (show) {
            if (typingJob == null) {
                typingJob = viewLifecycleOwner.lifecycleScope.launch {
                    val frames = listOf(
                        "User is typing",
                        "User is typing.",
                        "User is typing..",
                        "User is typing..."
                    )
                    var index = 0
                    while (isActive) {
                        typingIndicator.text = frames[index % frames.size]
                        index++
                        delay(320)
                    }
                }
            }
        } else {
            typingJob?.cancel()
            typingJob = null
            typingIndicator.text = ""
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }
}

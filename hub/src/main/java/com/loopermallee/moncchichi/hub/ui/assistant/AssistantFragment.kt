package com.loopermallee.moncchichi.hub.ui.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import com.loopermallee.moncchichi.hub.di.AppLocator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TITLE_TEXT_SIZE = 22f
private const val RESPONSE_TEXT_SIZE = 18f

class AssistantFragment : Fragment() {

    private val vm: HubViewModel by activityViewModels {
        HubVmFactory(
            AppLocator.router,
            AppLocator.ble,
            AppLocator.speech,
            AppLocator.llm,
            AppLocator.display,
            AppLocator.memory,
            AppLocator.perms
        )
    }

    private lateinit var inputField: TextInputEditText
    private lateinit var askButton: MaterialButton
    private lateinit var micButton: MaterialButton
    private lateinit var responseView: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val title = TextView(context).apply {
                text = "Assistant"
                textSize = TITLE_TEXT_SIZE
            }
            inputField = TextInputEditText(context).apply { hint = "Ask or command…" }
            askButton = MaterialButton(context).apply { text = "Send" }
            micButton = MaterialButton(context).apply { text = "🎙 Listen" }
            responseView = TextView(context).apply { textSize = RESPONSE_TEXT_SIZE }

            addView(title)
            addView(inputField)
            addView(askButton)
            addView(micButton)
            addView(responseView)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        askButton.setOnClickListener {
            val t = inputField.text?.toString().orEmpty()
            if (t.isNotBlank()) {
                viewLifecycleOwner.lifecycleScope.launch { vm.post(AppEvent.AssistantAsk(t)) }
                inputField.setText("")
            }
        }

        micButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { vm.post(AppEvent.UserSaid("Voice input not implemented")) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { st ->
                    responseView.text = st.assistant.lastResponse.orEmpty()
                    askButton.isEnabled = !st.assistant.isBusy
                    micButton.isEnabled = !st.assistant.isBusy
                }
            }
        }
    }
}

package com.loopermallee.moncchichi.hub.ui.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
                textSize = 22f
            }
            val input = TextInputEditText(context).apply { hint = "Ask or command…" }
            val ask = MaterialButton(context).apply { text = "Send" }
            val mic = MaterialButton(context).apply { text = "🎙 Listen" }
            val resp = TextView(context).apply { textSize = 18f }

            addView(title)
            addView(input)
            addView(ask)
            addView(mic)
            addView(resp)

            ask.setOnClickListener {
                val t = input.text?.toString().orEmpty()
                if (t.isNotBlank()) {
                    lifecycleScope.launch { vm.post(AppEvent.AssistantAsk(t)) }
                    input.setText("")
                }
            }

            mic.setOnClickListener {
                Toast.makeText(context, "Start listening…", Toast.LENGTH_SHORT).show()
            }

            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    vm.state.collectLatest { st ->
                        resp.text = st.assistant.lastResponse.orEmpty()
                        ask.isEnabled = !st.assistant.isBusy
                        mic.isEnabled = !st.assistant.isBusy
                    }
                }
            }
        }
    }
}

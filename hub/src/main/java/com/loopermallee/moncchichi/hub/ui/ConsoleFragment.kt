package com.loopermallee.moncchichi.hub.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.handlers.SystemEventHandler
import com.loopermallee.moncchichi.hub.util.LogFormatter
import com.loopermallee.moncchichi.hub.viewmodel.AppEvent
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ConsoleFragment : Fragment() {
    private lateinit var systemHandler: SystemEventHandler
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

    override fun onAttach(context: Context) {
        super.onAttach(context)
        systemHandler = SystemEventHandler(context.applicationContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_console, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val scroller = view.findViewById<NestedScrollView>(R.id.scroll)
        val text = view.findViewById<TextView>(R.id.text_logs)
        val copyButton = view.findViewById<MaterialButton>(R.id.button_copy_console)
        val clearButton = view.findViewById<MaterialButton>(R.id.button_clear_console)

        copyButton.setOnClickListener {
            val content = text.text?.toString().orEmpty()
            if (content.isBlank()) {
                Toast.makeText(requireContext(), "No logs to copy", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Moncchichi Logs", content))
                Toast.makeText(requireContext(), "Logs copied to clipboard ✅", Toast.LENGTH_SHORT).show()
            }
        }

        clearButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch { vm.post(AppEvent.ClearConsole) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { st ->
                    val ctx = requireContext()
                    val formatted = st.consoleLines.map { LogFormatter.format(ctx, it) }
                    text.text = SpannableStringBuilder().apply {
                        formatted.forEachIndexed { index, span ->
                            append(span)
                            if (index < formatted.lastIndex) {
                                append("\n")
                            }
                        }
                    }
                    scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                systemHandler.events.collectLatest { event ->
                    vm.logSystemEvent(event)
                    Toast.makeText(requireContext(), event, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        systemHandler.register()
    }

    override fun onStop() {
        super.onStop()
        systemHandler.unregister()
    }
}

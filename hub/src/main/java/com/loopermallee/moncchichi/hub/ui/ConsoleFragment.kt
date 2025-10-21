package com.loopermallee.moncchichi.hub.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.viewmodel.HubViewModel
import com.loopermallee.moncchichi.hub.viewmodel.HubVmFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ConsoleFragment : Fragment() {
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
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_console, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val scroller = view.findViewById<NestedScrollView>(R.id.scroll)
        val text = view.findViewById<TextView>(R.id.text_logs)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collectLatest { st ->
                    text.text = st.consoleLines.joinToString("\n")
                    scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }
}

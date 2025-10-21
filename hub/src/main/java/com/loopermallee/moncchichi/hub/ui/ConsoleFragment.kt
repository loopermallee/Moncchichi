package com.loopermallee.moncchichi.hub.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.loopermallee.moncchichi.hub.R

class ConsoleFragment : Fragment() {
    private val vm: SharedBleViewModel by activityViewModels()

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

        vm.logs.observe(viewLifecycleOwner) { lines ->
            text.text = lines.joinToString("\n")
            scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
        }
    }
}

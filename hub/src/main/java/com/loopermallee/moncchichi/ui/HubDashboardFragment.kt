package com.loopermallee.moncchichi.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.loopermallee.moncchichi.hub.databinding.FragmentHubDashboardBinding

class HubDashboardFragment : Fragment() {

    private var _binding: FragmentHubDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHubDashboardBinding.inflate(inflater, container, false)
        binding.textView.text = "Hub Dashboard"
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

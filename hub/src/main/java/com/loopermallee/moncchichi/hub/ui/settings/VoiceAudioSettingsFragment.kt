package com.loopermallee.moncchichi.hub.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.loopermallee.moncchichi.hub.R

/** Stub screen for the upcoming voice and audio settings. */
class VoiceAudioSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_voice_audio_settings, container, false)
    }
}

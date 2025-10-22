package com.loopermallee.moncchichi.hub.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.ui.assistant.AssistantFragment
import com.loopermallee.moncchichi.hub.ui.settings.SettingsFragment

class HubMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLocator.init(applicationContext)
        setContentView(R.layout.activity_hub_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HubFragment())
                .commitNow()
        }

        val bottom = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottom.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.tab_hub -> HubFragment()
                R.id.tab_console -> ConsoleFragment()
                R.id.tab_permissions -> PermissionsFragment()
                R.id.tab_assistant -> AssistantFragment()
                R.id.tab_settings -> SettingsFragment()
                else -> HubFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
            true
        }
    }
}

package com.loopermallee.moncchichi.hub.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.appbar.MaterialToolbar
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.ui.assistant.AssistantFragment
import com.loopermallee.moncchichi.hub.ui.hud.HudFragment
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

        val toolbar = findViewById<MaterialToolbar>(R.id.titleBar)
        toolbar.inflateMenu(R.menu.menu_overflow)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_console -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, ConsoleFragment())
                        .commit()
                    true
                }
                R.id.menu_permissions -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, PermissionsFragment())
                        .commit()
                    true
                }
                R.id.menu_settings -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, SettingsFragment())
                        .commit()
                    true
                }
                R.id.menu_diagnostics -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, DiagnosticsFragment())
                        .commit()
                    true
                }
                R.id.menu_about -> {
                    // TODO: show About dialog
                    true
                }
                R.id.menu_feedback -> {
                    // TODO: open feedback intent
                    true
                }
                else -> false
            }
        }

        val bottom = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottom.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.tab_hub -> HubFragment()
                R.id.tab_hud -> HudFragment()
                R.id.tab_assistant -> AssistantFragment()
                else -> null
            }

            fragment?.let {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, it)
                    .commit()
                true
            } ?: false
        }

        bottom.selectedItemId = R.id.tab_hub
    }
}

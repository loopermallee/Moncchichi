package com.loopermallee.moncchichi.hub.ui

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.ui.HubFragment
import com.loopermallee.moncchichi.hub.ui.assistant.AssistantFragment
import com.loopermallee.moncchichi.hub.ui.developer.DeveloperFragment
import com.loopermallee.moncchichi.hub.ui.settings.SettingsFragment

class HubMainActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView
    private var currentTabId: Int = R.id.tab_dashboard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLocator.init(applicationContext)
        setContentView(R.layout.app_shell)

        toolbar = findViewById(R.id.titleBar)
        bottomNav = findViewById(R.id.bottom_nav)

        toolbar.setOnMenuItemClickListener { item ->
            val activeFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (activeFragment is DeveloperFragment && activeFragment.handleToolbarAction(item.itemId)) {
                true
            } else {
                when (item.itemId) {
                    R.id.menu_about -> {
                        Toast.makeText(this, R.string.menu_about_placeholder, Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.menu_help -> {
                        Toast.makeText(this, R.string.menu_help_placeholder, Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.menu_developer_options -> {
                        bottomNav.selectedItemId = R.id.tab_telemetry
                        true
                    }
                    else -> false
                }
            }
        }

        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == currentTabId) return@setOnItemSelectedListener true
            val fragment = fragmentForTab(item.itemId)
            fragment?.let {
                navigateTo(it, item.itemId)
                true
            } ?: false
        }

        supportFragmentManager.addOnBackStackChangedListener {
            val topFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            val newTabId = tabIdForFragment(topFragment)
            if (newTabId != null) {
                if (newTabId != currentTabId) {
                    currentTabId = newTabId
                    bottomNav.selectedItemId = newTabId
                }
                updateToolbarForTab(newTabId)
            }
        }

        val initialTab = savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.tab_dashboard) ?: R.id.tab_dashboard
        if (savedInstanceState == null) {
            navigateTo(fragmentForTab(initialTab) ?: HubFragment(), initialTab)
        } else {
            currentTabId = initialTab
            updateToolbarForTab(initialTab)
        }
        bottomNav.selectedItemId = initialTab
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, currentTabId)
    }

    private fun navigateTo(fragment: Fragment, @IdRes tabId: Int) {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        supportFragmentManager.executePendingTransactions()
        currentTabId = tabId
        updateToolbarForTab(tabId)
    }

    private fun fragmentForTab(@IdRes tabId: Int): Fragment? = when (tabId) {
        R.id.tab_dashboard -> HubFragment()
        R.id.tab_telemetry -> DeveloperFragment()
        R.id.tab_assistant -> AssistantFragment()
        R.id.tab_settings -> SettingsFragment()
        else -> null
    }

    private fun updateToolbarForTab(@IdRes tabId: Int) {
        toolbar.menu.clear()
        val activeFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (tabId == R.id.tab_telemetry && activeFragment is DeveloperFragment) {
            toolbar.inflateMenu(R.menu.menu_developer_actions)
        }
        toolbar.inflateMenu(R.menu.menu_overflow)

        toolbar.title = when {
            activeFragment is DeveloperFragment -> getString(R.string.developer_console_title)
            tabId == R.id.tab_dashboard -> getString(R.string.hub_title)
            tabId == R.id.tab_telemetry -> getString(R.string.telemetry_title)
            tabId == R.id.tab_assistant -> getString(R.string.assistant_title)
            tabId == R.id.tab_settings -> getString(R.string.settings_title)
            else -> getString(R.string.hub_title)
        }
    }

    private fun tabIdForFragment(fragment: Fragment?): Int? = when (fragment) {
        is HubFragment -> R.id.tab_dashboard
        is DeveloperFragment -> R.id.tab_telemetry
        is AssistantFragment -> R.id.tab_assistant
        is SettingsFragment -> R.id.tab_settings
        else -> null
    }

    companion object {
        private const val KEY_SELECTED_TAB = "hub:selected_tab"
    }
}

package com.loopermallee.moncchichi.hub.ui

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.di.AppLocator
import com.loopermallee.moncchichi.hub.ui.assistant.AssistantFragment
import com.loopermallee.moncchichi.hub.ui.developer.DeveloperFragment
import com.loopermallee.moncchichi.hub.ui.hud.HudFragment
import com.loopermallee.moncchichi.hub.ui.PermissionsFragment
import com.loopermallee.moncchichi.hub.ui.settings.SettingsFragment

class HubMainActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView
    private var currentTabId: Int = R.id.tab_hub

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLocator.init(applicationContext)
        setContentView(R.layout.activity_hub_main)

        toolbar = findViewById(R.id.titleBar)
        bottomNav = findViewById(R.id.bottom_nav)

        toolbar.setOnMenuItemClickListener { item ->
            val activeFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (activeFragment is DeveloperFragment && activeFragment.handleToolbarAction(item.itemId)) {
                true
            } else {
                when (item.itemId) {
                    R.id.menu_permissions -> {
                        showOverlayFragment(PermissionsFragment(), R.string.permissions_screen_title)
                        true
                    }
                    R.id.menu_settings -> {
                        showOverlayFragment(SettingsFragment(), R.string.settings_title)
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
            if (supportFragmentManager.backStackEntryCount == 0) {
                updateToolbarForTab(currentTabId)
                bottomNav.selectedItemId = currentTabId
            }
        }

        val initialTab = savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.tab_hub) ?: R.id.tab_hub
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
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
        supportFragmentManager.executePendingTransactions()
        currentTabId = tabId
        updateToolbarForTab(tabId)
    }

    private fun fragmentForTab(@IdRes tabId: Int): Fragment? = when (tabId) {
        R.id.tab_hub -> HubFragment()
        R.id.tab_hud -> HudFragment()
        R.id.tab_assistant -> AssistantFragment()
        R.id.tab_developer -> DeveloperFragment()
        else -> null
    }

    private fun updateToolbarForTab(@IdRes tabId: Int) {
        toolbar.menu.clear()
        val activeFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (tabId == R.id.tab_developer && activeFragment is DeveloperFragment) {
            toolbar.inflateMenu(R.menu.menu_developer_actions)
        }
        toolbar.inflateMenu(R.menu.menu_overflow)

        toolbar.title = when {
            activeFragment is DeveloperFragment -> getString(R.string.developer_console_title)
            tabId == R.id.tab_hub -> getString(R.string.hub_title)
            tabId == R.id.tab_hud -> getString(R.string.hud_title)
            tabId == R.id.tab_assistant -> getString(R.string.assistant_title)
            else -> getString(R.string.hub_title)
        }
    }

    private fun showOverlayFragment(fragment: Fragment, titleRes: Int) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
        supportFragmentManager.executePendingTransactions()
        toolbar.menu.clear()
        toolbar.inflateMenu(R.menu.menu_overflow)
        toolbar.title = getString(titleRes)
    }

    companion object {
        private const val KEY_SELECTED_TAB = "hub:selected_tab"
    }
}

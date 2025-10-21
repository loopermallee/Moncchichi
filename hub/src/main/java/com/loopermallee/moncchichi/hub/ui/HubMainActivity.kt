package com.loopermallee.moncchichi.hub.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.loopermallee.moncchichi.hub.R

class HubMainActivity : AppCompatActivity() {

    private val vm: SharedBleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                else -> HubFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
            true
        }
    }
}

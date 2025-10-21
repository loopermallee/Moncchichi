package com.loopermallee.moncchichi.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.databinding.ActivityHubMainBinding

class HubMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHubMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHubMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bottomNav: BottomNavigationView = binding.bottomNavigation
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_hub -> switchFragment(HubFragment())
                R.id.nav_console -> switchFragment(G1DataConsoleFragment())
                R.id.nav_permissions -> switchFragment(PermissionsFragment())
            }
            true
        }

        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_hub
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}

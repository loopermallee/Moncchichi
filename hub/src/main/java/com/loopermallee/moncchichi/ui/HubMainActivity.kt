package com.loopermallee.moncchichi.ui

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.loopermallee.moncchichi.hub.R
import com.loopermallee.moncchichi.hub.databinding.ActivityHubMainBinding

class HubMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHubMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityHubMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Log.e("Moncchichi", "UI failed to inflate, loading fallback", e)
            setContentView(
                TextView(this).apply {
                    text = getString(R.string.hub_inflation_error)
                    gravity = Gravity.CENTER
                }
            )
            return
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_hub -> HubDashboardFragment()
                R.id.nav_console -> ConsoleFragment()
                R.id.nav_permissions -> PermissionsFragment()
                else -> null
            }
            fragment?.let {
                loadFragment(it)
                true
            } ?: false
        }

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_hub
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}

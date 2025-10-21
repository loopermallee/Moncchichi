package com.loopermallee.moncchichi.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.loopermallee.moncchichi.databinding.ActivityHubMainBinding

class HubMainActivity : ComponentActivity() {

    private lateinit var binding: ActivityHubMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHubMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnDeviceConsole.setOnClickListener {
            startActivity(Intent(this, G1DataConsoleActivity::class.java))
        }

        binding.btnPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        binding.btnExit.setOnClickListener {
            finishAffinity()
        }
    }
}

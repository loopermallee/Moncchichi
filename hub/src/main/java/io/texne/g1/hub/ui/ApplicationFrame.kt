package io.texne.g1.hub.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.texne.g1.hub.ui.theme.G1HubTheme

private enum class MainTab(val label: String) {
    Device("Device"),
    Display("Display")
}

@Composable
fun ApplicationFrame() {
    G1HubTheme {
        var selectedTab by rememberSaveable { mutableStateOf(MainTab.Device) }
        val tabs = MainTab.values().toList()

        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            icon = { Text(text = tab.label.take(1)) },
                            label = { Text(text = tab.label) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        ) { innerPadding ->
            when (selectedTab) {
                MainTab.Device -> DeviceScreen(modifier = Modifier.padding(innerPadding))
                MainTab.Display -> DisplayScreen(modifier = Modifier.padding(innerPadding))
            }
        }
    }
}

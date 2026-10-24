package com.zobo.fogdrive.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler

private enum class Tab(val label: String, val icon: ImageVector) {
    MAP("지도", Icons.Outlined.Map),
    RECORD("기록", Icons.Outlined.Insights),
    DEX("도감", Icons.Outlined.Explore),
    SETTINGS("설정", Icons.Outlined.Settings),
}

@Composable
fun FogApp(
    viewModel: MainViewModel,
    onStartDemo: () -> Unit,
    onStopDemo: () -> Unit,
    onRequestBackground: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Back from a sub tab returns to the map instead of closing the app.
    BackHandler(enabled = tab != 0) { tab = 0 }

    Surface(modifier = Modifier.fillMaxSize(), color = FogColors.Bg) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight().padding(vertical = 24.dp),
                containerColor = FogColors.Bg,
            ) {
                Spacer(Modifier.height(16.dp))
                Tab.entries.forEachIndexed { i, t ->
                    NavigationRailItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(40.dp)) },
                        label = { Text(t.label) },
                        modifier = Modifier.padding(vertical = 12.dp),
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = FogColors.Bg,
                            indicatorColor = FogColors.Accent,
                            unselectedIconColor = FogColors.TextSub,
                            unselectedTextColor = FogColors.TextSub,
                            selectedTextColor = FogColors.Accent,
                        ),
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                when (Tab.entries[tab]) {
                    Tab.MAP -> MapScreen(viewModel)
                    Tab.RECORD -> RecordScreen(viewModel)
                    Tab.DEX -> DexScreen(viewModel)
                    Tab.SETTINGS -> SettingsScreen(viewModel, onStartDemo, onStopDemo, onRequestBackground)
                }
            }
        }
    }
}

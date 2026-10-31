package com.zobo.burung.ui

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zobo.burung.voice.Sfx
import com.zobo.burung.voice.Sound

private enum class Tab(val label: String, val icon: ImageVector) {
    MAP("지도", Icons.Outlined.Map),
    DEX("도감", Icons.Outlined.Explore),
    EXPEDITION("내 탐험", Icons.Outlined.MilitaryTech),
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
    // Changing screen sounds a glide; played right away so the tap that caused it isn't ticked too.
    fun goTo(t: Int) {
        if (t != tab) Sfx.play(Sound.SCREEN)
        // The dex opens on its default slide, unless a 동 list was left open there.
        if (t != tab && t == Tab.DEX.ordinal && viewModel.dexDistrict.value == null) viewModel.dexProvince.value = null
        tab = t
    }
    val vehicle by viewModel.vehicle.collectAsState()
    // Back from a sub tab returns to the map instead of closing the app.
    BackHandler(enabled = tab != 0 && vehicle.drivingRestricted != true) { goTo(0) }
    // Into the driving view (moving off) a chime, back to parked a falling pair. Only while the app
    // is in front, and only between two known states seen there: a change that happened in the
    // background (or the first report after launch) sets the baseline instead of chiming late.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var was: Boolean? = null
            viewModel.vehicle.map { it.drivingRestricted }.distinctUntilChanged().collect { restricted ->
                if (was != null && restricted != null && was != restricted) {
                    Sfx.play(if (restricted) Sound.START else Sound.STOP, byControl = false)
                }
                if (restricted != null) was = restricted
            }
        }
    }

    // While the car requires distraction optimization only the driving view is shown.
    if (vehicle.drivingRestricted == true) {
        DrivingScreen(viewModel)
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = FogColors.Bg) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxHeight().width(120.dp).background(FogColors.Surface).padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Tab.entries.forEachIndexed { i, t -> RailItem(t, selected = tab == i) { goTo(i) } }
            }
            Box(Modifier.fillMaxHeight().width(1.dp).background(FogColors.Outline))
            Box(Modifier.fillMaxSize()) {
                when (Tab.entries[tab]) {
                    Tab.MAP -> MapScreen(viewModel)
                    Tab.DEX -> DexScreen(viewModel, onShowOnMap = { region ->
                        viewModel.mapFocus.value = region.bounds
                        goTo(Tab.MAP.ordinal)
                    })
                    Tab.EXPEDITION -> ExpeditionScreen(viewModel, onShowOnMap = { region ->
                        viewModel.mapFocus.value = region.bounds
                        goTo(Tab.MAP.ordinal)
                    })
                    Tab.SETTINGS -> SettingsScreen(viewModel, onStartDemo, onStopDemo, onRequestBackground)
                }
            }
        }
        val onboardingDone by viewModel.onboardingDone.collectAsState()
        if (!onboardingDone) Onboarding(onDone = { viewModel.setOnboardingDone(true) })
    }
}

/** Rail entry styled like the built-in dock: black when selected, with an orange bar under it. */
@Composable
private fun RailItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) FogColors.TextMain else FogColors.TextSub
    Column(
        Modifier.width(120.dp).clickable(onClick = onClick).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(40.dp))
        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = tint)
        Box(
            Modifier.width(48.dp).height(4.dp)
                .background(if (selected) FogColors.Amber else Color.Transparent),
        )
    }
}

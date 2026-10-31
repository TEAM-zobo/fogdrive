package com.zobo.burung.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.zobo.burung.FogRuntime
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.zobo.burung.demo.DemoCourses
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import com.mapbox.mapboxsdk.Mapbox
import com.zobo.burung.record.RecordingService

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    /** The one map every screen shows (see FogMap): made on first use, destroyed with the activity. */
    private var mapHolder: FogMapHolder? = null
    private fun mapHolder(): FogMapHolder = mapHolder ?: run {
        Mapbox.getInstance(this)
        FogMapHolder(this).also { mapHolder = it }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startRecordingIfAllowed() }

    override fun onDestroy() {
        mapHolder?.destroy()
        mapHolder = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        // Back from the navigation app: its 집 or destination may have changed meanwhile, and its
        // bookmark-changed callback did not reach us on the emulator.
        FogRuntime.navi.refresh()
    }

    override fun onStop() {
        super.onStop()
        // Region counts for the next start's loading screen (see FogRuntime.saveRegionCounts).
        FogRuntime.scope.launch { FogRuntime.saveRegionCounts() }
    }

    /**
     * Downloads the map along the demo course picked in settings and around the navigation's
     * destination, once the launch is done (not to compete with the first map's tiles).
     */
    private fun prefetchRoutes() {
        lifecycleScope.launch {
            FogRuntime.fogReady.first { it }
            launch {
                FogRuntime.demoPrefs.courseId.collect { id ->
                    RoutePrefetch.course(this@MainActivity, id, DemoCourses.byId(id).route)
                }
            }
            FogRuntime.navi.destination.filterNotNull().distinctUntilChanged().collect { RoutePrefetch.destination(this@MainActivity, it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // QA starts most cases with the tutorial already seen (its own case starts without it).
        if (intent?.getBooleanExtra(EXTRA_SKIP_ONBOARDING, false) == true) FogRuntime.mapPrefs.setOnboardingDone(true)
        // Made here, not in the content: a recomposition after onDestroy must not build a new map.
        val holder = mapHolder()
        setContent {
            FogTheme {
                CompositionLocalProvider(LocalFogMapHolder provides holder) {
                    Box(Modifier.fillMaxSize().clickSounds()) {
                        FogApp(
                            viewModel = viewModel,
                            onStartDemo = { RecordingService.start(this@MainActivity, RecordingService.ACTION_DEMO_START) },
                            onStopDemo = { RecordingService.start(this@MainActivity, RecordingService.ACTION_DEMO_STOP) },
                            onRequestBackground = {
                                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                            },
                        )
                        LoadingOverlay()
                    }
                }
            }
        }
        prefetchRoutes()
        val missing = RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startRecordingIfAllowed() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startRecordingIfAllowed() {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasLocation) RecordingService.start(this)
    }

    private companion object {
        const val EXTRA_SKIP_ONBOARDING = "skip_onboarding"
        val RUNTIME_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            "pleos.car.permission.FUSED_LOCATION",
            "android.car.permission.CAR_POWERTRAIN",
        )
    }
}

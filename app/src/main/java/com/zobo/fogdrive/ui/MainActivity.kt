package com.zobo.fogdrive.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.zobo.fogdrive.record.RecordingService

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startRecordingIfAllowed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FogTheme {
                FogApp(
                    viewModel = viewModel,
                    onStartDemo = { RecordingService.start(this, RecordingService.ACTION_DEMO_START) },
                    onStopDemo = { RecordingService.start(this, RecordingService.ACTION_DEMO_STOP) },
                    onRequestBackground = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    },
                )
            }
        }
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
        val RUNTIME_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            "pleos.car.permission.FUSED_LOCATION",
            "android.car.permission.CAR_POWERTRAIN",
        )
    }
}

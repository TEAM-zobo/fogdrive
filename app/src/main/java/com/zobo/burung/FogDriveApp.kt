package com.zobo.burung

import android.app.Application

class FogDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FogRuntime.init(this)
    }
}

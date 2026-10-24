package com.zobo.fogdrive

import android.app.Application

class FogDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FogRuntime.init(this)
    }
}

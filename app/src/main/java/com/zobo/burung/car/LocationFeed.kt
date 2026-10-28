package com.zobo.burung.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log

/**
 * Vehicle position feed. Prefers the Pleos FusedLocation SDK (vehicle-fused position, better in
 * tunnels) when `isSupported()`; otherwise falls back to the standard Android GPS provider.
 */
class LocationFeed(private val context: Context, private val onFix: (Fix) -> Unit) {
    private var fused: FusedLocation? = null
    private var fusedListener: FusedLocationListener? = null
    private var androidListener: LocationListener? = null

    var sourceName: String = "-"
        private set

    @SuppressLint("MissingPermission")
    fun start(): String {
        stop()
        if (startFused()) return sourceName
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { loc: Location ->
            onFix(Fix(loc.latitude, loc.longitude, loc.time, if (loc.hasAccuracy()) loc.accuracy else null, SOURCE_ANDROID))
        }
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 5f, listener, Looper.getMainLooper())
            androidListener = listener
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { listener.onLocationChanged(it) }
            SOURCE_ANDROID.also { sourceName = it }
        } catch (e: Exception) {
            Log.w(TAG, "GPS provider unavailable", e)
            "-".also { sourceName = it }
        }
    }

    private fun startFused(): Boolean = try {
        val f = FusedLocation(context.applicationContext)
        if (!f.isSupported()) {
            false
        } else {
            f.initialize()
            val listener = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    location ?: return
                    onFix(Fix(location.latitude, location.longitude, location.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis(), null, SOURCE_FUSED))
                }

                override fun onError(exception: Exception) {
                    Log.w(TAG, "FusedLocation error: ${exception.message}")
                }
            }
            f.registerFusedLocationCallback(listener)
            f.getLastKnownLocation(listener)
            fused = f
            fusedListener = listener
            sourceName = SOURCE_FUSED
            true
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Pleos FusedLocation unavailable", e)
        false
    }

    fun stop() {
        fused?.let { f ->
            runCatching { fusedListener?.let { f.unregisterFusedLocationCallback(it) } }
            runCatching { f.release() }
        }
        fused = null
        fusedListener = null
        androidListener?.let { l ->
            runCatching { (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(l) }
        }
        androidListener = null
        sourceName = "-"
    }

    companion object {
        const val SOURCE_FUSED = "Pleos FusedLocation"
        const val SOURCE_ANDROID = "Android GPS"
        const val SOURCE_DEMO = "시연 모드"
        private const val TAG = "FogLocation"
    }
}

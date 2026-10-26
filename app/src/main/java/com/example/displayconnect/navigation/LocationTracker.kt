package com.example.displayconnect.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Looper
import android.location.Location
import android.location.altitude.AltitudeConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class LocationUpdate(
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val speedMps: Float,
    val accuracyM: Float = Float.POSITIVE_INFINITY,
    val elapsedMs: Long = 0,
    val altitudeWgs84M: Double? = null,
    val altitudeAccuracyM: Float? = null,
    val altitudeMslM: Double? = null,
    val altitudeMslAccuracyM: Float? = null
)

class LocationTracker(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun locationFlow(intervalMs: Long): Flow<LocationUpdate> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setMaxUpdateAgeMillis(0)
            .setMaxUpdateDelayMillis(0)
            .setWaitForAccurateLocation(true)
            .build()

        fun sendLocation(location: Location) {
            trySend(LocationUpdate(location.latitude,location.longitude,
                    if(location.hasBearing()) location.bearing else Float.NaN,
                    if(location.hasSpeed()) location.speed else Float.NaN,
                    if(location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
                    location.elapsedRealtimeNanos/1_000_000,
                    if(location.hasAltitude()) location.altitude.takeIf { it.isFinite() } else null,
                    if(Build.VERSION.SDK_INT>=26 && location.hasVerticalAccuracy()) location.verticalAccuracyMeters.takeIf { it.isFinite()&&it>=0f } else null,
                    if(Build.VERSION.SDK_INT>=34 && location.hasMslAltitude()) location.mslAltitudeMeters.takeIf { it.isFinite() } else null,
                    if(Build.VERSION.SDK_INT>=34 && location.hasMslAltitudeAccuracy()) location.mslAltitudeAccuracyMeters.takeIf { it.isFinite()&&it>=0f } else null))
        }
        val monitor=(context.applicationContext as com.example.displayconnect.DisplayConnectApp).altitudeMonitor
        monitor.start()
        val converting=java.util.concurrent.atomic.AtomicBoolean(false)
        val converter=if(Build.VERSION.SDK_INT>=34) AltitudeConverter() else null
        var conversionJob:kotlinx.coroutines.Job?=null
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.locations.maxByOrNull { it.elapsedRealtimeNanos } ?: return
                val copy=Location(location)
                // A cold geoide load must not stop navigation or queue old GPS fixes.
                if(Build.VERSION.SDK_INT<34 || copy.hasMslAltitude() || !copy.hasAltitude() || !converting.compareAndSet(false,true)) {
                    sendLocation(copy)
                } else {
                    val raw=Location(copy)
                    val delivered=java.util.concurrent.atomic.AtomicBoolean(false)
                    val fallback=launch { delay(200);if(delivered.compareAndSet(false,true))sendLocation(raw) }
                    conversionJob=launch(Dispatchers.Default) {
                        try {
                            try { converter?.addMslAltitudeToLocation(context,copy) } catch (_: java.io.IOException) {} catch (_: IllegalArgumentException) {}
                            if(delivered.compareAndSet(false,true)) sendLocation(copy)
                        } finally { converting.set(false);fallback.cancel() }
                    }
                }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }
        awaitClose { fusedClient.removeLocationUpdates(callback);conversionJob?.cancel();monitor.stop() }
    }
}

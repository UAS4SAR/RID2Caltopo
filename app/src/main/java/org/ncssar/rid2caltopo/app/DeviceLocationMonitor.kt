/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.HandlerThread
import android.os.Process
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter
import org.ncssar.rid2caltopo.notam.NotamCenter

/**
 * One process-scoped Fused Location client.
 *
 * Activity- and service-scoped clients were created and torn down on every
 * display/activity lifecycle transition. Google Play Services posts the
 * resulting service unbind onto the main looper, where Binder can stall for
 * tens of seconds during activity recreate / process warm start.
 */
object DeviceLocationMonitor {
    private const val TAG = "DeviceLocationMonitor"

    private val lock = Any()
    @Volatile private var started = false
    private var client: FusedLocationProviderClient? = null
    private var callback: LocationCallback? = null
    private var worker: HandlerThread? = null
    private var firstFixDelivered = false
    @Volatile private var locationListener: ((android.location.Location) -> Unit)? = null

    /** Optional UI-side hook (terrain prefetch). Must not create or release GMS clients. */
    @JvmStatic
    fun setLocationListener(listener: ((android.location.Location) -> Unit)?) {
        locationListener = listener
    }

    /** Idempotent. Uses the application context so the client outlives activities. */
    @JvmStatic
    fun ensureStarted(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (started) return
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                CaltopoClient.CTError(TAG, "Precise location not granted; fused updates not started.")
                return
            }
            val thread = HandlerThread("r2c-device-location", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
            val locationClient = LocationServices.getFusedLocationProviderClient(app)
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (location in result.locations) {
                        deliver(location)
                    }
                }
            }
            val request = LocationRequest.Builder(10_000L)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(5_000L)
                .setMinUpdateDistanceMeters(5.0f)
                .build()
            try {
                locationClient.requestLocationUpdates(request, locationCallback, thread.looper)
                    .addOnSuccessListener {
                        CaltopoClient.CTDebug(TAG, "Fused location updates registered on dedicated worker.")
                    }
                    .addOnFailureListener { error ->
                        CaltopoClient.CTError(TAG, "requestLocationUpdates failed.", error)
                    }
                locationClient.lastLocation
                    .addOnSuccessListener { location ->
                        if (location == null) {
                            CaltopoClient.CTDebug(TAG, "Fused lastLocation unavailable.")
                            return@addOnSuccessListener
                        }
                        CaltopoClient.CTDebug(
                            TAG,
                            "Applying fused lastLocation accuracy=${location.accuracy}m " +
                                "ageMs=${(System.currentTimeMillis() - location.time).coerceAtLeast(0L)}",
                        )
                        deliver(location)
                    }
                    .addOnFailureListener { error ->
                        CaltopoClient.CTError(TAG, "Fused lastLocation failed.", error)
                    }
            } catch (error: SecurityException) {
                thread.quitSafely()
                CaltopoClient.CTError(TAG, "Location permission lost before updates started.", error)
                return
            }
            client = locationClient
            callback = locationCallback
            worker = thread
            started = true
            CaltopoClient.CTDebug(TAG, "Started long-lived fused location client.")
        }
    }

    /**
     * Tear down only on real process/session shutdown. Never call from activity
     * destroy, config change, or display-active toggles.
     */
    @JvmStatic
    fun stop() {
        synchronized(lock) {
            if (!started) return
            val activeClient = client
            val activeCallback = callback
            val activeWorker = worker
            client = null
            callback = null
            worker = null
            firstFixDelivered = false
            started = false
            try {
                if (activeClient != null && activeCallback != null) {
                    activeClient.removeLocationUpdates(activeCallback)
                }
            } catch (error: Exception) {
                CaltopoClient.CTError(TAG, "removeLocationUpdates raised:", error)
            } finally {
                activeWorker?.quitSafely()
            }
            CaltopoClient.CTDebug(TAG, "Stopped long-lived fused location client.")
        }
    }

    @JvmStatic
    fun isStarted(): Boolean = started

    private fun deliver(location: android.location.Location) {
        val hadDeviceLocationBefore = CaltopoMap.GetDeviceLocation() != null
        CaltopoMap.UpdateMyLocation(location)
        runCatching { locationListener?.invoke(location) }
        if (!hadDeviceLocationBefore && CaltopoMap.GetDeviceLocation() != null && !firstFixDelivered) {
            firstFixDelivered = true
            AirspaceCenter.requestImmediateRefresh()
            NotamCenter.requestImmediateRefresh()
            LandRestrictionCenter.requestImmediateRefresh()
        }
    }
}

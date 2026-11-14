/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ncssar.rid2caltopo.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
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
import java.util.concurrent.Executors

/** Main-thread lifecycle inputs, with a single atomic snapshot for worker callbacks. */
internal class ForegroundLocationGate {
    data class Session(val generation: Long, val active: Boolean)
    @Volatile var session = Session(0, false)
        private set
    private var enabled = false
    private var focusedActivities = 0

    fun enable(): Session? { enabled = true; return reconcile() }
    fun disable(): Session? { enabled = false; return reconcile() }
    fun activityResumed(): Session? { focusedActivities++; return reconcile() }
    fun activityPaused(): Session? {
        focusedActivities = (focusedActivities - 1).coerceAtLeast(0)
        return reconcile()
    }
    private fun reconcile(): Session? {
        val active = enabled && focusedActivities > 0
        if (active == session.active) return null
        return Session(session.generation + 1, active).also { session = it }
    }
    fun accepts(candidate: Session): Boolean = candidate.active && session == candidate
}

/**
 * Tablet location for the map marker (field test Nov 13, 2026: a coarse fix put the tablet
 * hundreds of metres off). Precise when the user granted precise location, approximate
 * otherwise; the marker's info states which, with the reported accuracy.
 */
internal object DeviceLocationText {
    fun accuracyLine(accuracyM: Float?, precise: Boolean): String {
        val acc = accuracyM?.takeIf { it.isFinite() && it > 0f }?.let { " ±${Math.round(it)} m" } ?: ""
        return if (precise) "Tablet location: precise$acc"
        else "Tablet location: approximate$acc (precise location is off for this app in Android settings)"
    }
}

/**
 * Device location is precise (when granted) and active only while an app activity is resumed
 * (Ken, Nov 13: no position updates while the app does not have focus). Paused = a system
 * dialog, another app in front, or the screen locked; the last fix stays for the map.
 */
object DeviceLocationMonitor {
    private const val TAG = "DeviceLocationMonitor"
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "r2c-device-location").apply { isDaemon = true }
    }
    // Written only by the main-thread activity lifecycle. Worker callbacks check
    // the generation before using a fix so an old session cannot publish it.
    private val gate = ForegroundLocationGate()
    private var appContext: Context? = null
    private var client: FusedLocationProviderClient? = null // worker only
    private var callback: LocationCallback? = null // worker only
    private var firstFixDelivered = false // worker only
    @Volatile private var locationListener: ((Location) -> Unit)? = null
    /** Whether the running request has precise (fine) permission; read by the map marker. */
    @Volatile var preciseGranted: Boolean = false
        private set

    /** Info-window line for the tablet marker: precision and accuracy of the last fix. */
    @JvmStatic
    fun accuracyLine(): String? {
        val fix = CaltopoMap.GetDeviceLocation() ?: return null
        return DeviceLocationText.accuracyLine(if (fix.hasAccuracy()) fix.accuracy else null, preciseGranted)
    }

    fun install(application: Application) {
        appContext = application.applicationContext
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                reconcile(gate.activityResumed())
            }
            override fun onActivityPaused(activity: Activity) {
                reconcile(gate.activityPaused())
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    @JvmStatic
    fun setLocationListener(listener: ((Location) -> Unit)?) { locationListener = listener }

    /** Called after launch consent and permission handling, on the main thread. */
    @JvmStatic
    fun ensureStarted(context: Context) {
        appContext = context.applicationContext
        reconcile(gate.enable())
    }

    /** Disable on session shutdown; the scanner service is independent of location. */
    @JvmStatic
    fun stop() {
        reconcile(gate.disable())
    }

    private fun reconcile(session: ForegroundLocationGate.Session?) {
        if (session == null) return
        val context = appContext ?: return
        worker.execute {
            stopOnWorker()
            if (gate.accepts(session)) startOnWorker(context, session)
        }
    }

    private fun startOnWorker(context: Context, session: ForegroundLocationGate.Session) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            CaltopoClient.CTDebug(TAG, "Location permission not granted; updates not started.")
            return
        }
        // Precise when the user granted it; approximate-only permission keeps working with
        // coarse fixes (GRANULARITY_PERMISSION_LEVEL never throws for a missing fine grant).
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        preciseGranted = precise
        CaltopoClient.CTDebug(TAG, "Location updates starting (foreground only) precise=$precise")
        val locationClient = LocationServices.getFusedLocationProviderClient(context)
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    if (!gate.accepts(session)) return
                    deliver(location)
                }
            }
        }
        val request = LocationRequest.Builder(5_000L)
            .setPriority(if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setMinUpdateIntervalMillis(2_000L)
            .setMinUpdateDistanceMeters(2.0f)
            .build()
        try {
            client = locationClient
            callback = locationCallback
            locationClient.requestLocationUpdates(request, worker, locationCallback)
                .addOnFailureListener(worker) { error ->
                    CaltopoClient.CTError(TAG, "requestLocationUpdates failed.", error)
                }
        } catch (error: SecurityException) {
            client = null
            callback = null
            CaltopoClient.CTError(TAG, "Location permission lost before updates started.", error)
        }
    }

    private fun stopOnWorker() {
        val activeClient = client
        val activeCallback = callback
        client = null
        callback = null
        firstFixDelivered = false
        if (activeClient != null && activeCallback != null) {
            try {
                activeClient.removeLocationUpdates(activeCallback)
            } catch (error: Exception) {
                CaltopoClient.CTError(TAG, "removeLocationUpdates raised:", error)
            }
        }
    }

    private fun deliver(location: Location) {
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

/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ncssar.rid2caltopo.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Executors

/**
 * Developer Tools override for the tracker ack retry.
 *
 * Absent or unreadable storage uses the new behavior: backoff, no immediate
 * redial, and reconnect jitter on. The in-memory values are what the
 * websocket client reads. Disk is touched only from [preload] and the persist
 * methods, which callers run off the UI thread.
 */
object TrackerReconnectPrefs {
    private const val PREFS = "tracker_reconnect"
    private const val LEGACY_IMMEDIATE_ACK_RETRY = "legacy_immediate_ack_retry"
    private const val RECONNECT_JITTER = "reconnect_jitter_enabled"

    @Volatile
    private var cachedLegacy: Boolean? = null

    @Volatile
    private var cachedJitter: Boolean? = null

    private val loader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tracker-reconnect-settings").apply { isDaemon = true }
    }

    /** Missing storage stays on the backoff path. */
    internal fun resolveLegacyImmediateAckRetry(stored: Boolean?): Boolean = stored == true

    /** Missing storage keeps jitter on. Equal jitter never waits longer than the ladder step. */
    internal fun resolveReconnectJitterEnabled(stored: Boolean?): Boolean = stored ?: true

    @JvmStatic
    fun current(): Boolean = cachedLegacy ?: false

    @JvmStatic
    fun reconnectJitterEnabled(): Boolean = cachedJitter ?: true

    @JvmStatic
    @Synchronized
    fun setCached(enabled: Boolean) {
        cachedLegacy = enabled
    }

    @JvmStatic
    @Synchronized
    fun setReconnectJitterCached(enabled: Boolean) {
        cachedJitter = enabled
    }

    @JvmStatic
    fun preloadAsync(context: Context?) {
        loader.execute { preload(context) }
    }

    /** Reads storage, then publishes only if the operator has not already chosen. */
    @JvmStatic
    fun preload(context: Context?): Boolean {
        val prefs = preferences(context)
        publishJitterIfUnset(readBoolean(prefs, RECONNECT_JITTER))
        return publishIfUnset(readBoolean(prefs, LEGACY_IMMEDIATE_ACK_RETRY))
    }

    @JvmStatic
    fun persist(context: Context?, enabled: Boolean) {
        setCached(enabled)
        context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(LEGACY_IMMEDIATE_ACK_RETRY, enabled)
            ?.apply()
    }

    @JvmStatic
    fun persistReconnectJitter(context: Context?, enabled: Boolean) {
        setReconnectJitterCached(enabled)
        context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(RECONNECT_JITTER, enabled)
            ?.apply()
    }

    @Synchronized
    internal fun publishIfUnset(stored: Boolean?): Boolean {
        if (cachedLegacy == null) {
            cachedLegacy = resolveLegacyImmediateAckRetry(stored)
        }
        return cachedLegacy ?: false
    }

    @Synchronized
    internal fun publishJitterIfUnset(stored: Boolean?): Boolean {
        if (cachedJitter == null) {
            cachedJitter = resolveReconnectJitterEnabled(stored)
        }
        return cachedJitter ?: true
    }

    private fun preferences(context: Context?) =
        context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readBoolean(prefs: SharedPreferences?, key: String): Boolean? {
        if (prefs == null || !prefs.contains(key)) return null
        return prefs.getBoolean(key, false)
    }
}

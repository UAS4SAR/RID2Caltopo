/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ncssar.rid2caltopo.data

import android.content.Context
import java.util.concurrent.Executors

/**
 * Developer Tools override for the tracker ack retry.
 *
 * Absent or unreadable storage uses the new behavior (backoff, no immediate
 * redial). The in-memory value is what the websocket client reads. Disk is
 * touched only from [preload] and [persist], which callers run off the UI thread.
 */
object TrackerReconnectPrefs {
    private const val PREFS = "tracker_reconnect"
    private const val LEGACY_IMMEDIATE_ACK_RETRY = "legacy_immediate_ack_retry"

    @Volatile
    private var cachedLegacy: Boolean? = null

    private val loader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tracker-reconnect-settings").apply { isDaemon = true }
    }

    /** Missing storage stays on the backoff path. */
    internal fun resolveLegacyImmediateAckRetry(stored: Boolean?): Boolean = stored == true

    @JvmStatic
    fun current(): Boolean = cachedLegacy ?: false

    @JvmStatic
    @Synchronized
    fun setCached(enabled: Boolean) {
        cachedLegacy = enabled
    }

    @JvmStatic
    fun preloadAsync(context: Context?) {
        loader.execute { preload(context) }
    }

    /** Reads storage, then publishes only if the operator has not already chosen. */
    @JvmStatic
    fun preload(context: Context?): Boolean {
        val stored = read(context)
        return publishIfUnset(stored)
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

    @Synchronized
    internal fun publishIfUnset(stored: Boolean?): Boolean {
        if (cachedLegacy == null) {
            cachedLegacy = resolveLegacyImmediateAckRetry(stored)
        }
        return cachedLegacy ?: false
    }

    private fun read(context: Context?): Boolean? {
        val prefs = context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?: return null
        if (!prefs.contains(LEGACY_IMMEDIATE_ACK_RETRY)) return null
        return prefs.getBoolean(LEGACY_IMMEDIATE_ACK_RETRY, false)
    }
}

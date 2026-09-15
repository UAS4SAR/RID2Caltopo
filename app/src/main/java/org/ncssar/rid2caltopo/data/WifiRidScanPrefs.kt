/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ncssar.rid2caltopo.data

import android.content.Context

/** Operator preference for Android Wi-Fi Beacon and Wi-Fi NAN Remote ID discovery. */
object WifiRidScanPrefs {
    // A new preference generation switches existing installs to Bluetooth-only
    // discovery. An operator can still explicitly opt back into direct Wi-Fi RID.
    private const val PREFS = "wifi_rid_scanning_bluetooth_default"
    private const val ENABLED = "enabled"

    /** Match the Apple app's Bluetooth-only discovery unless explicitly enabled. */
    internal fun resolveEnabled(hasStoredValue: Boolean, storedValue: Boolean): Boolean =
        if (hasStoredValue) storedValue else false

    @JvmStatic
    fun isEnabled(context: Context?): Boolean {
        val prefs = context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?: return false
        return resolveEnabled(prefs.contains(ENABLED), prefs.getBoolean(ENABLED, false))
    }

    @JvmStatic
    fun setEnabled(context: Context?, enabled: Boolean) {
        context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(ENABLED, enabled)
            ?.apply()
    }
}

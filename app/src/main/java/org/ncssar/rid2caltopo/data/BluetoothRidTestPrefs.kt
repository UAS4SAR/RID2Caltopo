/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ncssar.rid2caltopo.data

import android.content.Context
import org.ncssar.rid2caltopo.BuildConfig

/** Debug-build-only Bluetooth Remote ID scan experiments. */
object BluetoothRidTestPrefs {
    private const val PREFS = "bluetooth_rid_test"
    private const val VARIANT = "variant"
    private const val PERIODIC_RESTART = "periodic_restart"

    enum class ScanVariant(val label: String) {
        PRODUCTION("Off (production scan)"),
        FILTERED_ALL_PHY("Filtered / all PHY"),
        FILTERED_LEGACY_1M("Filtered / legacy 1M"),
        SOFTWARE_FILTER_ALL_PHY("App filter / all PHY"),
        SOFTWARE_FILTER_LEGACY_1M("App filter / legacy 1M");

        val diagnosticsEnabled: Boolean
            get() = this != PRODUCTION

        val usesSoftwareFilter: Boolean
            get() = this == SOFTWARE_FILTER_ALL_PHY || this == SOFTWARE_FILTER_LEGACY_1M

        val usesLegacy1M: Boolean
            get() = this == PRODUCTION || this == FILTERED_LEGACY_1M || this == SOFTWARE_FILTER_LEGACY_1M
    }

    internal fun resolveVariant(storedName: String?, debugBuild: Boolean): ScanVariant {
        if (!debugBuild) return ScanVariant.PRODUCTION
        return runCatching { ScanVariant.valueOf(storedName.orEmpty()) }
            .getOrDefault(ScanVariant.PRODUCTION)
    }

    internal fun resolvePeriodicRestart(
        storedValue: Boolean,
        variant: ScanVariant,
        debugBuild: Boolean
    ): Boolean = variant == ScanVariant.PRODUCTION ||
        (debugBuild && variant.diagnosticsEnabled && storedValue)

    @JvmStatic
    fun getVariant(context: Context?): ScanVariant {
        val storedName = context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(VARIANT, null)
        return resolveVariant(storedName, BuildConfig.DEBUG)
    }

    @JvmStatic
    fun setVariant(context: Context?, variant: ScanVariant) {
        if (!BuildConfig.DEBUG) return
        context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(VARIANT, variant.name)
            ?.apply()
    }

    @JvmStatic
    fun isPeriodicRestartEnabled(context: Context?): Boolean {
        val variant = getVariant(context)
        val storedValue = context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(PERIODIC_RESTART, false)
            ?: false
        return resolvePeriodicRestart(storedValue, variant, BuildConfig.DEBUG)
    }

    @JvmStatic
    fun setPeriodicRestartEnabled(context: Context?, enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(PERIODIC_RESTART, enabled)
            ?.apply()
    }
}

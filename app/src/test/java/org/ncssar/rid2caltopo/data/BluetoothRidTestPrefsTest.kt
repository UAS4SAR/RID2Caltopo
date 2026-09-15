/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRidTestPrefsTest {
    @Test
    fun releaseBuildAlwaysUsesProductionScan() {
        assertEquals(
            BluetoothRidTestPrefs.ScanVariant.PRODUCTION,
            BluetoothRidTestPrefs.resolveVariant("SOFTWARE_FILTER_ALL_PHY", debugBuild = false)
        )
    }

    @Test
    fun debugBuildRestoresAValidVariantAndRejectsUnknownValues() {
        assertEquals(
            BluetoothRidTestPrefs.ScanVariant.FILTERED_LEGACY_1M,
            BluetoothRidTestPrefs.resolveVariant("FILTERED_LEGACY_1M", debugBuild = true)
        )
        assertEquals(
            BluetoothRidTestPrefs.ScanVariant.PRODUCTION,
            BluetoothRidTestPrefs.resolveVariant("obsolete", debugBuild = true)
        )
    }

    @Test
    fun productionRestartsScanAndExperimentsRetainTheirOverride() {
        assertTrue(
            BluetoothRidTestPrefs.resolvePeriodicRestart(
                storedValue = true,
                variant = BluetoothRidTestPrefs.ScanVariant.FILTERED_ALL_PHY,
                debugBuild = true
            )
        )
        assertTrue(
            BluetoothRidTestPrefs.resolvePeriodicRestart(
                storedValue = true,
                variant = BluetoothRidTestPrefs.ScanVariant.PRODUCTION,
                debugBuild = true
            )
        )
        assertFalse(
            BluetoothRidTestPrefs.resolvePeriodicRestart(
                storedValue = true,
                variant = BluetoothRidTestPrefs.ScanVariant.FILTERED_ALL_PHY,
                debugBuild = false
            )
        )
    }

    @Test
    fun variantsExposeIndependentFilterAndPhyFactors() {
        assertTrue(BluetoothRidTestPrefs.ScanVariant.PRODUCTION.usesLegacy1M)
        assertFalse(BluetoothRidTestPrefs.ScanVariant.PRODUCTION.usesSoftwareFilter)
        assertTrue(BluetoothRidTestPrefs.resolvePeriodicRestart(false,
            BluetoothRidTestPrefs.ScanVariant.PRODUCTION, debugBuild = false))
        assertFalse(BluetoothRidTestPrefs.ScanVariant.FILTERED_ALL_PHY.usesSoftwareFilter)
        assertFalse(BluetoothRidTestPrefs.ScanVariant.FILTERED_ALL_PHY.usesLegacy1M)
        assertFalse(BluetoothRidTestPrefs.ScanVariant.FILTERED_LEGACY_1M.usesSoftwareFilter)
        assertTrue(BluetoothRidTestPrefs.ScanVariant.FILTERED_LEGACY_1M.usesLegacy1M)
        assertTrue(BluetoothRidTestPrefs.ScanVariant.SOFTWARE_FILTER_ALL_PHY.usesSoftwareFilter)
        assertFalse(BluetoothRidTestPrefs.ScanVariant.SOFTWARE_FILTER_ALL_PHY.usesLegacy1M)
        assertTrue(BluetoothRidTestPrefs.ScanVariant.SOFTWARE_FILTER_LEGACY_1M.usesSoftwareFilter)
        assertTrue(BluetoothRidTestPrefs.ScanVariant.SOFTWARE_FILTER_LEGACY_1M.usesLegacy1M)
    }
}

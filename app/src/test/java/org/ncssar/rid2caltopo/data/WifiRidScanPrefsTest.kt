/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRidScanPrefsTest {
    @Test
    fun bluetoothDiscoveryProfileDefaultsToWifiRidDisabled() {
        assertFalse(WifiRidScanPrefs.resolveEnabled(hasStoredValue = false, storedValue = false))
        assertFalse(WifiRidScanPrefs.resolveEnabled(hasStoredValue = false, storedValue = true))
    }

    @Test
    fun explicitSettingIsPreserved() {
        assertFalse(WifiRidScanPrefs.resolveEnabled(hasStoredValue = true, storedValue = false))
        assertTrue(WifiRidScanPrefs.resolveEnabled(hasStoredValue = true, storedValue = true))
    }
}

/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchDisclaimerTest {
    @Test
    fun disclaimerUsesApprovedSafetyLanguage() {
        var hash = 14695981039346656037uL
        LAUNCH_DISCLAIMER_TEXT.encodeToByteArray().forEach { byte ->
            hash = (hash xor byte.toUByte().toULong()) * 1099511628211uL
        }

        assertEquals(0x8298a4ab7300621uL, hash)
        assertTrue(LAUNCH_DISCLAIMER_TEXT.contains("Apache License, Version 2.0"))
        assertTrue(LAUNCH_DISCLAIMER_TEXT.contains("This acknowledgement does not modify those licenses"))
        assertTrue(LAUNCH_DISCLAIMER_TEXT.contains("does not accept service terms or bind your organization"))
        assertTrue(LAUNCH_DISCLAIMER_TEXT.contains("personal injury, or death"))
        assertTrue(LAUNCH_DISCLAIMER_TEXT.contains("does not establish that conditions are safe"))
    }
}

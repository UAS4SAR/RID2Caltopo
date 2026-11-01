package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StartupCoroutineSafetyTest {
    @Test
    fun sanitizeStripsPreciseCoordinatesAndLongDigits() {
        val raw = "failed near 39.7392367,-104.990251 while writing 123456789 bytes"
        val cleaned = StartupCoroutineSafety.sanitize(raw)
        assertFalse(cleaned.contains("39.7392367"))
        assertFalse(cleaned.contains("-104.990251"))
        assertFalse(cleaned.contains("123456789"))
        assertTrueish(cleaned.contains("[coord]"))
        assertTrueish(cleaned.contains("[n]"))
        assertEquals("(no message)", StartupCoroutineSafety.sanitize(null))
    }

    private fun assertTrueish(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}

package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaltopoMarkerCleanupPolicyTest {
    @Test
    fun delete400NeedsVerifyAndIsNotSuccess() {
        val plan = CaltopoMarkerCleanupPolicy.afterDelete(400, true)
        assertTrue(plan.needsVerify)
        assertFalse(plan.success)
        assertFalse(plan.terminal)
        assertFalse(CaltopoMarkerCleanupPolicy.afterVerify(false))
    }

    @Test
    fun delete200NeedsVerify_successOnlyWhenAbsent() {
        val plan = CaltopoMarkerCleanupPolicy.afterDelete(200, true)
        assertTrue(plan.needsVerify)
        assertFalse(plan.success)
        assertTrue(CaltopoMarkerCleanupPolicy.afterVerify(true))
        assertFalse(CaltopoMarkerCleanupPolicy.afterVerify(false))
    }

    @Test
    fun delete404IsAlreadyAbsent() {
        val plan = CaltopoMarkerCleanupPolicy.afterDelete(404, true)
        assertTrue(plan.terminal)
        assertTrue(plan.success)
        assertFalse(plan.needsVerify)
    }

    @Test
    fun delete403IsTerminalAuthWithoutInventingSuccess() {
        val plan = CaltopoMarkerCleanupPolicy.afterDelete(403, false)
        assertTrue(plan.terminal)
        assertTrue(plan.terminalAuth)
        assertFalse(plan.success)
        assertFalse(plan.needsVerify)
    }

    @Test
    fun delete500RetriesWithoutVerify() {
        val plan = CaltopoMarkerCleanupPolicy.afterDelete(500, false)
        assertFalse(plan.terminal)
        assertFalse(plan.success)
        assertFalse(plan.needsVerify)
        assertEquals(false, plan.terminalAuth)
    }
}

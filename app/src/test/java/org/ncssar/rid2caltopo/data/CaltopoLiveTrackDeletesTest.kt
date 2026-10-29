package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaltopoLiveTrackDeletesTest {
    @After
    fun resetClaims() {
        CaltopoLiveTrackDeletes.resetForTests()
    }

    @Test
    fun endOfFlightDeleteIsSentOnceUntilARealFailure() {
        assertTrue(CaltopoLiveTrackDeletes.claim("4J0LF02", "CC1A8356"))
        assertFalse(CaltopoLiveTrackDeletes.claim("4J0LF02", "cc1a8356"))
        CaltopoLiveTrackDeletes.release("4J0LF02", "cc1a8356")
        assertTrue(CaltopoLiveTrackDeletes.claim("4J0LF02", "cc1a8356"))
    }
}

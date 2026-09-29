package org.ncssar.rid2caltopo.airspace

import org.junit.Assert.*
import org.junit.Test

class AirspaceRetryPolicyTest {
    @Test fun successSchedulesNextRoutineCheckTwentyMinutesLater() {
        val policy = AirspaceRetryPolicy()
        policy.failed(true, null, 1000)
        policy.succeeded()
        assertEquals(0, policy.failures)
        val successAt = 5000L
        val next = AirspaceRetryPolicy.nextRoutineRefreshAfter(successAt)
        assertEquals(successAt + 1_200_000L, next)
        for (elapsed in listOf(2_000L, 32_000L, 60_000L, 900_000L, 1_199_999L)) {
            assertTrue(successAt + elapsed < next)
        }
    }

    @Test fun rateLimitsIncreaseDelayAndSuccessResets() {
        val policy = AirspaceRetryPolicy()
        val now = 1_000_000L
        for (expected in listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 32_000L, 32_000L)) {
            assertEquals(expected, policy.failed(true, null, now))
            assertFalse(policy.permits(now + expected - 1))
            assertTrue(policy.permits(now + expected))
        }
        policy.succeeded()
        assertTrue(policy.permits(now))
        assertEquals(2_000L, policy.failed(false, null, now))
    }
    @Test fun retryAfterIsMinimumEvenBeyondLocalCap() {
        assertEquals(180_000L, AirspaceRetryPolicy.serverDelay("Thu, 01 Jan 1970 00:03:00 GMT", 0))
        assertNull(AirspaceRetryPolicy.serverDelay("invalid", 0))
        assertEquals(3_600_000L, AirspaceRetryPolicy().failed(true, "3600", 0))
    }
    @Test fun arcgisErrorBodyIsNotAnEmptySuccess() {
        for (payload in listOf("""{"error":{"code":429,"message":"Throttled"}}""",
            """{"error":{"code":400,"message":"Unable to perform query. Too many requests."}}""")) {
            try {
                FaaUasFacilityMapParser.parse(payload)
                fail("Expected service failure")
            } catch (error: AirspaceServiceFailure) { assertTrue(error.rateLimited) }
        }
    }
}

package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflinePrepRetryTest {
    private fun capacity(limit: Long, free: Long = 960_000_000_000L) = OfflinePrepCapacity(
        8_300_000_000L, 12_000_000_000L, 2_000_000_000L, limit, free
    )

    @Test fun storageFailureThenAppliedLimitRecoversWithAolIncludingAfterReopen() = runBlocking {
        for (cachedPlan in listOf("aol-plan", null)) {
            var limit = 10_000_000_000L
            var lookups = 0
            var started: String? = null
            suspend fun retry() = recoverOfflinePrep(
                true, cachedPlan, { lookups++; "aol-plan" }, { true },
                { check(!capacity(limit).exceedsCacheLimit) }, { started = it }
            )
            try { retry(); fail("Expected capacity failure") } catch (_: IllegalStateException) {}
            assertNull(started)
            limit = 100_000_000_000L
            retry()
            assertEquals("aol-plan", started)
            assertEquals(if (cachedPlan == null) 2 else 0, lookups)
        }
    }

    @Test fun newlyResolvedAolBytesAreCheckedBeforeStart() = runBlocking {
        var started = false
        var resolved = false
        try {
            recoverOfflinePrep(true, null, { resolved = true; "large-aol" }, { true }, {
                assertEquals("large-aol", it)
                check(!capacity(100_000_000_000L, 1_000_000_000L).exceedsAvailableVolume)
            }, { started = true })
            fail("Expected free-space failure")
        } catch (_: IllegalStateException) {}
        assertTrue(resolved)
        assertFalse(started)
    }

    @Test fun catalogFailureNeverSilentlyDropsAolAndNextRetryWorks() = runBlocking {
        var started: String? = null
        try {
            recoverOfflinePrep<String>(true, null, { error("USGS unavailable") }, { true }, {}, { started = it })
            fail("Expected catalog failure")
        } catch (_: IllegalStateException) {}
        assertNull(started)
        recoverOfflinePrep(true, null, { "recovered" }, { true }, {}, { started = it })
        assertEquals("recovered", started)
    }

    @Test fun changedSelectionAndCancellationNeverStart() = runBlocking {
        var current = true
        var started = false
        recoverOfflinePrep(true, null, { current = false; "old-region" }, { current },
            { fail("Stale selection must not reach capacity check") }, { started = true })
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            recoverOfflinePrep<String>(true, null, { awaitCancellation() }, { true }, {}, { started = true })
        }
        job.cancelAndJoin()
        assertFalse(started)
        current = true
        recoverOfflinePrep(true, "plan", { error("Unexpected lookup") }, { current },
            { current = false }, { started = true })
        assertFalse(started)
    }

    @Test fun explicitOptOutDoesNotFetchOrPassAol() = runBlocking {
        var started = false
        recoverOfflinePrep(false, "old-plan", { error("Unexpected lookup") }, { true }, {}, {
            assertNull(it); started = true
        })
        assertTrue(started)
    }
}

package org.ncssar.rid2caltopo.video

import org.junit.Assert.*
import org.junit.Test
import org.ncssar.rid2caltopo.video.mapcache.MapCacheUsage
import org.ncssar.rid2caltopo.video.mapcache.MapCacheBudgetPolicy

class UnifiedMapCacheTest {
    @Test fun defaultLimitKeepsTwentyPercentFreeAndCapsAtTenGB() {
        assertEquals(10_000_000_000, MapCacheBudgetPolicy.defaultLimit(100_000_000_000))
        assertEquals(4_000_000_000, MapCacheBudgetPolicy.defaultLimit(5_000_000_000))
        assertEquals(0, MapCacheBudgetPolicy.defaultLimit(0))
        assertEquals(0, MapCacheBudgetPolicy.defaultLimit(-1))
        assertEquals(10_000_000_000, MapCacheBudgetPolicy.defaultLimit(Long.MAX_VALUE))
    }

    @Test fun allDiskCategoriesAndConcurrentDownloadsShareOneLimit() {
        val usage = MapCacheUsage(300_000_000, 400_000_000, 50_000_000, 10_000_000, 100_000_000)
        assertEquals(760_000_000, usage.total)
        assertTrue(MapCacheBudgetPolicy.fits(usage.total, usage.reserved, 140_000_000, 1_000_000_000))
        assertFalse(MapCacheBudgetPolicy.fits(usage.total, usage.reserved, 140_000_001, 1_000_000_000))
        assertFalse(MapCacheBudgetPolicy.fits(usage.total, usage.reserved, 400_000_000, 1_000_000_000))
    }

    @Test fun terrainAloneCanExceedOfflinePlanBudget() {
        val capacity = OfflinePrepCapacity(100_000_000, 0, 1_050_000_000, 1_000_000_000, 10_000_000_000)
        assertTrue(capacity.exceedsCacheLimit)
        assertEquals(2_000_000_000, capacity.recommendedMaximumBytes)
    }

    @Test fun reservationAdmissionRejectsOverflowAndAlreadyOverLimit() {
        assertFalse(MapCacheBudgetPolicy.fits(Long.MAX_VALUE - 4, 3, 2, Long.MAX_VALUE))
        assertFalse(MapCacheBudgetPolicy.fits(1100, 0, 0, 1000))
        assertFalse(MapCacheBudgetPolicy.fits(0, 0, -1, 1000))
    }
}

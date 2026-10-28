package org.ncssar.rid2caltopo.ui

import org.junit.Assert.*
import org.junit.Test

class StorageAllocationTest {
    @Test fun onlyUnusedAllowancesAreSubtractedFromActualFreeSpace() {
        val allocation = StorageAllocation(100_000_000_000, 3_000_000_000, 10_000_000_000, 8_000_000_000, 20_000_000_000)
        assertEquals(7_000_000_000, allocation.remainingCache)
        assertEquals(12_000_000_000, allocation.remainingFlight)
        assertEquals(81_000_000_000, allocation.freeAfterAllowances)
    }
    @Test fun OverLimitUsageDoesNotPretendThatSpaceHasBeenReclaimed() {
        assertEquals(-5L, StorageAllocation(5, 2, 10, 5, 7).freeAfterAllowances)
        assertEquals(5L, StorageAllocation(5, 20, 10, 9, 7).freeAfterAllowances)
        assertNull(StorageAllocation(null, 2, 10, 5, 7).freeAfterAllowances)
    }
}

package org.ncssar.rid2caltopo.ui

import org.junit.Assert.*
import org.junit.Test

class StorageLimitDraftTest {
    @Test fun hundredGbDoesNotOverflowOrFallBackToTen() {
        assertEquals(StorageLimits(100_000_000_000L, 30), parseStorageLimits("100", "30"))
        assertEquals(parseStorageLimits("100", "30"), parseStorageLimits("100.0", "30"))
        assertNotEquals(parseStorageLimits("10", "30"), parseStorageLimits("100", "30"))
    }
    @Test fun invalidOrIncompleteEditsCannotBeSaved() {
        for (size in listOf("", "NaN", "Infinity", "0", "1001")) assertNull(parseStorageLimits(size, "30"))
        for (days in listOf("", "0", "3651", "1.5")) assertNull(parseStorageLimits("100", days))
        assertEquals(StorageLimits(1_000_000_000_000, 3650), parseStorageLimits("1000", "3650"))
    }
}

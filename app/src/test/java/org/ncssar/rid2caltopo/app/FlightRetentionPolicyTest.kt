package org.ncssar.rid2caltopo.app

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FlightRetentionPolicyTest {
    private val today = LocalDate.of(2026, 9, 14)
    @Test fun missingArchiveRequiresSetupWithoutLowStorageNotification() {
        val issue = flightStorageIssue(archiveReady = false, deviceLow = false, allowanceInsufficient = false)
        assertEquals(FlightStorageIssue.ARCHIVE_REQUIRED, issue)
        assertFalse(issue!!.shouldNotify)
        assertFalse(issue.message.contains("storage is low"))
    }
    @Test fun connectingArchiveClearsSetupBlockWhenCapacityIsHealthy() {
        assertNotNull(flightStorageIssue(false, false, false))
        assertNull(flightStorageIssue(true, false, false))
    }
    @Test fun realDevicePressureIsDistinctFromAppAllowance() {
        assertEquals(FlightStorageIssue.DEVICE_LOW, flightStorageIssue(true, true, false))
        assertEquals(FlightStorageIssue.DEVICE_LOW, flightStorageIssue(true, true, true))
        assertEquals(FlightStorageIssue.ALLOWANCE, flightStorageIssue(true, false, true))
        assertTrue(FlightStorageIssue.DEVICE_LOW.shouldNotify)
        assertTrue(FlightStorageIssue.ALLOWANCE.shouldNotify)
    }
    @Test fun ninetyPercentIncludesPlannedWritesAndLargerAllowanceRelievesPressure() {
        val limit = 10_000_000_000L
        assertEquals(limit, FlightStorage.DEFAULT_MAX_BYTES)
        assertFalse(FlightRetentionPolicy.needsCleanup(8_999_999_999L, maximum = limit))
        assertTrue(FlightRetentionPolicy.needsCleanup(9_000_000_000L, maximum = limit))
        assertTrue(FlightRetentionPolicy.needsCleanup(8_800_000_000L, 300_000_000L, limit))
        assertFalse(FlightRetentionPolicy.needsCleanup(9_100_000_000L, maximum = 11_000_000_000L))
    }
    @Test fun deletesOldestFirstAndNeverActiveEvenOverBudget() {
        val days = listOf(
            FlightRetentionPolicy.Day("today", today, 50, true),
            FlightRetentionPolicy.Day("newer", today.minusDays(1), 20, false),
            FlightRetentionPolicy.Day("old", today.minusDays(13), 20, false),
            FlightRetentionPolicy.Day("active", today.minusDays(12), 50, true)
        )
        assertEquals(listOf("old", "newer"), FlightRetentionPolicy.candidates(days, 140, 100, 30, today))
        assertEquals(listOf("old"), FlightRetentionPolicy.candidates(days, 140, 500, 7, today))
    }
    @Test fun retainsExactAgeBoundaryUnlessOverSize() {
        val days = listOf(FlightRetentionPolicy.Day("boundary", today.minusDays(7), 20, false))
        assertTrue(FlightRetentionPolicy.candidates(days, 20, 20, 7, today).isEmpty())
        assertEquals(listOf("boundary"), FlightRetentionPolicy.candidates(days, 20, 10, 7, today))
    }
    @Test fun archiveAgeIgnoresRecentDirectoryModification() {
        val oldDate = parseArchiveDirectoryDateMs("tracks-01Sep2026")!!
        val now = parseArchiveDirectoryDateMs("tracks-14Sep2026")!!
        val option = buildArchiveCleanupOption("tracks-01Sep2026", now, emptyList(), now, "tracks-14Sep2026")!!
        assertEquals(now - oldDate, option.ageMs)
    }
}

package org.ncssar.rid2caltopo.storage

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-18 S11U: SD card remounted read-only; the verdict flip-flopped and the pop-up flickered. */
class ArchiveWritabilityTrackerTest {
    private var now = 1_000_000L
    private val logs = mutableListOf<String>()
    private val tracker = ArchiveWritabilityTracker({ now }, { logs += it })
    private val events = mutableListOf<Pair<ArchiveWritabilityVerdict, ArchiveWritabilityVerdict>>()
    init { tracker.addListener { old, new -> events += old to new } }

    private val erofs = IOException("open failed: EROFS (Read-only file system)")

    @Test fun classifiesReadOnlyAndIoErrorsThroughTheCauseChain() {
        assertEquals(ArchiveWriteProblem.READ_ONLY, ArchiveWritabilityTracker.classify(erofs))
        assertEquals(ArchiveWriteProblem.READ_ONLY, ArchiveWritabilityTracker.classify(RuntimeException("wrapped", IllegalStateException("x", erofs))))
        assertEquals(ArchiveWriteProblem.IO_ERROR, ArchiveWritabilityTracker.classify(IOException("write failed: EIO (I/O error)")))
        assertEquals(ArchiveWriteProblem.IO_ERROR, ArchiveWritabilityTracker.classify(IOException("I/O error during copy")))
        assertNull(ArchiveWritabilityTracker.classify(IOException("Missing file mapping/stats.json")))
        assertNull(ArchiveWritabilityTracker.classify(IllegalStateException("Archive Dir changed during preparation")))
        assertNull(ArchiveWritabilityTracker.classify(IOException("VIDEOS are fine"))) // EIO only as a word
        assertNull(ArchiveWritabilityTracker.classify(null))
    }

    @Test fun readOnlyIsStickyAndOnlyOneEventPerStateChange() {
        assertTrue(tracker.writable)
        assertTrue(tracker.noteFailure(erofs, "flight storage check", removable = true))
        assertTrue(tracker.noteFailure(erofs, "flight storage check"))
        assertTrue(tracker.noteFailure(IOException("EIO"), "recording checkpoint"))
        assertFalse(tracker.writable)
        assertEquals(1, events.size)
        assertEquals(1, logs.size)
        val verdict = tracker.verdict
        assertEquals(ArchiveWriteProblem.READ_ONLY, verdict.problem)
        assertTrue(verdict.removable)
        assertEquals(1, verdict.generation)
        // Unrelated errors neither set nor clear the verdict.
        assertFalse(tracker.noteFailure(IOException("Missing file"), "scan"))
        assertFalse(tracker.writable)
    }

    @Test fun periodicRecheckRunsAtMostEveryFiveMinutesAndOnlyWhileNotWritable() {
        var probes = 0
        assertFalse(tracker.recheckDue(ArchiveRecheckTrigger.PERIODIC))
        tracker.recheck(ArchiveRecheckTrigger.PERIODIC) { probes++ }
        assertEquals(0, probes)
        tracker.noteFailure(erofs, "check")
        now += ArchiveWritabilityTracker.RECHECK_INTERVAL_MS - 1
        tracker.recheck(ArchiveRecheckTrigger.PERIODIC) { probes++; throw erofs }
        assertEquals(0, probes)
        now += 1
        tracker.recheck(ArchiveRecheckTrigger.PERIODIC) { probes++; throw erofs }
        assertEquals(1, probes)
        assertFalse(tracker.writable)
        // Still failing: no new event, and the next periodic probe waits another 5 minutes.
        assertEquals(1, events.size)
        now += 60_000
        tracker.recheck(ArchiveRecheckTrigger.PERIODIC) { probes++ }
        assertEquals(1, probes)
        now += ArchiveWritabilityTracker.RECHECK_INTERVAL_MS
        assertTrue(tracker.recheck(ArchiveRecheckTrigger.PERIODIC) { probes++ })
        assertEquals(2, probes)
        assertEquals(2, events.size)
        assertTrue(events.last().second.writable)
        assertEquals(2, tracker.verdict.generation)
    }

    @Test fun remountAndFolderPickAlwaysProbe() {
        tracker.noteFailure(erofs, "check")
        var probes = 0
        assertFalse(tracker.recheck(ArchiveRecheckTrigger.REMOUNT) { probes++; throw erofs })
        assertTrue(tracker.recheck(ArchiveRecheckTrigger.FOLDER_PICKED) { probes++ })
        assertEquals(2, probes)
        // A folder pick also checks a writable archive and can find a read-only one.
        assertFalse(tracker.recheck(ArchiveRecheckTrigger.FOLDER_PICKED) { throw IOException("Archive Dir refused a new file") })
        assertEquals(ArchiveWriteProblem.NOT_WRITABLE, tracker.verdict.problem)
    }

    @Test fun nothingButARecheckClearsTheVerdict() {
        tracker.noteFailure(erofs, "check")
        // Start-up estimates and canWrite() have no API here: only recheck() can clear it.
        repeat(10) { now += 1_000; assertFalse(tracker.writable) }
        tracker.markNotWritable(ArchiveWriteProblem.READ_ONLY, "volume mounted read-only", "mount state")
        assertEquals(1, events.size)
    }
}

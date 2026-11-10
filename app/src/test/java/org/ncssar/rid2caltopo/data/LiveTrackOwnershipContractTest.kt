package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers for one-flight / one-CalTopo-track ownership and duplicate archive policy. */
class LiveTrackOwnershipContractTest {

    @Test
    fun remoteIdFromFleetDeviceId_parsesSharedFleetKey() {
        assertEquals(
            "1581F8HGX255S00A0FZT",
            CaltopoMap.remoteIdFromFleetDeviceId(
                "FLEET:seF00gKlpO4MROcq8PzW8AuJ7A8k4WvQNp66U4Z7YFLkY-1581F8HGX255S00A0FZT"
            )
        )
        assertEquals("", CaltopoMap.remoteIdFromFleetDeviceId(""))
        assertEquals("", CaltopoMap.remoteIdFromFleetDeviceId("not-fleet"))
    }

    @Test
    fun duplicateArchivePolicy_archivesOwnZoneOrOrphanOnly() {
        // Peer lease active + not our mint → keep / adopt, do not archive.
        assertFalse(shouldArchiveDuplicate(createdByThisZone = false, peerLeaseActive = true))
        // Own-zone duplicate while publishing → archive.
        assertTrue(shouldArchiveDuplicate(createdByThisZone = true, peerLeaseActive = true))
        assertTrue(shouldArchiveDuplicate(createdByThisZone = true, peerLeaseActive = false))
        // Orphan with no peer lease → archive as stale.
        assertTrue(shouldArchiveDuplicate(createdByThisZone = false, peerLeaseActive = false))
    }

    @Test
    fun archivedShapeIsNotAdoptable() {
        val device = CaltopoSession.liveTrackDeviceId("DRONESHAPE", "connect-key")
        CaltopoMap.noteArchivedShape("already-shape")
        CaltopoMap.rememberAdoptableLiveTrack("already-shape", "old-title", device, "zone-bravo")
        assertFalse(CaltopoMap.isArchivedShape("still-live"))
        assertNull(CaltopoMap.peekAdoptableLiveTrack(device))
        CaltopoMap.rememberAdoptableLiveTrack("still-live", "live-title", device, "zone-bravo")
        CaltopoMap.noteArchivedShape("still-live")
        assertNull(CaltopoMap.takeAdoptableLiveTrack(device))
    }

    @Test
    fun descriptionAlreadyLinked_detectsShortVideoPath() {
        assertTrue(CaltopoArchiveDescription.alreadyContainsVideoLink(
            "https://r2c-tracker.com/v/8fiw1A"))
        assertFalse(CaltopoArchiveDescription.alreadyContainsVideoLink(""))
        assertFalse(CaltopoArchiveDescription.alreadyContainsVideoLink("no link yet"))
    }

    /**
     * Mirrors CaltopoMap.archiveDuplicateLiveTrackIfNeeded gate without CalTopo I/O.
     * Archive when created by this zone, or when there is no active peer owner lease.
     */
    private fun shouldArchiveDuplicate(createdByThisZone: Boolean, peerLeaseActive: Boolean): Boolean {
        if (!createdByThisZone && peerLeaseActive) return false
        return true
    }
}

/** Mirror of Apple CaltopoArchiveDescription.alreadyContainsVideoLink for Android parity checks. */
private object CaltopoArchiveDescription {
    fun alreadyContainsVideoLink(description: String?): Boolean =
        (description ?: "").contains("/v/")
}

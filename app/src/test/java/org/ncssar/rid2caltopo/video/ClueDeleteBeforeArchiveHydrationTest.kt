package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Clue delete must succeed while Archive Dir bulk hydration is still unfinished.
 * The directory-backed store (no app Context) uses local files only — the same
 * fallback OperationalStorage uses when the clues lane has nothing retained yet.
 */
class ClueDeleteBeforeArchiveHydrationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun deleteWorksBeforeFullArchiveHydration() {
        val root = folder.newFolder("clues")
        val store = AndroidClueStore.forDirectory(root)
        val record = AndroidClueRecord(
            id = "55818872-d191-4fe4-8d2e-453ea4103080",
            mapKey = "map:4J0LF02",
            lat = 39.15,
            lng = -121.13,
            alt = 100.0,
            title = "Delete me",
            description = "Local",
            createdAtMs = 1_000L,
            sourceDesignator = "1SAR7",
            imageFilename = "55818872.jpg",
            thumbnailFilename = "55818872-thumb.jpg",
            publishToCaltopo = true,
            destinationTeamId = "personal:BCL66J",
            uploadState = "pending",
        )
        store.saveEncoded(record, byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        assertTrue(store.imageFile(record).isFile)
        assertTrue(store.thumbnailFile(record).isFile)

        // Archive storage is intentionally unprepared (no Context → no OperationalStorage).
        assertTrue(store.delete(record.id))
        assertNull(store.record(record.id))
        assertFalse(store.imageFile(record).exists())
        assertFalse(store.thumbnailFile(record).exists())
    }
}

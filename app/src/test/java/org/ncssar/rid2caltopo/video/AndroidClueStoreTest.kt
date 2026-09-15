package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidClueStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savedClueReloadsForItsMapAfterStoreRecreation() {
        val root = temporaryFolder.newFolder("clues")
        val record = clueRecord(id = "clue-one", mapKey = "map:alpha")
        val image = byteArrayOf(1, 2, 3, 4)
        val thumbnail = byteArrayOf(5, 6)

        AndroidClueStore.forDirectory(root).saveEncoded(record, image, thumbnail)
        val reopened = AndroidClueStore.forDirectory(root)

        assertEquals(listOf(record), reopened.recordsForMap("map:alpha"))
        assertEquals(emptyList<AndroidClueRecord>(), reopened.recordsForMap("map:other"))
        assertArrayEquals(image, reopened.imageFile(record).readBytes())
        assertArrayEquals(thumbnail, reopened.thumbnailFile(record).readBytes())
    }

    @Test
    fun deleteRemovesIndexImageAndThumbnailButDoesNotAffectOtherClues() {
        val root = temporaryFolder.newFolder("clues")
        val store = AndroidClueStore.forDirectory(root)
        val deleted = clueRecord(id = "delete-me", mapKey = "map:alpha")
        val retained = clueRecord(id = "keep-me", mapKey = "map:alpha")
        store.saveEncoded(deleted, byteArrayOf(1), byteArrayOf(2))
        store.saveEncoded(retained, byteArrayOf(3), byteArrayOf(4))

        assertTrue(store.delete(deleted.id))

        assertFalse(store.imageFile(deleted).exists())
        assertFalse(store.thumbnailFile(deleted).exists())
        assertEquals(listOf(retained), AndroidClueStore.forDirectory(root).recordsForMap("map:alpha"))
    }

    @Test
    fun retentionRemovalHidesClueAndNextWritePrunesItsMetadata() {
        val root = temporaryFolder.newFolder("retention")
        val store = AndroidClueStore.forDirectory(root)
        val old = clueRecord("old", "map:alpha")
        store.saveEncoded(old, byteArrayOf(1), byteArrayOf(2))
        store.imageFile(old).delete()
        store.thumbnailFile(old).delete()
        assertTrue(store.recordsForMap("map:alpha").isEmpty())
        val current = clueRecord("new", "map:alpha")
        store.saveEncoded(current, byteArrayOf(3), byteArrayOf(4))
        assertEquals(listOf(current), AndroidClueStore.forDirectory(root).recordsForMap("map:alpha"))
    }

    private fun clueRecord(id: String, mapKey: String) = AndroidClueRecord(
        id = id,
        mapKey = mapKey,
        lat = 39.153,
        lng = -121.132,
        alt = 102.0,
        title = "Clue $id",
        description = "Found beside trail",
        createdAtMs = 1_000L,
        sourceDesignator = "1SAR7",
        imageFilename = "$id.jpg",
        thumbnailFilename = "$id-thumb.jpg",
        publishToCaltopo = true,
    )
}

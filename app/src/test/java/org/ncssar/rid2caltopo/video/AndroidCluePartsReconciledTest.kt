package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidCluePartsReconciledTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun published(id: String, reconciled: Boolean) = AndroidClueRecord(
        id = id,
        mapKey = "map:alpha",
        lat = 39.0,
        lng = -121.0,
        alt = 100.0,
        title = id,
        description = "note",
        createdAtMs = System.currentTimeMillis(),
        sourceDesignator = "AC1",
        imageFilename = "$id.jpg",
        thumbnailFilename = "$id-thumb.jpg",
        publishToCaltopo = true,
        destinationTeamId = "team-a",
        uploadState = "published",
        caltopoPartsReconciled = reconciled,
    )

    @Test
    fun successMarksPartsReconciledAndExcludesFromAutoReverify() {
        val root = temporaryFolder.newFolder("reconciled")
        val store = AndroidClueStore.forDirectory(root)
        val record = published("a", reconciled = false).copy(uploadState = "pending")
        store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        store.recordUploadResult(record.id, success = true)
        assertTrue(store.record(record.id)!!.caltopoPartsReconciled)
        assertTrue(store.recentlyPublishedForMap("alpha", "team-a", 0L).isEmpty())
    }

    @Test
    fun demotionClearsReconciledAndReturnsToPending() {
        val root = temporaryFolder.newFolder("demote")
        val store = AndroidClueStore.forDirectory(root)
        val record = published("b", reconciled = true)
        store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        store.recordUploadResult(record.id, success = false, error = "repair", terminal = false)
        val updated = store.record(record.id)!!
        assertEquals("pending", updated.uploadState)
        assertFalse(updated.caltopoPartsReconciled)
    }

    @Test
    fun unreconciledPublishedStillSelectedForOneShotReverify() {
        val root = temporaryFolder.newFolder("once")
        val store = AndroidClueStore.forDirectory(root)
        val record = published("c", reconciled = false)
        store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        assertEquals(listOf("c"), store.recentlyPublishedForMap("alpha", "team-a", 0L).map { it.id })
    }

    @Test
    fun retryClearsReconciledDurably() {
        val root = temporaryFolder.newFolder("retry")
        val store = AndroidClueStore.forDirectory(root)
        val record = published("d", reconciled = true)
        store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        store.clearPartsReconciledForRetry(listOf(record.id))
        assertFalse(store.record(record.id)!!.caltopoPartsReconciled)
        assertEquals(listOf("d"), store.recentlyPublishedForMap("alpha", "team-a", 0L).map { it.id })
    }
}

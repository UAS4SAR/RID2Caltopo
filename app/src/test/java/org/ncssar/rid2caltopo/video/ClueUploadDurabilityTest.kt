package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ncssar.rid2caltopo.data.ClueUploadBackoffTable

class ClueUploadDurabilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun clueRecord(id: String, mapKey: String) = AndroidClueRecord(
        id = id,
        mapKey = mapKey,
        lat = 39.0,
        lng = -121.0,
        alt = 100.0,
        title = id,
        description = "note",
        createdAtMs = 1_000L,
        sourceDesignator = "AC1",
        imageFilename = "$id.jpg",
        thumbnailFilename = "$id-thumb.jpg",
        publishToCaltopo = true,
        destinationTeamId = "team-a",
        uploadState = "pending",
        binding = testFlightBinding(1_000L),
    )

    @Test
    fun network5xxKeepsPendingAndAdvancesPerClueBackoff() {
        val root = temporaryFolder.newFolder("clue-5xx")
        val store = AndroidClueStore.forDirectory(root)
        val record = clueRecord("a", "map:alpha")
        store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        store.recordUploadResult(record.id, false, "HTTP 502", terminal = false)
        assertEquals("pending", store.record(record.id)!!.uploadState)
        assertEquals(listOf(record.id), store.pendingForMap("alpha", "team-a").map { it.id })

        val backoff = ClueUploadBackoffTable()
        assertEquals(2_000L, backoff.nextDelayMs(record.id))
        backoff.markDelayedUntil(record.id, 10_000L)
        assertTrue(backoff.isReady("other", 5_000L))
        assertEquals(false, backoff.isReady(record.id, 5_000L))
    }

    @Test
    fun client4xxKeepsDurableRetryableRecordAndNeverDeletes() {
        val root = temporaryFolder.newFolder("clue-4xx")
        val store = AndroidClueStore.forDirectory(root)
        val record = clueRecord("b", "map:alpha")
        val saved = store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        // 4xx alone is not permanent; keep retryable until reconcile proves presence.
        store.recordUploadResult(saved.id, false, "Forbidden", terminal = false)
        val kept = store.record(saved.id)!!
        assertEquals("pending", kept.uploadState)
        assertEquals("Forbidden", kept.lastUploadError)
        assertEquals(listOf(saved.id), store.pendingForMap("alpha", "team-a").map { it.id })
        assertEquals(listOf(saved.id), store.unpublishedForMap("alpha", "team-a").map { it.id })
        assertTrue(store.imageFile(kept).isFile)

        store.recordUploadResult(saved.id, false, "Forbidden", terminal = true)
        val failed = store.record(saved.id)!!
        assertEquals("failed", failed.uploadState)
        assertEquals(listOf(saved.id), store.pendingForMap("alpha", "team-a").map { it.id })
        assertTrue(store.imageFile(failed).isFile)
    }

    @Test
    fun pendingSurvivesSimulatedRestartIndexReload() {
        val root = temporaryFolder.newFolder("clue-restart")
        val store = AndroidClueStore.forDirectory(root)
        val record = clueRecord("c", "map:alpha").copy(uploadState = "uploading")
        store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        // Simulate kill mid-upload then cold reopen.
        val reopened = AndroidClueStore.forDirectory(root)
        assertEquals(listOf(record.id), reopened.pendingForMap("alpha", "team-a").map { it.id })
        assertEquals("pending", reopened.record(record.id)!!.uploadState)
    }
}

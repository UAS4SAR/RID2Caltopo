package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ncssar.rid2caltopo.storage.ClueProjectionMerge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AndroidClueDeleteTombstoneTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun record(id: String, state: String = "published") = AndroidClueRecord(
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
        uploadState = state,
        caltopoPartsReconciled = state == "published",
    )

    @Test
    fun deleteTombstonesAndRefusesInFlightUploadResult() {
        val root = temporaryFolder.newFolder("tomb")
        val store = AndroidClueStore.forDirectory(root)
        val clue = record("fda322b4")
        store.saveEncoded(clue, byteArrayOf(1), byteArrayOf(2))
        assertTrue(store.delete(clue.id))
        assertTrue(store.isUserDeleted(clue.id))
        assertNull(store.record(clue.id))
        assertFalse(File(root, clue.imageFilename).exists())
        assertFalse(File(root, clue.thumbnailFilename).exists())
        // In-flight reconcile/publish success must not resurrect the row.
        store.recordUploadResult(clue.id, success = true)
        assertNull(store.record(clue.id))
        assertTrue(File(root, AndroidClueStore.TOMBSTONE_FILENAME).isFile)
    }

    @Test
    fun deleteDuringPrepareKeepsTombstoneAcrossReload() {
        val root = temporaryFolder.newFolder("prepare")
        val store = AndroidClueStore.forDirectory(root)
        store.saveEncoded(record("1eb73175"), byteArrayOf(1), byteArrayOf(2))
        assertTrue(store.delete("1eb73175"))
        store.invalidateLoaded()
        assertTrue(store.isUserDeleted("1eb73175"))
        assertNull(store.record("1eb73175"))
        assertTrue(store.recordsForMap("map:alpha").isEmpty())
    }

    @Test
    fun archiveMergeDoesNotResurrectTombstonedId() {
        val root = temporaryFolder.newFolder("merge")
        val archive = File(root, "archive").apply { mkdirs() }
        val working = File(root, "working").apply { mkdirs() }
        fun writeIndex(dir: File, ids: List<String>) {
            val array = JSONArray()
            ids.forEachIndexed { index, id ->
                array.put(
                    JSONObject()
                        .put("id", id)
                        .put("createdAtMs", 1_000L + index)
                        .put("imageFilename", "$id.jpg")
                        .put("thumbnailFilename", "$id-thumb.jpg")
                        .put("lat", 39.0)
                        .put("lng", -121.0)
                        .put("mapKey", "map:alpha")
                        .put("title", id)
                        .put("description", "")
                        .put("sourceDesignator", "AC1")
                        .put("publishToCaltopo", true)
                        .put("uploadState", "published"),
                )
                File(dir, "$id.jpg").writeBytes(byteArrayOf(1))
                File(dir, "$id-thumb.jpg").writeBytes(byteArrayOf(2))
            }
            File(dir, "clues.json").writeText(array.toString(2))
        }
        writeIndex(archive, listOf("keep", "fda322b4"))
        writeIndex(working, listOf("keep"))
        File(working, AndroidClueStore.TOMBSTONE_FILENAME).writeText(JSONArray().put("fda322b4").toString(2))
        val destination = File(root, "destination").apply { mkdirs() }
        ClueProjectionMerge.mergeTree(archive, destination, preferExisting = true)
        ClueProjectionMerge.mergeTree(working, destination, preferExisting = false)
        val ids = JSONArray(File(destination, "clues.json").readText()).let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
        }
        assertEquals(setOf("keep"), ids)
        assertFalse(File(destination, "fda322b4.jpg").exists())
    }

    @Test
    fun legacyPublishedNotInPendingAutoUpload() {
        val root = temporaryFolder.newFolder("legacy")
        val store = AndroidClueStore.forDirectory(root)
        store.saveEncoded(
            record("legacy-pub", state = "published").copy(caltopoPartsReconciled = false),
            byteArrayOf(1),
            byteArrayOf(2),
        )
        assertTrue(store.pendingForMap("alpha", "team-a").isEmpty())
        assertEquals(listOf("legacy-pub"), store.recentlyPublishedForMap("alpha", "team-a", 0L).map { it.id })
    }

    @Test
    fun remotelyDeletedConfirmedExcludedFromPendingAndReverify() {
        val root = temporaryFolder.newFolder("remote")
        val store = AndroidClueStore.forDirectory(root)
        store.saveEncoded(record("gone"), byteArrayOf(1), byteArrayOf(2))
        store.markRemotelyDeleted("gone")
        val updated = store.record("gone")!!
        assertTrue(updated.caltopoRemotelyDeleted)
        assertTrue(updated.caltopoPartsReconciled)
        assertTrue(store.pendingForMap("alpha", "team-a").isEmpty())
        assertTrue(store.recentlyPublishedForMap("alpha", "team-a", 0L).isEmpty())
    }
}

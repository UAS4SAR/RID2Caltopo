package org.ncssar.rid2caltopo.video

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ncssar.rid2caltopo.app.todayArchiveDirectoryName
import java.io.File
import java.util.Date

class AndroidClueFlightFolderMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun writeFlatIndex(root: File, vararg rows: JSONObject) {
        val array = JSONArray()
        rows.forEach { array.put(it) }
        File(root, "clues.json").writeText(array.toString(2))
    }

    private fun row(
        id: String,
        createdAtMs: Long,
        uploadState: String,
        designator: String = "1SAR7",
        flightId: String? = null,
    ): JSONObject {
        val json = JSONObject()
            .put("id", id)
            .put("mapKey", "map:alpha")
            .put("lat", 39.0)
            .put("lng", -121.0)
            .put("alt", 100.0)
            .put("title", id)
            .put("description", "note")
            .put("createdAtMs", createdAtMs)
            .put("sourceDesignator", designator)
            .put("imageFilename", "$id.jpg")
            .put("thumbnailFilename", "$id-thumb.jpg")
            .put("publishToCaltopo", true)
            .put("destinationTeamId", "team-a")
            .put("uploadState", uploadState)
            .put("caltopoPartsReconciled", uploadState == "published")
        if (flightId != null) {
            json.put(
                "binding",
                JSONObject()
                    .put("aircraftID", "RID-1")
                    .put("flightID", flightId)
                    .put("captureTimeMs", createdAtMs)
                    .put("captureTimeSource", "stream-pts")
                    .put("captureReceivedAtMs", createdAtMs),
            )
        }
        return json
    }

    @Test
    fun migratesFlatCluesIntoDayFoldersIdempotently() {
        val root = temporaryFolder.newFolder("clues")
        val at = 1_783_296_000_000L
        val day = todayArchiveDirectoryName(Date(at))
        writeFlatIndex(
            root,
            row("pending-a", at, "pending"),
            row("published-b", at, "published"),
        )
        File(root, "pending-a.jpg").writeBytes(byteArrayOf(1, 2))
        File(root, "pending-a-thumb.jpg").writeBytes(byteArrayOf(3))
        File(root, "published-b.jpg").writeBytes(byteArrayOf(4, 5))
        File(root, "published-b-thumb.jpg").writeBytes(byteArrayOf(6))

        AndroidClueFlightFolderMigration.resetForTests(null, root)
        assertEquals(2, AndroidClueFlightFolderMigration.migrateRoot(root))

        assertFalse(File(root, "clues.json").exists())
        assertFalse(File(root, "pending-a.jpg").exists())
        assertTrue(File(root, "$day/pending-a.jpg").isFile)
        assertTrue(File(root, "$day/published-b.jpg").isFile)
        val dayIndex = JSONArray(File(root, "$day/clues.json").readText())
        assertEquals(2, dayIndex.length())
        val byId = (0 until dayIndex.length()).associate {
            val o = dayIndex.getJSONObject(it)
            o.getString("id") to o
        }
        assertEquals("pending", byId.getValue("pending-a").getString("uploadState"))
        assertEquals("published", byId.getValue("published-b").getString("uploadState"))
        assertTrue(byId.getValue("published-b").optBoolean("caltopoPartsReconciled"))
        assertEquals("$day/pending-a.jpg", byId.getValue("pending-a").getString("imageFilename"))

        // Idempotent: flag file skips work; published stays published.
        assertEquals(0, AndroidClueFlightFolderMigration.migrateRoot(root))
        val again = JSONArray(File(root, "$day/clues.json").readText())
        val published = (0 until again.length()).map { again.getJSONObject(it) }
            .first { it.getString("id") == "published-b" }
        assertEquals("published", published.getString("uploadState"))
    }

    @Test
    fun photoMissingRowsStayIndexedAndConfirmedNotRequeued() {
        val root = temporaryFolder.newFolder("clues-missing")
        val at = 1_783_296_000_000L
        val day = todayArchiveDirectoryName(Date(at))
        writeFlatIndex(
            root,
            row("ghost", at, "pending"),
            row("done", at, "published").put("caltopoMarkerId", "marker-1"),
        )
        File(root, "done.jpg").writeBytes(byteArrayOf(9))
        File(root, "done-thumb.jpg").writeBytes(byteArrayOf(8))

        AndroidClueFlightFolderMigration.resetForTests(null, root)
        AndroidClueFlightFolderMigration.migrateRoot(root)

        val store = AndroidClueStore.forDirectory(root)
        val ghost = store.record("ghost")!!
        assertEquals("$day/ghost.jpg", ghost.imageFilename)
        assertFalse(store.imageFile(ghost).isFile)
        assertEquals("pending", ghost.uploadState)
        assertTrue(store.allRecords().any { it.id == "ghost" })

        val done = store.record("done")!!
        assertEquals("published", done.uploadState)
        assertTrue(done.uploadConfirmed())
        assertTrue(store.pendingForMap("alpha", "team-a").none { it.id == "done" })
    }

    @Test
    fun flightIdHintSelectsArchiveDayWhenProvided() {
        val root = temporaryFolder.newFolder("clues-hint")
        val ownership = 1_783_296_000_000L
        val ownershipDay = todayArchiveDirectoryName(Date(ownership))
        val archiveDay = "tracks-01Oct2026"
        require(archiveDay != ownershipDay)
        writeFlatIndex(root, row("bound", ownership, "pending", flightId = "flight-9"))
        File(root, "bound.jpg").writeBytes(byteArrayOf(1))
        File(root, "bound-thumb.jpg").writeBytes(byteArrayOf(2))

        AndroidClueFlightFolderMigration.resetForTests(null, root)
        AndroidClueFlightFolderMigration.migrateRoot(root, mapOf("flight-9" to archiveDay))

        assertTrue(File(root, "$archiveDay/bound.jpg").isFile)
        assertFalse(File(root, "$ownershipDay/bound.jpg").exists())
        val index = JSONArray(File(root, "$archiveDay/clues.json").readText())
        assertEquals("$archiveDay/bound.jpg", index.getJSONObject(0).getString("imageFilename"))
    }
}

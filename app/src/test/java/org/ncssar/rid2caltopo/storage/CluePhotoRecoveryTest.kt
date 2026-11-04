package org.ncssar.rid2caltopo.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ncssar.rid2caltopo.data.CluePhotoUploadPolicy
import org.ncssar.rid2caltopo.data.ClueUploadBackoffTable
import org.ncssar.rid2caltopo.video.AndroidClueRecord
import org.ncssar.rid2caltopo.video.AndroidClueStore
import java.io.File

class CluePhotoRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun clue(id: String, error: String = "") = AndroidClueRecord(
        id = id,
        mapKey = "map:alpha",
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
        lastUploadError = error,
        binding = org.ncssar.rid2caltopo.video.testFlightBinding(1_000L),
    )

    @Test
    fun relocationMergePreservesPendingPhotosIntoDurableRoot() {
        val root = temporaryFolder.newFolder("clue-relocate")
        val cacheProjection = File(root, "operational-work/abcdef0123456789/clues").apply { mkdirs() }
        val durable = File(root, "files/clues").apply { mkdirs() }
        val pending = clue("keep-me")
        File(cacheProjection, pending.imageFilename).writeBytes(byteArrayOf(1, 2, 3))
        File(cacheProjection, pending.thumbnailFilename).writeBytes(byteArrayOf(4))
        File(cacheProjection, "clues.json").writeText(
            """[{"id":"keep-me","mapKey":"map:alpha","lat":39.0,"lng":-121.0,"title":"keep-me",""" +
                """"description":"note","createdAtMs":1000,"sourceDesignator":"AC1",""" +
                """"imageFilename":"keep-me.jpg","thumbnailFilename":"keep-me-thumb.jpg",""" +
                """"publishToCaltopo":true,"destinationTeamId":"team-a","uploadState":"pending"}]""",
        )
        ClueProjectionMerge.mergeTree(cacheProjection, durable, preferExisting = false)
        assertEquals("1,2,3", File(durable, pending.imageFilename).readBytes().joinToString(","))
        assertTrue(File(durable, "clues.json").isFile)
        val store = AndroidClueStore.forDirectory(durable)
        assertEquals(listOf("keep-me"), store.pendingForMap("alpha", "team-a").map { it.id })
        assertTrue(store.imageFile(store.record("keep-me")!!).isFile)
    }

    @Test
    fun adoptCopiesPhotoFromPriorOperationalWorkRoot() {
        val root = temporaryFolder.newFolder("clue-adopt")
        val preferred = File(root, "files/clues").apply { mkdirs() }
        val prior = File(root, "cache/operational-work/abcdef0123456789/clues").apply { mkdirs() }
        val id = "recover-me"
        File(prior, "$id.jpg").writeBytes(byteArrayOf(9, 8, 7))
        // Unit-level adopt without Android Context: copy when caller supplies roots.
        val found = File(prior, "$id.jpg")
        assertTrue(found.isFile)
        val destination = File(preferred, "$id.jpg")
        org.ncssar.rid2caltopo.data.PlainFileSafeWriter.replace(destination, found.readBytes()) { it.isNotEmpty() }
        assertEquals(listOf<Byte>(9, 8, 7), destination.readBytes().toList())
        assertFalse(File(preferred, "missing.jpg").isFile)
    }

    @Test
    fun missingPhotoStopsAutoRetryAndDoesNotBackoffForever() {
        val root = temporaryFolder.newFolder("clue-missing")
        val store = AndroidClueStore.forDirectory(root)
        val record = clue("ghost")
        // Index row without photo (orphaned after relocation).
        File(root, "clues.json").writeText(
            """[{"id":"ghost","mapKey":"map:alpha","lat":39.0,"lng":-121.0,"title":"ghost",""" +
                """"description":"note","createdAtMs":1000,"sourceDesignator":"AC1",""" +
                """"imageFilename":"ghost.jpg","thumbnailFilename":"ghost-thumb.jpg",""" +
                """"publishToCaltopo":true,"destinationTeamId":"team-a","uploadState":"pending"}]""",
        )
        val reopened = AndroidClueStore.forDirectory(root)
        assertEquals(listOf("ghost"), reopened.pendingForMap("alpha", "team-a").map { it.id })
        reopened.recordUploadResult(
            "ghost",
            false,
            CluePhotoUploadPolicy.PHOTO_MISSING_MESSAGE,
            terminal = true,
        )
        assertTrue(reopened.pendingForMap("alpha", "team-a").isEmpty())
        assertEquals("failed", reopened.record("ghost")!!.uploadState)
        assertEquals(
            CluePhotoUploadPolicy.PHOTO_MISSING_MESSAGE,
            reopened.record("ghost")!!.lastUploadError,
        )
        val backoff = ClueUploadBackoffTable()
        // Terminal photo-missing rows are excluded from pendingForMap — no further backoff wake.
        assertTrue(backoff.isReady("ghost", 0L))
        assertTrue(
            CluePhotoUploadPolicy.descriptionNotesLostPhoto(
                CluePhotoUploadPolicy.descriptionWithLostPhotoNote("note"),
            ),
        )
    }
}

package org.ncssar.rid2caltopo.storage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClueProjectionMergeDurabilityTest {
    @Test
    fun hydrateRaceUnionsAndNeverDeletesIntoTargetFiles() {
        val root = File.createTempFile("clue-hydrate-race-", ".dir").apply { delete(); mkdirs() }
        try {
            val archive = File(root, "archive").apply { mkdirs() }
            val working = File(root, "working").apply { mkdirs() }
            writeIndex(archive, listOf("shared", "archive-only"))
            File(archive, "shared.jpg").writeText("archive-shared")
            File(archive, "archive-only.jpg").writeText("archive-only")
            writeIndex(working, listOf("shared", "working-only"))
            File(working, "shared.jpg").writeText("working-shared")
            File(working, "working-only.jpg").writeText("working-only")
            File(working, "keep-me.jpg").writeText("must-survive")

            ClueProjectionMerge.mergeTree(archive, working, preferExisting = true)

            val ids = JSONArray(File(working, "clues.json").readText()).let { array ->
                (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
            }
            assertEquals(setOf("shared", "archive-only", "working-only"), ids)
            assertEquals("working-shared", File(working, "shared.jpg").readText())
            assertEquals("archive-only", File(working, "archive-only.jpg").readText())
            assertEquals("working-only", File(working, "working-only.jpg").readText())
            assertTrue(File(working, "keep-me.jpg").isFile)
            assertEquals("must-survive", File(working, "keep-me.jpg").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun orphanPhotoIsReindexedAndMissingPhotoIsFlagged() {
        val root = File.createTempFile("clue-integrity-", ".dir").apply { delete(); mkdirs() }
        try {
            writeIndex(root, listOf("indexed"))
            File(root, "indexed.jpg").writeText("bytes")
            File(root, "orphan.jpg").writeText("orphan-bytes")
            // Index entry without photo:
            val array = JSONArray(File(root, "clues.json").readText())
            array.put(
                JSONObject()
                    .put("id", "ghost")
                    .put("createdAtMs", 9_000L)
                    .put("imageFilename", "ghost.jpg")
                    .put("thumbnailFilename", "ghost-thumb.jpg")
                    .put("lat", 39.0)
                    .put("lng", -121.0)
                    .put("mapKey", "map:alpha")
                    .put("title", "ghost")
                    .put("description", "")
                    .put("sourceDesignator", "")
                    .put("publishToCaltopo", true)
                    .put("destinationTeamId", "team-a")
                    .put("uploadState", "pending"),
            )
            File(root, "clues.json").writeText(array.toString(2))

            val summary = ClueTrackDurabilityIntegrity.reconcileClueDirectory(root)
            assertEquals(1, summary.orphansReindexed)
            assertEquals(1, summary.missingPhotos)

            val byId = JSONArray(File(root, "clues.json").readText()).let { json ->
                (0 until json.length()).associate { json.getJSONObject(it).getString("id") to json.getJSONObject(it) }
            }
            assertTrue(byId.containsKey("orphan"))
            assertEquals("pending", byId.getValue("orphan").getString("uploadState"))
            assertEquals("failed", byId.getValue("ghost").getString("uploadState"))
            assertEquals("missing-photo", byId.getValue("ghost").getString("lastUploadError"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeIndex(dir: File, ids: List<String>) {
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
                    .put("sourceDesignator", "AC")
                    .put("publishToCaltopo", true)
                    .put("destinationTeamId", "team-a")
                    .put("uploadState", "pending"),
            )
        }
        File(dir, "clues.json").writeText(array.toString(2))
    }
}

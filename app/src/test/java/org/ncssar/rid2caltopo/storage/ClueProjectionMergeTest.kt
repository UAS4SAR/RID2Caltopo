package org.ncssar.rid2caltopo.storage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClueProjectionMergeTest {
    @Test
    fun clueSavedAfterHydrationStartsSurvivesProjectionChangedRetry() {
        val root = File.createTempFile("clue-merge-", ".dir").apply { delete(); mkdirs() }
        try {
            val archive = File(root, "archive").apply { mkdirs() }
            val working = File(root, "working").apply { mkdirs() }
            // Archive snapshot taken before the in-flight saves (4 clues).
            writeIndex(archive, listOf("old-1", "old-2", "old-3", "old-4"))
            File(archive, "old-1.jpg").writeText("a1")
            File(archive, "old-2.jpg").writeText("a2")
            File(archive, "old-3.jpg").writeText("a3")
            File(archive, "old-4.jpg").writeText("a4")
            // Working projection gained two clues while hydrate/reuse was in flight.
            writeIndex(working, listOf("old-1", "old-2", "old-3", "old-4", "a17ba8f2", "0a2b0800"))
            File(working, "old-1.jpg").writeText("a1")
            File(working, "old-2.jpg").writeText("a2")
            File(working, "old-3.jpg").writeText("a3")
            File(working, "old-4.jpg").writeText("a4")
            File(working, "a17ba8f2.jpg").writeText("new-cz")
            File(working, "0a2b0800.jpg").writeText("new-c")

            // Retry-without-reuse previously rebuilt from archive only and discarded working.
            // Merge keeps archive fills and working wins for overlapping/newer ids.
            val destination = File(root, "destination").apply { mkdirs() }
            ClueProjectionMerge.mergeTree(archive, destination, preferExisting = true)
            ClueProjectionMerge.mergeTree(working, destination, preferExisting = false)

            val ids = JSONArray(File(destination, "clues.json").readText()).let { array ->
                (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
            }
            assertEquals(
                setOf("old-1", "old-2", "old-3", "old-4", "a17ba8f2", "0a2b0800"),
                ids,
            )
            assertEquals("new-cz", File(destination, "a17ba8f2.jpg").readText())
            assertEquals("new-c", File(destination, "0a2b0800.jpg").readText())
            assertTrue(File(destination, "old-1.jpg").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mismatchedCopiesAreKeptWhenPreferringExisting() {
        val root = File.createTempFile("clue-conflict-", ".dir").apply { delete(); mkdirs() }
        try {
            val archive = File(root, "archive").apply { mkdirs() }
            val working = File(root, "working").apply { mkdirs() }
            File(archive, "same.jpg").writeText("archive-bytes")
            File(working, "same.jpg").writeText("working-bytes")
            val conflicts = mutableListOf<String>()
            ClueProjectionMerge.mergeTree(archive, working, preferExisting = true) { conflicts.add(it) }
            assertEquals(listOf("same.jpg"), conflicts)
            assertEquals("working-bytes", File(working, "same.jpg").readText())
            assertEquals("archive-bytes", File(archive, "same.jpg").readText())
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
                    .put("lat", 39.0)
                    .put("lng", -121.0),
            )
        }
        File(dir, "clues.json").writeText(array.toString(2))
    }
}

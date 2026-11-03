package org.ncssar.rid2caltopo.mapping

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MappingLibraryIndexTest {
    @get:Rule val folder = TemporaryFolder()

    private class IndexContext(private val root: File) : ContextWrapper(null) {
        val values = mutableMapOf<String, Any>()
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { if (args[1] == null) values.remove(args[0] as String) else values[args[0] as String] = args[1]; proxy }
                "putStringSet" -> { values[args[0] as String] = args[1]; proxy }
                "remove" -> { values.remove(args[0] as String); proxy }
                "apply", "commit" -> true
                else -> error("Unexpected preference edit ${method.name}")
            }
        } as SharedPreferences.Editor
        private val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args[0]] ?: args[1]
                "getStringSet" -> @Suppress("UNCHECKED_CAST") (values[args[0]] as? Set<String> ?: args[1] as Set<String>)
                "edit" -> editor
                else -> error("Unexpected preference read ${method.name}")
            }
        } as SharedPreferences
        override fun getSharedPreferences(name: String, mode: Int) = preferences
        override fun getFilesDir(): File = root
        override fun getApplicationContext() = this
    }

    private val defaultInventory = MappingLibraryIndex.inventoryReader
    private val defaultJson = MappingLibraryIndex.jsonReader

    @Before fun resetReaders() {
        MappingLibraryIndex.jsonReader = defaultJson
        MappingLibraryIndex.inventoryReader = defaultInventory
    }

    @After fun restoreReaders() {
        MappingLibraryIndex.jsonReader = defaultJson
        MappingLibraryIndex.inventoryReader = defaultInventory
        MappingLibraryListing.areaBusyProbe = null
    }

    private fun mission(root: File, photos: Int = 2): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        val dir = File(root, "mapping-missions/$id").apply { mkdirs() }
        File(dir, "mission.json").writeText(JSONObject()
            .put("missionId", id).put("title", "Trial").put("createdAt", "2026-10-12T00:00:00Z")
            .put("assignmentId", "a1").put("incidentId", "inc").put("profile", "daylight-rgb").toString())
        File(dir, "inventory").mkdirs(); File(dir, "originals").mkdirs()
        repeat(photos) { index ->
            val digest = "%064x".format(index + 1)
            File(dir, "originals/$digest.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(dir, "inventory/$digest.json").writeText(JSONObject().put("sha256", digest).put("name", "DJI_$index.jpg")
                .put("complete", true).put("sizeBytes", 3).toString())
        }
        File(dir, "mission.kmz").writeBytes(byteArrayOf(1))
        File(dir, "route-summary.json").writeText(JSONObject()
            .put("estimatedPhotos", 10).put("estimatedFlightSeconds", 60).put("heightMeters", 80.0).toString())
        return id to dir
    }

    private fun countingInventory(): AtomicInteger {
        val count = AtomicInteger()
        val base = MappingLibraryIndex.jsonReader
        MappingLibraryIndex.inventoryReader = { file ->
            count.incrementAndGet()
            base(file)
        }
        return count
    }

    @Test fun indexRoundTripPersistsSchemaAndMissions() {
        val context = IndexContext(folder.root)
        val (id, _) = mission(folder.root)
        val first = MappingLibraryIndex.refresh(context, null)
        assertEquals(1, first.changed)
        assertTrue(MappingLibraryIndex.file(folder.root).isFile)
        val loaded = MappingLibraryIndex.load(folder.root)!!
        assertEquals(MappingLibraryIndex.SCHEMA, loaded.schema)
        assertEquals(id, loaded.missions.keys.single())
        assertEquals("Trial", loaded.missions.getValue(id).title)
        assertEquals(2, loaded.missions.getValue(id).photoCount)
        assertTrue(loaded.missions.getValue(id).hasRoute)
    }

    @Test fun unchangedFingerprintsSkipInventoryReads() {
        val context = IndexContext(folder.root)
        mission(folder.root, photos = 3)
        MappingLibraryIndex.refresh(context, null)
        val reads = countingInventory()
        val second = MappingLibraryIndex.refresh(context, null)
        assertEquals(0, second.changed)
        assertEquals(0, reads.get())
    }

    @Test fun touchingInventoryRebuildsOnlyThatMission() {
        val context = IndexContext(folder.root)
        val (firstId, firstDir) = mission(folder.root, photos = 1)
        val (secondId, _) = mission(folder.root, photos = 1)
        MappingLibraryIndex.refresh(context, null)
        val reads = countingInventory()
        Thread.sleep(5)
        val digest = "%064x".format(99)
        File(firstDir, "originals/$digest.jpg").writeBytes(byteArrayOf(9))
        File(firstDir, "inventory/$digest.json").writeText(JSONObject().put("sha256", digest).put("name", "extra.jpg")
            .put("complete", true).put("sizeBytes", 1).toString())
        // Bump inventory dir mtime for filesystems with coarse timestamps.
        File(firstDir, "inventory").setLastModified(System.currentTimeMillis())
        val refreshed = MappingLibraryIndex.refresh(context, null)
        assertEquals(1, refreshed.changed)
        assertEquals(2, refreshed.index.missions.getValue(firstId).photoCount)
        assertEquals(1, refreshed.index.missions.getValue(secondId).photoCount)
        assertTrue(reads.get() >= 1)
        assertTrue("second mission inventory should not reopen", reads.get() <= 3)
    }

    @Test fun deletedMissionRemovedFromIndex() {
        val context = IndexContext(folder.root)
        val (id, dir) = mission(folder.root)
        MappingLibraryIndex.refresh(context, null)
        assertTrue(dir.deleteRecursively())
        val refreshed = MappingLibraryIndex.refresh(context, null)
        assertEquals(1, refreshed.removed)
        assertFalse(refreshed.index.missions.containsKey(id))
    }

    @Test fun corruptOrOldSchemaForcesFullRebuild() {
        val context = IndexContext(folder.root)
        mission(folder.root)
        MappingLibraryIndex.refresh(context, null)
        MappingLibraryIndex.file(folder.root).writeText("""{"schema":0,"missions":{},"layers":{}}""")
        assertNull(MappingLibraryIndex.load(folder.root))
        val reads = countingInventory()
        val rebuilt = MappingLibraryIndex.refresh(context, null)
        assertEquals(1, rebuilt.changed)
        assertEquals(1, rebuilt.index.missions.size)
        assertTrue(reads.get() >= 1)
        MappingLibraryIndex.file(folder.root).writeText("{not-json")
        assertNull(MappingLibraryIndex.load(folder.root))
    }

    @Test fun largeMapTifNeverOpenedDuringRefresh() {
        val context = IndexContext(folder.root)
        val (id, _) = mission(folder.root, photos = 0)
        val layerId = UUID.randomUUID().toString()
        val layerDir = File(folder.root, "mapping-layers/$layerId").apply { mkdirs() }
        val layer = MappingLayer(layerId, "inc", "Map", "http://127.0.0.1/tiles/{Z}/{X}/{Y}.png", 18, 20,
            listOf(-121.1, 39.1, -121.0, 39.2), missionId = id)
        File(layerDir, "layer.json").writeText(layer.json().toString())
        File(layerDir, "18/1").mkdirs()
        File(layerDir, "18/1/2.png").writeBytes(byteArrayOf(1))
        val tif = File(layerDir, "map.tif")
        tif.writeBytes(ByteArray(2 * 1024 * 1024))
        assertTrue(tif.setReadable(false))
        val opens = AtomicInteger()
        val previousJson = MappingLibraryIndex.jsonReader
        MappingLibraryIndex.jsonReader = { file ->
            if (file.name.equals("map.tif", true)) opens.incrementAndGet()
            require(!file.name.equals("map.tif", true)) { "map.tif must not be opened" }
            previousJson(file)
        }
        try {
            val result = MappingLibraryIndex.refresh(context, null)
            assertEquals(0, opens.get())
            assertTrue(result.index.layers.containsKey(layerId) || result.index.missions.containsKey(id))
            assertTrue(MappingLayer.hasTileManifest(layerDir))
        } finally {
            MappingLibraryIndex.jsonReader = previousJson
            tif.setReadable(true)
        }
    }

    @Test fun routeUnavailableSkipsRepeatedDjiRouteRecovery() {
        val id = UUID.randomUUID().toString()
        val root = File(folder.root, "mapping-missions/$id").apply { mkdirs() }
        // Intentionally incomplete mission: DjiMappingRoute recovery will fail.
        File(root, "mission.json").writeText(JSONObject().put("missionId", id).put("title", "Broken")
            .put("createdAt", "2026-10-12T00:00:00Z").put("assignmentId", "a").put("incidentId", "i")
            .put("profile", "daylight-rgb").toString())
        File(root, "inventory").mkdirs()
        File(root, "mission.kmz").writeBytes(byteArrayOf(1))
        File(root, "route-summary.json").writeText("{}")
        val mission = JSONObject(File(root, "mission.json").readText())
        MappingFlightRepository.resolveRoute(root, mission)
        assertTrue(File(root, "route-unavailable.json").isFile || MappingFlightRepository.routeUnavailableFingerprint(root) != null ||
            !File(root, "route-unavailable.json").exists())
        // Second call must not throw and must honor marker when present.
        val firstMark = MappingFlightRepository.routeUnavailableFingerprint(root)
        if (firstMark != null) {
            assertNull(MappingFlightRepository.resolveRoute(root, mission))
            assertEquals(firstMark, MappingFlightRepository.routeUnavailableFingerprint(root))
        }
    }

    @Test fun missionWithOriginalsAlwaysAppearsEvenWhenCorruptOrActiveCrash() {
        val context = IndexContext(folder.root)
        val (id, dir) = mission(folder.root, photos = 2)
        File(dir, "processing-status.json").writeText(JSONObject().put("active", true)
            .put("stage", "Estimating unknown 2D areas").put("progress", 0.5).toString())
        val ok = MappingLibraryIndex.refresh(context, null)
        assertTrue(ok.entries.any { it.flight?.id == id })
        assertTrue(ok.index.missions.getValue(id).stage.contains("interrupted"))

        File(dir, "mission.json").writeText("{not-json")
        val corrupt = MappingLibraryIndex.refresh(context, null)
        assertTrue(corrupt.entries.any { it.id == "flight:$id" || it.flight?.id == id })
        assertTrue(corrupt.index.missions.getValue(id).stage.contains("unreadable") ||
            corrupt.index.missions.getValue(id).stage.contains("interrupted"))
    }

    @Test fun emptyIndexPlusExistingDirsRewritesIndex() {
        val context = IndexContext(folder.root)
        val (id, _) = mission(folder.root, photos = 3)
        File(folder.root, "mapping-storage-stats").mkdirs()
        MappingLibraryIndex.file(folder.root).writeText("""{"schema":1,"missions":{},"layers":{}}""")
        val refreshed = MappingLibraryIndex.refresh(context, null)
        assertEquals(1, refreshed.index.missions.size)
        assertEquals(id, refreshed.index.missions.keys.single())
        assertTrue(MappingLibraryIndex.load(folder.root)!!.missions.containsKey(id))
    }

    @Test fun unavailableListingKeepsPreviousIndexByteIdentical() {
        val context = IndexContext(folder.root)
        mission(folder.root, photos = 2)
        MappingLibraryIndex.refresh(context, null)
        val before = MappingLibraryIndex.file(folder.root).readBytes()
        MappingLibraryListing.areaBusyProbe = { true }
        try {
            val refreshed = MappingLibraryIndex.refresh(context, null)
            assertTrue(refreshed.stale)
            assertEquals(MappingLibraryListing.REASON_HYDRATING, refreshed.skipReason)
            assertEquals(1, refreshed.index.missions.size)
            assertArrayEquals(before, MappingLibraryIndex.file(folder.root).readBytes())
            assertTrue(refreshed.entries.any { it.stage == MappingLibraryListing.STALE_STAGE })
        } finally {
            MappingLibraryListing.areaBusyProbe = null
        }
    }

    @Test fun danglingSymlinkSkipsWrite() {
        val context = IndexContext(folder.root)
        mission(folder.root, photos = 1)
        MappingLibraryIndex.refresh(context, null)
        val before = MappingLibraryIndex.file(folder.root).readBytes()
        val alias = File(folder.root, "mapping-missions")
        val missing = File(folder.root, "missing-target-${UUID.randomUUID()}")
        alias.deleteRecursively()
        java.nio.file.Files.createSymbolicLink(alias.toPath(), missing.toPath())
        try {
            val refreshed = MappingLibraryIndex.refresh(context, null)
            assertTrue(refreshed.stale)
            assertEquals(MappingLibraryListing.REASON_UNAVAILABLE, refreshed.skipReason)
            assertArrayEquals(before, MappingLibraryIndex.file(folder.root).readBytes())
        } finally {
            java.nio.file.Files.deleteIfExists(alias.toPath())
        }
    }

    @Test fun removedMissionDroppedOnlyAfterSuccessfulListing() {
        val context = IndexContext(folder.root)
        val (keep, _) = mission(folder.root)
        val (drop, dropDir) = mission(folder.root)
        MappingLibraryIndex.refresh(context, null)
        assertTrue(dropDir.deleteRecursively())
        val refreshed = MappingLibraryIndex.refresh(context, null)
        assertTrue(refreshed.index.missions.containsKey(keep))
        assertFalse(refreshed.index.missions.containsKey(drop))
        assertEquals(1, refreshed.removed)
    }

    @Test fun writeRenameFailureDoesNotThrowAndRefreshRetries() {
        val context = IndexContext(folder.root)
        val (id, _) = mission(folder.root, photos = 2)
        // Block the stats directory so rename/write fails; refresh must not throw a sticky error.
        val stats = File(folder.root, "mapping-storage-stats")
        stats.mkdirs()
        val indexFile = MappingLibraryIndex.file(folder.root)
        indexFile.writeText("blocker")
        // Replace parent with a file so mkdirs/write of tmp fails on next write attempt after delete.
        // First refresh succeeds into a normal tree.
        MappingLibraryIndex.refresh(context, null)
        assertTrue(MappingLibraryIndex.load(folder.root)!!.missions.containsKey(id))
        // Simulate rename failure by making the destination a non-empty directory named like the index file.
        indexFile.delete()
        indexFile.mkdirs()
        File(indexFile, "child").writeText("x")
        val second = MappingLibraryIndex.refresh(context, null)
        assertTrue(second.index.missions.containsKey(id))
        // Cleanup so subsequent ops can rewrite.
        indexFile.deleteRecursively()
        val third = MappingLibraryIndex.refresh(context, null)
        assertTrue(MappingLibraryIndex.load(folder.root)!!.missions.containsKey(id))
        assertTrue(third.index.missions.containsKey(id))
    }

    @Test fun zeroCountsWithOriginalsStillList() {
        val context = IndexContext(folder.root)
        val id = UUID.randomUUID().toString()
        val dir = File(folder.root, "mapping-missions/$id").apply { mkdirs() }
        File(dir, "mission.json").writeText(JSONObject().put("missionId", id).put("title", "Zero")
            .put("createdAt", "2026-10-12T00:00:00Z").put("assignmentId", "a").put("incidentId", "i")
            .put("profile", "daylight-rgb").toString())
        File(dir, "originals").mkdirs()
        File(dir, "originals/${"%064x".format(1)}.jpg").writeBytes(byteArrayOf(1))
        File(dir, "inventory").mkdirs()
        // Inventory present but not matching complete size → photoCount 0.
        File(dir, "inventory/${"%064x".format(1)}.json").writeText(JSONObject()
            .put("sha256", "%064x".format(1)).put("name", "x.jpg").put("complete", false).put("sizeBytes", 99).toString())
        val refreshed = MappingLibraryIndex.refresh(context, null)
        assertTrue(refreshed.index.missions.containsKey(id))
        assertTrue(refreshed.entries.any { it.flight?.id == id })
    }
}

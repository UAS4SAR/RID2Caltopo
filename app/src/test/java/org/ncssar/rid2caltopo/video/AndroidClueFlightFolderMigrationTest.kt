package org.ncssar.rid2caltopo.video

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ncssar.rid2caltopo.app.todayArchiveDirectoryName
import org.ncssar.rid2caltopo.data.RidFlightId
import java.io.File
import java.util.Date

class AndroidClueFlightFolderMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val at = 1_783_296_000_000L
    private val day = todayArchiveDirectoryName(Date(at))

    private fun writeIndex(dir: File, vararg rows: JSONObject) {
        dir.mkdirs()
        val array = JSONArray()
        rows.forEach { array.put(it) }
        File(dir, "clues.json").writeText(array.toString(2))
    }

    private fun row(
        id: String,
        createdAtMs: Long,
        uploadState: String,
        designator: String = "1SAR7",
        flightId: String? = null,
        prefix: String = "",
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
            .put("imageFilename", "$prefix$id.jpg")
            .put("thumbnailFilename", "$prefix$id-thumb.jpg")
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

    private fun photos(dir: File, id: String, image: ByteArray = byteArrayOf(1, 2), thumb: ByteArray = byteArrayOf(3)) {
        dir.mkdirs()
        File(dir, "$id.jpg").writeBytes(image)
        File(dir, "$id-thumb.jpg").writeBytes(thumb)
    }

    private fun index(root: File, folder: String): Map<String, JSONObject> {
        val array = JSONArray(File(File(root, folder), "clues.json").readText())
        return (0 until array.length()).associate { array.getJSONObject(it).let { o -> o.getString("id") to o } }
    }

    @Test
    fun movesFlatCluesIntoFlightFoldersIdempotently() {
        val root = temporaryFolder.newFolder("clues")
        writeIndex(
            root,
            row("pending-a", at, "pending", flightId = "flight-1"),
            row("published-b", at, "published", flightId = "flight-2"),
        )
        photos(root, "pending-a")
        photos(root, "published-b", byteArrayOf(4, 5), byteArrayOf(6))

        assertEquals(2, AndroidClueFlightFolderMigration.migrateRoot(root))

        assertFalse(File(root, "clues.json").exists())
        assertFalse(File(root, "pending-a.jpg").exists())
        assertFalse(File(root, "published-b-thumb.jpg").exists())
        assertEquals(listOf(1, 2).map { it.toByte() }, File(root, "flight-1/pending-a.jpg").readBytes().toList())
        assertTrue(File(root, "flight-1/pending-a-thumb.jpg").isFile)
        assertTrue(File(root, "flight-2/published-b.jpg").isFile)
        assertEquals(setOf("pending-a"), index(root, "flight-1").keys)
        assertEquals(setOf("published-b"), index(root, "flight-2").keys)
        val pending = index(root, "flight-1").getValue("pending-a")
        assertEquals("pending", pending.getString("uploadState"))
        assertEquals("flight-1/pending-a.jpg", pending.getString("imageFilename"))
        assertEquals("flight-1/pending-a-thumb.jpg", pending.getString("thumbnailFilename"))
        assertEquals("published", index(root, "flight-2").getValue("published-b").getString("uploadState"))
        assertTrue(File(root, AndroidClueFlightFolderMigration.FLAG_FILE).isFile)

        // Idempotent: nothing left to do; published stays published.
        assertEquals(0, AndroidClueFlightFolderMigration.migrateRoot(root))
        assertEquals("published", index(root, "flight-2").getValue("published-b").getString("uploadState"))
    }

    @Test
    fun movesP75DayFoldersAndKeepsRootTombstones() {
        val root = temporaryFolder.newFolder("clues-days")
        val dayDir = File(root, day)
        writeIndex(
            dayDir,
            row("one", at, "pending", flightId = "flight-1", prefix = "$day/"),
            row("two", at + 1, "published", flightId = "flight-1", prefix = "$day/"),
            row("three", at + 2, "pending", flightId = "flight-3", prefix = "$day/"),
        )
        listOf("one", "two", "three").forEach { photos(dayDir, it) }
        File(root, "deleted-clue-ids.json").writeText("[\"gone\"]")

        assertEquals(3, AndroidClueFlightFolderMigration.migrateRoot(root))

        assertFalse("empty day dir removed", dayDir.exists())
        assertEquals(setOf("one", "two"), index(root, "flight-1").keys)
        assertEquals(setOf("three"), index(root, "flight-3").keys)
        assertTrue(File(root, "flight-1/one.jpg").isFile)
        assertTrue(File(root, "flight-3/three-thumb.jpg").isFile)
        assertEquals("[\"gone\"]", File(root, "deleted-clue-ids.json").readText())

        val store = AndroidClueStore.forDirectory(root)
        assertEquals(setOf("one", "two", "three"), store.allRecords().map { it.id }.toSet())
        assertTrue(store.imageFile(store.record("one")!!).isFile)
        assertEquals("flight-1/one.jpg", store.record("one")!!.imageFilename)
    }

    @Test
    fun missingFlightIdUsesUniqueArchiveHintElseStableSyntheticAndNeverDrops() {
        val root = temporaryFolder.newFolder("clues-recover")
        writeIndex(
            root,
            row("hinted", at, "pending", designator = "RID-1").also { it.put("sourceDesignator", "RID-1") },
            row("lonely-a", at + 5_000, "pending", designator = "Unknown Drone"),
            row("lonely-b", at + 9_000, "pending", designator = "Unknown Drone"),
        )
        listOf("hinted", "lonely-a", "lonely-b").forEach { photos(root, it) }
        val hints = AndroidClueFlightFolderMigration.FlightHints(
            mapOf("RID1|$day" to listOf("hint-flight", "hint-flight")),
        )

        assertEquals(3, AndroidClueFlightFolderMigration.migrateRoot(root, hints = { hints }))

        val synthetic = RidFlightId.make("UNKNOWNDRONE", org.ncssar.rid2caltopo.app.parseArchiveDirectoryDateMs(day)!!)
        assertEquals(setOf("hinted"), index(root, "hint-flight").keys)
        assertEquals(setOf("lonely-a", "lonely-b"), index(root, synthetic).keys)
        // Recovered flight is written back onto bound records only; the rows survive either way.
        assertTrue(File(root, "$synthetic/lonely-a.jpg").isFile)
        assertFalse(File(root, "lonely-a.jpg").exists())
    }

    @Test
    fun syntheticFlightIdIsStableAcrossRuns() {
        val first = temporaryFolder.newFolder("stable-1")
        val second = temporaryFolder.newFolder("stable-2")
        for (root in listOf(first, second)) {
            writeIndex(root, row("x", at, "pending", designator = "AC9"))
            photos(root, "x")
            AndroidClueFlightFolderMigration.migrateRoot(root)
        }
        assertEquals(
            first.listFiles()!!.filter { it.isDirectory }.map { it.name },
            second.listFiles()!!.filter { it.isDirectory }.map { it.name },
        )
        assertEquals(1, first.listFiles()!!.count { it.isDirectory })
    }

    @Test
    fun boundRecordsWithoutFlightGainRecoveredFlightOnBinding() {
        val root = temporaryFolder.newFolder("clues-binding")
        val json = row("bound", at, "pending", designator = "RID-1", flightId = "x")
        json.getJSONObject("binding").put("flightID", JSONObject.NULL)
        writeIndex(root, json)
        photos(root, "bound")
        AndroidClueFlightFolderMigration.migrateRoot(
            root,
            hints = { AndroidClueFlightFolderMigration.FlightHints(mapOf("RID1|$day" to listOf("flight-9"))) },
        )
        val migrated = index(root, "flight-9").getValue("bound")
        assertEquals("flight-9", migrated.getJSONObject("binding").getString("flightID"))
        assertEquals("flight-9", AndroidClueStore.forDirectory(root).record("bound")!!.binding!!.flightId)
    }

    @Test
    fun photoMissingRowsStayIndexedAndConfirmedNotRequeued() {
        val root = temporaryFolder.newFolder("clues-missing")
        writeIndex(
            root,
            row("ghost", at, "pending", flightId = "flight-1"),
            row("done", at, "published", flightId = "flight-1").put("caltopoMarkerId", "marker-1"),
        )
        photos(root, "done", byteArrayOf(9), byteArrayOf(8))

        AndroidClueFlightFolderMigration.migrateRoot(root)

        val store = AndroidClueStore.forDirectory(root)
        val ghost = store.record("ghost")!!
        assertEquals("flight-1/ghost.jpg", ghost.imageFilename)
        assertFalse(store.imageFile(ghost).isFile)
        assertEquals("pending", ghost.uploadState)
        val done = store.record("done")!!
        assertTrue(done.uploadConfirmed())
        assertTrue(store.pendingForMap("alpha", "team-a").none { it.id == "done" })
    }

    @Test
    fun orphanPhotosBecomePendingStubsNotDropped() {
        val root = temporaryFolder.newFolder("clues-orphans")
        val dayDir = File(root, day)
        photos(dayDir, "orphan")
        photos(root, "flat-orphan")
        AndroidClueFlightFolderMigration.migrateRoot(root)
        val store = AndroidClueStore.forDirectory(root)
        assertEquals(setOf("orphan", "flat-orphan"), store.allRecords().map { it.id }.toSet())
        store.allRecords().forEach {
            assertEquals("pending", it.uploadState)
            assertTrue(store.imageFile(it).isFile)
        }
        assertFalse(dayDir.exists())
    }

    @Test
    fun destinationRowsAreNeverDemotedByStaleLegacyCopies() {
        val root = temporaryFolder.newFolder("clues-merge")
        // Crash after the new index was written but before the day index was removed.
        writeIndex(File(root, "flight-1"), row("dup", at, "published", flightId = "flight-1", prefix = "flight-1/"))
        photos(File(root, "flight-1"), "dup")
        val dayDir = File(root, day)
        writeIndex(dayDir, row("dup", at, "pending", flightId = "flight-1", prefix = "$day/"))
        photos(dayDir, "dup")

        AndroidClueFlightFolderMigration.migrateRoot(root)

        assertEquals("published", index(root, "flight-1").getValue("dup").getString("uploadState"))
        assertFalse(dayDir.exists())
        assertTrue(File(root, "flight-1/dup.jpg").isFile)
    }

    @Test
    fun failureBeforeVerificationRetainsOriginalsAndRetries() {
        val root = temporaryFolder.newFolder("clues-crash")
        writeIndex(root, row("keep", at, "pending", flightId = "blocked"))
        photos(root, "keep")
        // A plain file where the flight folder must go makes the copy fail.
        File(root, "blocked").writeText("in the way")

        assertThrows(Exception::class.java) { AndroidClueFlightFolderMigration.migrateRoot(root) }
        assertTrue(File(root, "keep.jpg").isFile)
        assertTrue(File(root, "keep-thumb.jpg").isFile)
        assertTrue(File(root, "clues.json").isFile)
        assertFalse(File(root, AndroidClueFlightFolderMigration.FLAG_FILE).exists())

        assertTrue(File(root, "blocked").delete())
        assertEquals(1, AndroidClueFlightFolderMigration.migrateRoot(root))
        assertFalse(File(root, "keep.jpg").exists())
        assertTrue(File(root, "blocked/keep.jpg").isFile)
    }

    @Test
    fun interruptedRemovalIsFinishedByTheNextRun() {
        val root = temporaryFolder.newFolder("clues-partial")
        writeIndex(root, row("p", at, "pending", flightId = "flight-1"))
        photos(root, "p")
        // Removal that fails once (e.g. process death after copy/verify, before old copies went).
        var failures = 0
        assertEquals(
            1,
            AndroidClueFlightFolderMigration.migrateRoot(root, removeOld = { if (failures++ == 0) false else it.delete() }),
        )
        assertTrue(File(root, "flight-1/p.jpg").isFile)
        // Leftover legacy copy remains (removal failed) and is picked up and cleared again.
        assertTrue(File(root, "p.jpg").isFile || File(root, "clues.json").isFile)
        AndroidClueFlightFolderMigration.migrateRoot(root)
        assertFalse(File(root, "p.jpg").exists())
        assertFalse(File(root, "clues.json").exists())
        assertEquals(setOf("p"), index(root, "flight-1").keys)
    }

    @Test
    fun hydratedLeftoversAreRetainedThenRemovedThroughManagedCallbacks() {
        val root = temporaryFolder.newFolder("clues-managed")
        val dayDir = File(root, day)
        writeIndex(dayDir, row("m", at, "pending", flightId = "flight-1", prefix = "$day/"))
        photos(dayDir, "m")
        val retained = mutableListOf<String>()
        val removed = mutableListOf<String>()
        AndroidClueFlightFolderMigration.migrateRoot(
            root,
            retainNew = { retained += it.relativeTo(root).invariantSeparatorsPath },
            removeOld = { removed += it.relativeTo(root).invariantSeparatorsPath; !it.exists() || it.delete() },
        )
        assertTrue(retained.containsAll(listOf("flight-1/m.jpg", "flight-1/m-thumb.jpg", "flight-1/clues.json")))
        assertTrue(removed.containsAll(listOf("$day/m.jpg", "$day/m-thumb.jpg", "$day/clues.json", day)))
        assertTrue(retained.none { it.startsWith("$day/") })
    }

    @Test
    fun hasLegacyWorkIgnoresCompletedFlightLayoutAndDetectsLeftovers() {
        val root = temporaryFolder.newFolder("clues-detect")
        assertFalse(AndroidClueFlightFolderMigration.hasLegacyWork(root))
        File(root, "flight-1").mkdirs()
        File(root, "flight-1/clues.json").writeText("[]")
        File(root, "deleted-clue-ids.json").writeText("[]")
        assertFalse(AndroidClueFlightFolderMigration.hasLegacyWork(root))
        File(root, day).mkdirs()
        assertTrue(AndroidClueFlightFolderMigration.hasLegacyWork(root))
        File(root, day).delete()
        File(root, "stray.jpg").writeBytes(byteArrayOf(1))
        assertTrue(AndroidClueFlightFolderMigration.hasLegacyWork(root))
    }

    @Test
    fun flightDaysResolveFromIndexesForDayDeletion() {
        val root = temporaryFolder.newFolder("clues-days-map")
        writeIndex(File(root, "flight-1"), row("a", at, "pending", flightId = "flight-1", prefix = "flight-1/"))
        val later = at + 3 * 86_400_000L
        writeIndex(File(root, "flight-2"), row("b", later, "pending", flightId = "flight-2", prefix = "flight-2/"))
        File(root, "flight-3").mkdirs() // no index → unresolved
        val days = AndroidClueFlightFolders.flightDays(root)
        assertEquals(day, days["flight-1"])
        assertEquals(todayArchiveDirectoryName(Date(later)), days["flight-2"])
        assertNotEquals(days["flight-1"], days["flight-2"])
        assertFalse("flight-3" in days)
        assertEquals("flight-1", AndroidClueFlightFolders.flightFolderNameOf(root, File(root, "flight-1/a.jpg")))
        assertEquals(null, AndroidClueFlightFolders.flightFolderNameOf(root, File(root, "clues.json")))
        assertEquals(null, AndroidClueFlightFolders.flightFolderNameOf(root, File(root, "$day/a.jpg")))
    }

    @Test
    fun folderNamesAreFilesystemSafeAndNeverLegacyDayNames() {
        assertEquals("a1b2-c3", AndroidClueFlightFolders.folderNameFor(" a1b2-c3 "))
        assertEquals("a_b_c", AndroidClueFlightFolders.folderNameFor("a/b\\c"))
        assertFalse(AndroidClueFlightFolders.folderNameFor("../x").contains(".."))
        assertFalse(AndroidClueFlightFolders.isFlightFolderName("tracks-01Oct2026"))
        assertFalse(AndroidClueFlightFolders.isFlightFolderName(".hidden"))
        assertThrows(IllegalArgumentException::class.java) { AndroidClueFlightFolders.folderNameFor("  ") }
    }
}

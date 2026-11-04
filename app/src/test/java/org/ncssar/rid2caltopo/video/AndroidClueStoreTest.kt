package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AndroidClueStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun unreadableIndexBlocksWritesAndRecoversWithoutDiscardingPhotos() {
        val root = temporaryFolder.newFolder("corrupt-index")
        val record = clueRecord("retained", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending")
        val store = AndroidClueStore.forDirectory(root)
        val saved = store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        val index = File(root, saved.imageFilename.substringBefore('/') + "/clues.json")
        val original = index.readBytes()
        index.writeText("{damaged original")
        // Already-loaded process memory remains authoritative; a fresh open must refuse.
        assertEquals(saved, store.pendingForMap("alpha", "team-a").single())
        assertTrue(store.imageFile(saved).isFile)
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            AndroidClueStore.forDirectory(root).pendingForMap("alpha", "team-a")
        }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            AndroidClueStore.forDirectory(root).recordUploadResult(saved.id, true)
        }
        assertEquals("{damaged original", index.readText())
        assertTrue(File(root, saved.imageFilename).isFile)
        index.writeBytes(original)
        val recovered = AndroidClueStore.forDirectory(root)
        assertEquals(saved, recovered.pendingForMap("alpha", "team-a").single())
        recovered.recordUploadResult(saved.id, true)
    }

    @Test fun unrelatedIndexWriteRetainsMetadataForTemporarilyMissingImage() {
        val root = temporaryFolder.newFolder("missing-image")
        val store = AndroidClueStore.forDirectory(root)
        val missing = clueRecord("missing", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending")
        val other = clueRecord("other", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending")
        val savedMissing = store.saveEncoded(missing, byteArrayOf(1), byteArrayOf(2))
        val savedOther = store.saveEncoded(other, byteArrayOf(1), byteArrayOf(2))
        store.imageFile(savedMissing).delete()
        val reopened = AndroidClueStore.forDirectory(root)
        reopened.recordUploadResult(savedOther.id, true)
        assertEquals(savedMissing, AndroidClueStore.forDirectory(root).record(savedMissing.id))
        store.imageFile(savedMissing).writeBytes(byteArrayOf(1))
        assertEquals(savedMissing, AndroidClueStore.forDirectory(root).pendingForMap("alpha", "team-a").single())
    }

    @Test fun failedUploadResultWritePreservesPendingStateAndPhotoForRetry() {
        val root = temporaryFolder.newFolder("failed-upload-result")
        val record = clueRecord("retry", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending")
        val store = AndroidClueStore.forDirectory(root)
        val saved = store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        val day = saved.imageFilename.substringBefore('/')
        val indexFile = File(File(root, day), "clues.json")
        val index = indexFile.readBytes()
        val blocked = File(File(root, day), "clues.json.partial")
        assertTrue(blocked.mkdir())
        File(blocked, "fault").writeText("keep directory nonempty")
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { store.recordUploadResult(saved.id, true) }
        assertEquals(saved, store.record(saved.id))
        assertArrayEquals(index, indexFile.readBytes())
        assertTrue(store.imageFile(saved).isFile)
        assertEquals(saved, AndroidClueStore.forDirectory(root).pendingForMap("alpha", "team-a").single())
        blocked.deleteRecursively()
        store.recordUploadResult(saved.id, true)
        assertTrue(AndroidClueStore.forDirectory(root).pendingForMap("alpha", "team-a").isEmpty())
    }

    @Test
    fun mapAssignmentRefreshesPhotoEvenWhenUploadAlreadyFinished() {
        val root = temporaryFolder.newFolder("published-refresh")
        val store = AndroidClueStore.forDirectory(root)
        val record = clueRecord("photo", "unassigned").copy(destinationTeamId = "team-a")
        val saved = store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        assertTrue(store.recordsForMap("map:alpha").isEmpty())
        val published = saved.copy(mapKey = "map:alpha", uploadState = "published")
        val republished = store.saveEncoded(published, byteArrayOf(1), byteArrayOf(2))
        assertTrue(store.pendingForMap("alpha", "team-a").isEmpty())
        assertEquals(listOf(republished), store.recordsForMap("map:alpha"))
        assertTrue(store.imageFile(store.record(record.id)!!).isFile)
        assertEquals(listOf(republished), AndroidClueStore.forDirectory(root).recordsForMap("map:alpha"))
    }

    @Test fun localOnlyShareReportPreservesPrivacyAndUploadState() {
        val store = AndroidClueStore.forDirectory(temporaryFolder.newFolder("sharing"))
        val record = clueRecord("share", "map:alpha").copy(
            title = "Sensitive clue", description = "found by: 1SAR7\nDetails entered at capture",
            publishToCaltopo = false, uploadState = "local", destinationTeamId = "private-credential")
        val saved = store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        val before = store.record(saved.id)
        for (format in CoordinateDisplayFormat.values()) {
            val report = clueShareReport(saved, format)
            assertTrue(report.contains("Sensitive clue"))
            assertTrue(report.contains("found by: 1SAR7"))
            assertTrue(report.contains(CoordinateFormatter.format(saved.lat, saved.lng, format)))
            assertFalse(report.contains("private-credential"))
        }
        assertEquals(before, store.record(saved.id))
        assertTrue(store.pendingForMap("alpha", "private-credential").isEmpty())
    }

    @Test fun personalQueueSurvivesRelaunchButNeverSwitchesAccountOrMap() {
        val root = temporaryFolder.newFolder("personal")
        val record = clueRecord("personal", "map:ABC123").copy(destinationTeamId = "personal:USER01", uploadState = "pending")
        val saved = AndroidClueStore.forDirectory(root).saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        val reopened = AndroidClueStore.forDirectory(root)
        assertEquals(1, reopened.pendingForMap("ABC123", "personal:USER01").size)
        assertTrue(reopened.pendingForMap("ABC123", "personal:USER02").isEmpty())
        assertTrue(reopened.pendingForMap("ABC123", "USER01").isEmpty())
        assertTrue(reopened.pendingForMap("DEF456", "personal:USER01").isEmpty())
        reopened.recordUploadResult(saved.id, false, "lost response")
        assertEquals(saved.id, reopened.pendingForMap("ABC123", "personal:USER01").single().id)
        reopened.recordUploadResult(saved.id, true)
        assertTrue(reopened.pendingForMap("ABC123", "personal:USER01").isEmpty())
    }

    @Test
    fun localOnlyMarkerNeverEntersUploadQueueAcrossRelaunchAndRetry() {
        val root = temporaryFolder.newFolder("local-only")
        val local = clueRecord("local", "map:alpha").copy(
            publishToCaltopo = false,
            destinationTeamId = "team-a",
            uploadState = "local",
        )
        val saved = AndroidClueStore.forDirectory(root).saveEncoded(local, byteArrayOf(1), byteArrayOf(2))
        val reopened = AndroidClueStore.forDirectory(root)
        assertEquals(listOf(saved), reopened.recordsForMap("map:alpha"))
        assertTrue(reopened.pendingForMap("alpha", "team-a").isEmpty())
        reopened.recordUploadResult(local.id, false, "offline")
        val reloaded = AndroidClueStore.forDirectory(root)
        assertFalse(reloaded.record(local.id)!!.publishToCaltopo)
        assertTrue(reloaded.pendingForMap("alpha", "team-a").isEmpty())
    }

    @Test
    fun savedClueReloadsForItsMapAfterStoreRecreation() {
        val root = temporaryFolder.newFolder("clues")
        val record = clueRecord(id = "clue-one", mapKey = "map:alpha")
        val image = byteArrayOf(1, 2, 3, 4)
        val thumbnail = byteArrayOf(5, 6)

        val saved = AndroidClueStore.forDirectory(root).saveEncoded(record, image, thumbnail)
        val reopened = AndroidClueStore.forDirectory(root)

        assertEquals(listOf(saved), reopened.recordsForMap("map:alpha"))
        assertEquals(emptyList<AndroidClueRecord>(), reopened.recordsForMap("map:other"))
        assertArrayEquals(image, reopened.imageFile(saved).readBytes())
        assertArrayEquals(thumbnail, reopened.thumbnailFile(saved).readBytes())
        assertTrue(saved.imageFilename.contains('/'))
        assertTrue(File(File(root, saved.imageFilename.substringBefore('/')), "clues.json").isFile)
        assertFalse(File(root, "clues.json").exists())
    }

    @Test
    fun deleteRemovesIndexImageAndThumbnailButDoesNotAffectOtherClues() {
        val root = temporaryFolder.newFolder("clues")
        val store = AndroidClueStore.forDirectory(root)
        val deleted = clueRecord(id = "delete-me", mapKey = "map:alpha")
        val retained = clueRecord(id = "keep-me", mapKey = "map:alpha")
        val savedDeleted = store.saveEncoded(deleted, byteArrayOf(1), byteArrayOf(2))
        val savedRetained = store.saveEncoded(retained, byteArrayOf(3), byteArrayOf(4))

        assertTrue(store.delete(savedDeleted.id))

        assertFalse(store.imageFile(savedDeleted).exists())
        assertFalse(store.thumbnailFile(savedDeleted).exists())
        assertEquals(listOf(savedRetained), AndroidClueStore.forDirectory(root).recordsForMap("map:alpha"))
    }

    @Test
    fun photoMissingRowsRemainVisibleAndIndexedAcrossWrites() {
        val root = temporaryFolder.newFolder("retention")
        val store = AndroidClueStore.forDirectory(root)
        val old = clueRecord("old", "map:alpha")
        val savedOld = store.saveEncoded(old, byteArrayOf(1), byteArrayOf(2))
        store.imageFile(savedOld).delete()
        store.thumbnailFile(savedOld).delete()
        // Photo presence no longer hides the index row.
        assertEquals(listOf(savedOld), store.recordsForMap("map:alpha"))
        assertEquals(listOf(savedOld), store.allRecords())
        val current = clueRecord("new", "map:alpha")
        val savedNew = store.saveEncoded(current, byteArrayOf(3), byteArrayOf(4))
        assertEquals(
            setOf("old", "new"),
            AndroidClueStore.forDirectory(root).recordsForMap("map:alpha").map { it.id }.toSet(),
        )
        assertTrue(store.imageFile(savedNew).isFile)
    }

    @Test
    fun pendingUploadIsBoundToOriginalMapAndTeamAcrossRelaunch() {
        val root = temporaryFolder.newFolder("pending")
        val store = AndroidClueStore.forDirectory(root)
        val record = clueRecord("pending", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending")
        val saved = store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        val reopened = AndroidClueStore.forDirectory(root)
        assertEquals(listOf(saved), reopened.pendingForMap("alpha", "team-a"))
        assertTrue(reopened.pendingForMap("other", "team-a").isEmpty())
        assertTrue(reopened.pendingForMap("alpha", "team-b").isEmpty())
        reopened.recordUploadResult(saved.id, false, "offline")
        assertEquals(1, reopened.pendingForMap("alpha", "team-a").size)
        reopened.recordUploadResult(saved.id, true)
        assertTrue(AndroidClueStore.forDirectory(root).pendingForMap("alpha", "team-a").isEmpty())
    }

    @Test
    fun acceptingFlightOnlyBindsItsUnassignedPublishableClues() {
        val store = AndroidClueStore.forDirectory(temporaryFolder.newFolder("binding"))
        val matching = clueRecord("matching", "unassigned").copy(uploadState = "pending")
        val local = matching.copy(id="local", imageFilename="local.jpg", thumbnailFilename="local-thumb.jpg", publishToCaltopo=false)
        val other = matching.copy(id="other", imageFilename="other.jpg", thumbnailFilename="other-thumb.jpg", sourceDesignator="OTHER")
        for (record in listOf(matching, local, other)) store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        val flight = org.json.JSONObject().put("id", "f").put("label", "1SAR7_0641").put("points", org.json.JSONArray()
            .put(org.json.JSONArray().put("-121.0").put("39.0").put("100").put("900"))
            .put(org.json.JSONArray().put("-121.0").put("39.0").put("100").put("1100")))
        store.bindAwaitingFlight(flight, emptyList(), "map-a", "team-a")
        assertEquals(listOf("matching"), store.pendingForMap("map-a", "team-a").map { it.id })
        assertEquals(2, store.recordsForMap("unassigned").size)
    }

    @Test
    fun legacyRecordsStayOutOfQueueAndFailedStayDurableForReconcileRetry() {
        val store = AndroidClueStore.forDirectory(temporaryFolder.newFolder("legacy"))
        val old = clueRecord("old", "map:alpha").copy(destinationTeamId="team-a")
        store.saveEncoded(old, byteArrayOf(1), byteArrayOf(2))
        assertTrue(store.pendingForMap("alpha", "team-a").isEmpty())
        val pending = clueRecord("retryable", "map:alpha").copy(
            destinationTeamId = "team-a",
            uploadState = "pending",
        )
        val savedPending = store.saveEncoded(pending, byteArrayOf(1), byteArrayOf(2))
        store.recordUploadResult(savedPending.id, false, "Forbidden", terminal = true)
        assertEquals(listOf(savedPending.id), store.pendingForMap("alpha", "team-a").map { it.id })
        assertEquals("failed", store.record(savedPending.id)!!.uploadState)
    }

    @Test
    fun bindingIsStoredAndUploadsImmediately() {
        val root = temporaryFolder.newFolder("bound")
        val store = AndroidClueStore.forDirectory(root)
        val point = org.ncssar.rid2caltopo.data.ClueBindingPoint(500L, 800L, 39.153, -121.132, 100.0, "rid", true)
        val binding = org.ncssar.rid2caltopo.data.ClueBinder.bind("RID-1", "flight-1", 1_000L, "stream-pts", 1_400L,
            listOf(point), null, false)
        val record = clueRecord("bound", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending", binding = binding)
        store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        val reopened = AndroidClueStore.forDirectory(root)
        assertEquals(binding, reopened.record("bound")!!.binding)
        assertEquals(1_000L, reopened.record("bound")!!.ownershipTimeMs)
        assertEquals(listOf("bound"), reopened.pendingForMap("alpha", "team-a").map { it.id })
        assertTrue(record.publishedDescription.startsWith("Found beside trail\n\nWaypoint binding:\n  Offset: -0.500 s"))
        assertEquals("flight-1/bound.jpg", reopened.record("bound")!!.imageFilename)
    }

    @Test
    fun newSavesRequireFlightAndLandInFlightFolder() {
        val root = temporaryFolder.newFolder("per-flight")
        val store = AndroidClueStore.forDirectory(root)
        val noFlight = clueRecord("orphan", "map:alpha").copy(binding = null)
        val refused = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.saveEncoded(noFlight, byteArrayOf(1), byteArrayOf(2))
        }
        assertEquals(AndroidClueStore.NO_FLIGHT_MESSAGE, refused.message)
        val blankFlight = clueRecord("blank", "map:alpha").copy(binding = testFlightBinding(1_000L, flightId = "  "))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.saveEncoded(blankFlight, byteArrayOf(1), byteArrayOf(2))
        }
        assertTrue(store.allRecords().isEmpty())
        assertTrue(root.listFiles().orEmpty().none { it.isDirectory })

        val a = store.saveEncoded(
            clueRecord("a", "map:alpha").copy(binding = testFlightBinding(1_000L, "flight-a")),
            byteArrayOf(1), byteArrayOf(2),
        )
        val b = store.saveEncoded(
            clueRecord("b", "map:alpha").copy(binding = testFlightBinding(1_000L, "flight-b")),
            byteArrayOf(3), byteArrayOf(4),
        )
        assertEquals("flight-a/a.jpg", a.imageFilename)
        assertEquals("flight-a/a-thumb.jpg", a.thumbnailFilename)
        assertEquals("flight-b/b.jpg", b.imageFilename)
        assertTrue(File(root, "flight-a/clues.json").isFile)
        assertTrue(File(root, "flight-b/clues.json").isFile)
        assertFalse(File(root, "clues.json").exists())
        assertEquals(setOf("a"), idsIn(File(root, "flight-a/clues.json")))
        assertEquals(setOf("b"), idsIn(File(root, "flight-b/clues.json")))
        assertEquals(setOf("a", "b"), AndroidClueStore.forDirectory(root).allRecords().map { it.id }.toSet())

        // Deleting the only clue of a flight prunes its (now empty) folder; tombstones stay at root.
        assertTrue(store.delete("a"))
        assertFalse(File(root, "flight-a").exists())
        assertTrue(File(root, AndroidClueStore.TOMBSTONE_FILENAME).isFile)
    }

    @Test
    fun withFlightRelativePathsRekeysAnyPriorPrefixToTheFlight() {
        val record = clueRecord("x", "map:alpha").copy(
            imageFilename = "tracks-01Oct2026/x.jpg",
            thumbnailFilename = "tracks-01Oct2026/x-thumb.jpg",
            binding = testFlightBinding(1_000L, "flight-z"),
        )
        val keyed = AndroidClueStore.withFlightRelativePaths(record)
        assertEquals("flight-z/x.jpg", keyed.imageFilename)
        assertEquals("flight-z/x-thumb.jpg", keyed.thumbnailFilename)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AndroidClueStore.withFlightRelativePaths(record.copy(binding = null))
        }
    }

    private fun idsIn(index: File): Set<String> {
        val array = org.json.JSONArray(index.readText())
        return (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
    }

    @Test
    fun allRecords_includesCluesAcrossMapsForDisplayWithoutSelectedMap() {
        val root = temporaryFolder.newFolder("all-records-display")
        val store = AndroidClueStore.forDirectory(root)
        store.saveEncoded(clueRecord("a", "map:alpha"), byteArrayOf(1), byteArrayOf(2))
        store.saveEncoded(clueRecord("b", "unassigned"), byteArrayOf(1), byteArrayOf(2))
        store.saveEncoded(clueRecord("c", "map:bravo"), byteArrayOf(1), byteArrayOf(2))
        val all = store.allRecords().map { it.id }.toSet()
        assertEquals(setOf("a", "b", "c"), all)
        assertEquals(listOf("b"), store.recordsForMap("unassigned").map { it.id })
    }

    @Test
    fun allRecords_includesPhotoMissingRows() {
        val root = temporaryFolder.newFolder("all-records-missing")
        val store = AndroidClueStore.forDirectory(root)
        val saved = store.saveEncoded(clueRecord("ghost", "map:alpha"), byteArrayOf(1), byteArrayOf(2))
        store.imageFile(saved).delete()
        assertEquals(listOf("ghost"), store.allRecords().map { it.id })
        assertEquals(listOf("ghost"), store.recordsForMap("map:alpha").map { it.id })
        assertFalse(store.imageFile(saved).isFile)
    }

    @Test
    fun destinationMapTitleRoundTripsThroughIndex() {
        val root = temporaryFolder.newFolder("map-title")
        val record = clueRecord("titled", "map:alpha").copy(destinationMapTitle = "Incident Alpha")
        val saved = AndroidClueStore.forDirectory(root).saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        assertEquals("Incident Alpha", saved.destinationMapTitle)
        assertEquals("Incident Alpha", AndroidClueStore.forDirectory(root).record("titled")!!.destinationMapTitle)
    }

    @Test
    fun discardTombstonePersistsAcrossReload() {
        val root = temporaryFolder.newFolder("discard")
        val store = AndroidClueStore.forDirectory(root)
        val saved = store.saveEncoded(
            clueRecord("gone", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending"),
            byteArrayOf(1),
            byteArrayOf(2),
        )
        assertTrue(store.delete(saved.id))
        assertTrue(store.isUserDeleted(saved.id))
        assertTrue(store.allRecords().none { it.id == saved.id })
        val reopened = AndroidClueStore.forDirectory(root)
        assertTrue(reopened.isUserDeleted(saved.id))
        assertTrue(reopened.allRecords().none { it.id == saved.id })
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            reopened.saveEncoded(saved, byteArrayOf(1), byteArrayOf(2))
        }
    }

    @Test
    fun markLocalOnlyRemovesFromPendingButKeepsRecord() {
        val root = temporaryFolder.newFolder("local-only")
        val store = AndroidClueStore.forDirectory(root)
        val saved = store.saveEncoded(
            clueRecord("keep", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending"),
            byteArrayOf(1),
            byteArrayOf(2),
        )
        store.markLocalOnly(saved.id)
        assertTrue(store.pendingForMap("alpha", "team-a").isEmpty())
        val kept = store.record(saved.id)!!
        assertFalse(kept.publishToCaltopo)
        assertEquals("local", kept.uploadState)
        assertTrue(store.imageFile(kept).isFile)
    }

    @Test
    fun loadsLegacyFlatAndDayIndexesAlongsideFlightFolders() {
        val root = temporaryFolder.newFolder("legacy-flat")
        val day = "tracks-01Oct2026"
        fun row(id: String, image: String) =
            """[{"id":"$id","mapKey":"map:alpha","lat":39.0,"lng":-121.0,"title":"$id",""" +
                """"description":"","createdAtMs":2000,"sourceDesignator":"1SAR7",""" +
                """"imageFilename":"$image","thumbnailFilename":"${image.removeSuffix(".jpg")}-thumb.jpg",""" +
                """"publishToCaltopo":true,"uploadState":"pending"}]"""
        File(root, day).mkdirs()
        File(File(root, day), "clues.json").writeText(row("day", "$day/day.jpg"))
        File(root, "flight-f").mkdirs()
        File(File(root, "flight-f"), "clues.json").writeText(row("flight", "flight-f/flight.jpg"))
        File(root, "clues.json").writeText(row("flat", "flat.jpg"))
        val ids = AndroidClueStore.forDirectory(root).allRecords().map { it.id }.toSet()
        assertEquals(setOf("day", "flat", "flight"), ids)
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
        binding = testFlightBinding(1_000L),
    )
}

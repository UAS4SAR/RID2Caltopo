package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidClueStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun personalQueueSurvivesRelaunchButNeverSwitchesAccountOrMap() {
        val root = temporaryFolder.newFolder("personal")
        val record = clueRecord("personal", "map:ABC123").copy(destinationTeamId = "personal:USER01", uploadState = "pending")
        AndroidClueStore.forDirectory(root).saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        val reopened = AndroidClueStore.forDirectory(root)
        assertEquals(1, reopened.pendingForMap("ABC123", "personal:USER01").size)
        assertTrue(reopened.pendingForMap("ABC123", "personal:USER02").isEmpty())
        assertTrue(reopened.pendingForMap("ABC123", "USER01").isEmpty())
        assertTrue(reopened.pendingForMap("DEF456", "personal:USER01").isEmpty())
        reopened.recordUploadResult(record.id, false, "lost response")
        assertEquals(record.id, reopened.pendingForMap("ABC123", "personal:USER01").single().id)
        reopened.recordUploadResult(record.id, true)
        assertTrue(reopened.pendingForMap("ABC123", "personal:USER01").isEmpty())
    }

    @Test
    fun savedClueReloadsForItsMapAfterStoreRecreation() {
        val root = temporaryFolder.newFolder("clues")
        val record = clueRecord(id = "clue-one", mapKey = "map:alpha")
        val image = byteArrayOf(1, 2, 3, 4)
        val thumbnail = byteArrayOf(5, 6)

        AndroidClueStore.forDirectory(root).saveEncoded(record, image, thumbnail)
        val reopened = AndroidClueStore.forDirectory(root)

        assertEquals(listOf(record), reopened.recordsForMap("map:alpha"))
        assertEquals(emptyList<AndroidClueRecord>(), reopened.recordsForMap("map:other"))
        assertArrayEquals(image, reopened.imageFile(record).readBytes())
        assertArrayEquals(thumbnail, reopened.thumbnailFile(record).readBytes())
    }

    @Test
    fun deleteRemovesIndexImageAndThumbnailButDoesNotAffectOtherClues() {
        val root = temporaryFolder.newFolder("clues")
        val store = AndroidClueStore.forDirectory(root)
        val deleted = clueRecord(id = "delete-me", mapKey = "map:alpha")
        val retained = clueRecord(id = "keep-me", mapKey = "map:alpha")
        store.saveEncoded(deleted, byteArrayOf(1), byteArrayOf(2))
        store.saveEncoded(retained, byteArrayOf(3), byteArrayOf(4))

        assertTrue(store.delete(deleted.id))

        assertFalse(store.imageFile(deleted).exists())
        assertFalse(store.thumbnailFile(deleted).exists())
        assertEquals(listOf(retained), AndroidClueStore.forDirectory(root).recordsForMap("map:alpha"))
    }

    @Test
    fun retentionRemovalHidesClueAndNextWritePrunesItsMetadata() {
        val root = temporaryFolder.newFolder("retention")
        val store = AndroidClueStore.forDirectory(root)
        val old = clueRecord("old", "map:alpha")
        store.saveEncoded(old, byteArrayOf(1), byteArrayOf(2))
        store.imageFile(old).delete()
        store.thumbnailFile(old).delete()
        assertTrue(store.recordsForMap("map:alpha").isEmpty())
        val current = clueRecord("new", "map:alpha")
        store.saveEncoded(current, byteArrayOf(3), byteArrayOf(4))
        assertEquals(listOf(current), AndroidClueStore.forDirectory(root).recordsForMap("map:alpha"))
    }

    @Test
    fun pendingUploadIsBoundToOriginalMapAndTeamAcrossRelaunch() {
        val root = temporaryFolder.newFolder("pending")
        val store = AndroidClueStore.forDirectory(root)
        val record = clueRecord("pending", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending")
        store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        val reopened = AndroidClueStore.forDirectory(root)
        assertEquals(listOf(record), reopened.pendingForMap("alpha", "team-a"))
        assertTrue(reopened.pendingForMap("other", "team-a").isEmpty())
        assertTrue(reopened.pendingForMap("alpha", "team-b").isEmpty())
        reopened.recordUploadResult(record.id, false, "offline")
        assertEquals(1, reopened.pendingForMap("alpha", "team-a").size)
        reopened.recordUploadResult(record.id, true)
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
    fun legacyRecordsAndPermanentFailuresAreNeverBlindlyReplayed() {
        val store = AndroidClueStore.forDirectory(temporaryFolder.newFolder("legacy"))
        val old = clueRecord("old", "map:alpha").copy(destinationTeamId="team-a")
        store.saveEncoded(old, byteArrayOf(1), byteArrayOf(2))
        assertTrue(store.pendingForMap("alpha", "team-a").isEmpty())
        store.recordUploadResult(old.id, false, "Forbidden", terminal=true)
        assertTrue(store.pendingForMap("alpha", "team-a").isEmpty())
    }

    @Test
    fun bindingIsStoredOptionallyAndHoldsUploadsUntilFinal() {
        val root = temporaryFolder.newFolder("bound")
        val store = AndroidClueStore.forDirectory(root)
        val point = org.ncssar.rid2caltopo.data.ClueBindingPoint(500L, 800L, 39.153, -121.132, 100.0, "rid", true)
        val open = org.ncssar.rid2caltopo.data.ClueBinder.bind("RID-1", "flight-1", 1_000L, "stream-pts", 1_400L,
            listOf(point), null, false, false, 1_400L)
        // The only fix is before the capture, so a nearer one could still arrive.
        assertFalse(open.final)
        val record = clueRecord("bound", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending", binding = open)
        store.saveEncoded(record, byteArrayOf(1), byteArrayOf(2))
        val reopened = AndroidClueStore.forDirectory(root)
        assertEquals(open, reopened.record("bound")!!.binding)
        assertEquals(1_000L, reopened.record("bound")!!.ownershipTimeMs)
        // Held: a nearer waypoint could still arrive.
        assertTrue(reopened.pendingForMap("alpha", "team-a").isEmpty())
        assertEquals(listOf("bound"), reopened.openBindings().map { it.id })
        val final = reopened.record("bound")!!.copy(binding = open.copy(final = true))
        assertEquals(listOf(final), reopened.update(listOf(final)))
        assertTrue(reopened.update(listOf(final)).isEmpty())
        assertEquals(listOf("bound"), AndroidClueStore.forDirectory(root).pendingForMap("alpha", "team-a").map { it.id })
        assertTrue(final.publishedDescription.startsWith("Found beside trail\n\nWaypoint binding:\n  Offset: -0.500 s"))
        // Records saved before binding existed still load, are final, and publish their plain description.
        val legacy = clueRecord("legacy", "map:alpha").copy(destinationTeamId = "team-a", uploadState = "pending")
        store.saveEncoded(legacy, byteArrayOf(1), byteArrayOf(2))
        val index = java.io.File(root, "clues.json").readText()
        assertFalse(org.json.JSONArray(index).getJSONObject(1).has("binding"))
        val loaded = AndroidClueStore.forDirectory(root).record("legacy")!!
        assertEquals(null, loaded.binding)
        assertTrue(loaded.bindingFinal)
        assertEquals("Found beside trail", loaded.publishedDescription)
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
    )
}

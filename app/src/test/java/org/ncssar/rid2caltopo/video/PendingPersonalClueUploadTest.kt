package org.ncssar.rid2caltopo.video

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ncssar.rid2caltopo.data.CaltopoPersonalSession
import org.ncssar.rid2caltopo.data.CaltopoSession
import org.ncssar.rid2caltopo.data.CluePublicationIdentity

class PendingPersonalClueUploadTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun reset() {
        CaltopoSession.setStoredPhotoProbeForTesting(null)
        CaltopoPersonalSession.resetRuntimeState()
    }

    @Test fun pendingPersonalClueOnTheConnectedAccountPublishes() {
        CaltopoPersonalSession.acceptCatalog(
            CaltopoPersonalSession.PreparedCatalog("USER01", "user", mapOf("ABC123" to "USER01"), emptyList())
        )
        CaltopoPersonalSession.activate("session-token", "ABC123")
        val teamId = CluePublicationIdentity.publicationTeamId()
        assertEquals("personal:USER01", teamId)

        val root = folder.newFolder("clues")
        val store = AndroidClueStore.forDirectory(root)
        val pending = clue("pending").copy(
            mapKey = "map:ABC123",
            destinationTeamId = "personal: USER01",
            uploadState = "pending",
        )
        val otherAccount = clue("other").copy(
            mapKey = "map:ABC123",
            destinationTeamId = "personal:USER02",
            uploadState = "pending",
        )
        store.saveEncoded(pending, byteArrayOf(1), byteArrayOf(2))
        store.saveEncoded(otherAccount, byteArrayOf(1), byteArrayOf(2))

        val published = mutableListOf<String>()
        CaltopoSession.setStoredPhotoProbeForTesting { mapId, team, markerId ->
            assertEquals("ABC123", mapId)
            assertEquals(teamId, team)
            published += markerId
        }

        assertEquals(listOf(pending.id), store.pendingForMap("ABC123", teamId).map { it.id })
        assertEquals(listOf(otherAccount.id), store.pendingForMap("ABC123", "personal:USER02").map { it.id })

        val record = publishFirstPendingClue(store, "ABC123", teamId, "folder-1", { byteArrayOf(9) }, null)

        assertEquals(pending.id, record?.id)
        assertEquals(listOf(pending.id), published)
    }

    private fun clue(id: String) = AndroidClueRecord(
        id = id,
        mapKey = "unassigned",
        lat = 39.15,
        lng = -121.13,
        alt = 100.0,
        title = "Clue $id",
        description = "Found",
        createdAtMs = 1_000L,
        sourceDesignator = "1SAR7",
        imageFilename = "$id.jpg",
        thumbnailFilename = "$id-thumb.jpg",
        publishToCaltopo = true,
    )
}

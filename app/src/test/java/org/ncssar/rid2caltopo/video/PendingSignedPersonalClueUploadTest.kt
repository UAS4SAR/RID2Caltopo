package org.ncssar.rid2caltopo.video

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ncssar.rid2caltopo.data.CaltopoPersonalAccountConnection
import org.ncssar.rid2caltopo.data.CaltopoPersonalSession
import org.ncssar.rid2caltopo.data.CaltopoSession
import org.ncssar.rid2caltopo.data.CluePublicationAuth
import org.ncssar.rid2caltopo.data.CluePublicationIdentity

class PendingSignedPersonalClueUploadTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun reset() {
        CaltopoSession.setStoredPhotoProbeForTesting(null)
        CaltopoPersonalSession.resetRuntimeState()
        CaltopoPersonalAccountConnection.resetForTests()
    }

    @Test fun personalIdentityCluePublishesWithSignedCredential() {
        CaltopoPersonalAccountConnection.install(
            org.ncssar.rid2caltopo.data.CaltopoPersonalAccountMemoryStore(),
            featureEnabled = true,
        )
        CaltopoPersonalSession.acceptCatalog(
            CaltopoPersonalSession.PreparedCatalog("BCL66J", "user", mapOf("4J0LF02" to "BCL66J"), emptyList())
        )
        CaltopoPersonalSession.activate(CaltopoPersonalAccountConnection.SIGNED_ACCOUNT_GRANT, "4J0LF02")
        val personal = CaltopoPersonalSession.capture("")
        assertTrue(CluePublicationAuth.usesSignedPersonalCredential(personal))
        assertFalse(CluePublicationAuth.requiresCookieMediaAuthorization(personal))

        val teamId = CluePublicationIdentity.publicationTeamId()
        assertEquals("personal:BCL66J", teamId)

        val root = folder.newFolder("clues")
        val store = AndroidClueStore.forDirectory(root)
        val pending = AndroidClueRecord(
            id = "3906752a-b48b-4ffd-89a3-5acc318b8dd1",
            mapKey = "map:4J0LF02",
            lat = 39.15,
            lng = -121.13,
            alt = 100.0,
            title = "Clue",
            description = "Found",
            createdAtMs = 1_000L,
            sourceDesignator = "1SAR7",
            imageFilename = "clue.jpg",
            thumbnailFilename = "clue-thumb.jpg",
            publishToCaltopo = true,
            destinationTeamId = "personal:BCL66J",
            uploadState = "pending",
        )
        store.saveEncoded(pending, byteArrayOf(1), byteArrayOf(2))

        val published = mutableListOf<String>()
        CaltopoSession.setStoredPhotoProbeForTesting { mapId, team, markerId ->
            assertEquals("4J0LF02", mapId)
            assertEquals(teamId, team)
            published += markerId
        }

        val record = publishFirstPendingClue(store, "4J0LF02", teamId, "folder-1", { byteArrayOf(9) }, null)
        assertEquals(pending.id, record?.id)
        assertEquals(listOf(pending.id), published)
    }
}

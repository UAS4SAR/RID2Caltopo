package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R2CViewModelCredentialRecoveryTest {
    @Test
    fun selectingPersonalFromMapConnectionOpensBrowserWithCachedCatalog() {
        org.ncssar.rid2caltopo.data.CaltopoClient.ResetPersistedClientState()
        val session = org.ncssar.rid2caltopo.data.CaltopoPersonalSession
        try {
            session.catalogReady = true
            session.username = "test-user"
            session.maps = listOf(org.ncssar.rid2caltopo.data.CaltopoNode.MapNode("ABC123", "Test map", 0L))
            val model = R2CViewModel(org.ncssar.rid2caltopo.data.SimpleTimer())
            model.onUIEvent(UIEvent.BrowseProfileSelected("personal"))
            org.junit.Assert.assertEquals(OverlayState.MapBrowser, model.overlay)
            org.junit.Assert.assertEquals(session.maps, model.mapHierarchy)
            assertTrue(session.browsingPersonal)
        } finally { org.ncssar.rid2caltopo.data.CaltopoClient.ResetPersistedClientState() }
    }

    @Test
    fun restoredCredentialsResumeAWaitingMapConnection() {
        assertTrue(
            shouldResumeMapConnectionAfterCredentialRestore(
                OverlayState.RequestConfigFile,
                hasCredentials = true
            )
        )
    }

    @Test
    fun missingCredentialsRemainInTheExplicitWaitingState() {
        assertFalse(
            shouldResumeMapConnectionAfterCredentialRestore(
                OverlayState.RequestConfigFile,
                hasCredentials = false
            )
        )
    }

    @Test
    fun restoredCredentialsDoNotInterruptAnotherOverlay() {
        assertFalse(
            shouldResumeMapConnectionAfterCredentialRestore(
                OverlayState.MapBrowser,
                hasCredentials = true
            )
        )
    }
}

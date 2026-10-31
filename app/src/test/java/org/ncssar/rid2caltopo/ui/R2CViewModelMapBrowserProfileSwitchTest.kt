package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoPersonalSession
import org.ncssar.rid2caltopo.data.MapBrowserProfilePreference
import org.ncssar.rid2caltopo.data.SimpleTimer

class R2CViewModelMapBrowserProfileSwitchTest {
    @Test
    fun credentialsVerifiedDoesNotRequireMapResetForProfileSwitch() {
        assertFalse(
            shouldResetMapConnectionForBrowseProfileSwitch(
                CaltopoMap.MapStatusListener.mapStatus.credentialsVerified
            )
        )
        assertFalse(
            shouldResetMapConnectionForBrowseProfileSwitch(
                CaltopoMap.MapStatusListener.mapStatus.down
            )
        )
        assertTrue(
            shouldResetMapConnectionForBrowseProfileSwitch(
                CaltopoMap.MapStatusListener.mapStatus.up
            )
        )
        assertTrue(
            shouldResetMapConnectionForBrowseProfileSwitch(
                CaltopoMap.MapStatusListener.mapStatus.connecting
            )
        )
    }

    @Test
    fun profileSwitchDisconnectKeepsMapBrowserOpen() {
        assertTrue(
            shouldKeepMapBrowserOpenAfterConnectionDown(
                OverlayState.MapBrowser,
                keepOpenForProfileSwitch = true
            )
        )
        assertFalse(
            shouldKeepMapBrowserOpenAfterConnectionDown(
                OverlayState.MapBrowser,
                keepOpenForProfileSwitch = false
            )
        )
        assertFalse(
            shouldKeepMapBrowserOpenAfterConnectionDown(
                OverlayState.None,
                keepOpenForProfileSwitch = true
            )
        )
    }

    @Test
    fun reselectingActivePersonalProfileIsSessionNoOpAndKeepsBrowser() {
        org.ncssar.rid2caltopo.data.CaltopoClient.ResetPersistedClientState()
        val session = CaltopoPersonalSession
        try {
            session.catalogReady = true
            session.username = "test-user"
            session.maps = listOf(org.ncssar.rid2caltopo.data.CaltopoNode.MapNode("ABC123", "Test map", 0L))
            session.browsingPersonal = true
            val model = R2CViewModel(SimpleTimer())
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.credentialsVerified))
            assertEquals(OverlayState.MapBrowser, model.overlay)
            model.onUIEvent(UIEvent.BrowseProfileSelected(MapBrowserProfilePreference.PERSONAL_PROFILE_ID))
            assertTrue(session.browsingPersonal)
            assertEquals(OverlayState.MapBrowser, model.overlay)
            assertEquals(session.maps, model.mapHierarchy)
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.down))
            assertEquals(OverlayState.None, model.overlay)
        } finally {
            org.ncssar.rid2caltopo.data.CaltopoClient.ResetPersistedClientState()
        }
    }

    @Test
    fun applyingRememberedPersonalWhileVerifiedDoesNotResetMapStatus() {
        org.ncssar.rid2caltopo.data.CaltopoClient.ResetPersistedClientState()
        val session = CaltopoPersonalSession
        val mapStatusField = CaltopoMap::class.java.getDeclaredField("MapStatus").apply { isAccessible = true }
        val previousStatus = mapStatusField.get(null)
        try {
            mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.credentialsVerified)
            session.catalogReady = true
            session.username = "test-user"
            session.maps = listOf(org.ncssar.rid2caltopo.data.CaltopoNode.MapNode("ABC123", "Test map", 0L))
            session.browsingPersonal = false
            val model = R2CViewModel(SimpleTimer())
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.credentialsVerified))
            assertEquals(OverlayState.MapBrowser, model.overlay)
            model.onUIEvent(UIEvent.BrowseProfileSelected(MapBrowserProfilePreference.PERSONAL_PROFILE_ID))
            assertTrue(session.browsingPersonal)
            assertEquals(OverlayState.MapBrowser, model.overlay)
            assertEquals(session.maps, model.mapHierarchy)
            // Re-applying remembered personal must not OpenMap(null) from credentialsVerified.
            assertEquals(
                CaltopoMap.MapStatusListener.mapStatus.credentialsVerified,
                mapStatusField.get(null)
            )
            // A later standalone down without the profile-switch guard still clears the overlay.
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.down))
            assertEquals(OverlayState.None, model.overlay)
        } finally {
            mapStatusField.set(null, previousStatus)
            org.ncssar.rid2caltopo.data.CaltopoClient.ResetPersistedClientState()
        }
    }

    @Test
    fun profileSwitchDisconnectWhileBrowserOpenDoesNotDismissOverlay() {
        org.ncssar.rid2caltopo.data.CaltopoClient.ResetPersistedClientState()
        try {
            val model = R2CViewModel(SimpleTimer())
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.credentialsVerified))
            assertEquals(OverlayState.MapBrowser, model.overlay)
            val flag = R2CViewModel::class.java
                .getDeclaredField("keepMapBrowserOpenAcrossProfileDisconnect")
                .apply { isAccessible = true }
            flag.setBoolean(model, true)
            // Simulate completeMapDisconnect after an internal profile-switch OpenMap(null).
            model.onUIEvent(UIEvent.ConnectionStatusChanged(CaltopoMap.MapStatusListener.mapStatus.down))
            assertEquals(OverlayState.MapBrowser, model.overlay)
            assertEquals(CaltopoConnectionState.StandAlone, model.connectionState)
            assertFalse(flag.getBoolean(model))
        } finally {
            org.ncssar.rid2caltopo.data.CaltopoClient.ResetPersistedClientState()
        }
    }
}

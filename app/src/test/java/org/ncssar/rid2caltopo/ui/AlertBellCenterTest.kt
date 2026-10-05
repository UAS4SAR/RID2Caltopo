package org.ncssar.rid2caltopo.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertBellCenterTest {
    @After
    fun tearDown() {
        AlertBellCenter.resetForTests()
        ProximityAlertCenter.resetForTests()
    }

    @Test
    fun altitudeEightyPercentSemantics() {
        assertEquals(AlertBellColor.White, AlertBellThresholdPolicy.altitudeColor(159.0))
        assertEquals(AlertBellColor.Orange, AlertBellThresholdPolicy.altitudeColor(160.0))
        assertEquals(AlertBellColor.Orange, AlertBellThresholdPolicy.altitudeColor(199.0))
        assertEquals(AlertBellColor.Red, AlertBellThresholdPolicy.altitudeColor(200.0))
        assertEquals(AlertBellColor.White, AlertBellThresholdPolicy.altitudeColor(null))
    }

    @Test
    fun distanceEightyPercentSemantics() {
        assertEquals(AlertBellColor.White, AlertBellThresholdPolicy.distanceColor(4223.0))
        assertEquals(AlertBellColor.Orange, AlertBellThresholdPolicy.distanceColor(4224.0))
        assertEquals(AlertBellColor.Orange, AlertBellThresholdPolicy.distanceColor(5279.0))
        assertEquals(AlertBellColor.Red, AlertBellThresholdPolicy.distanceColor(5280.0))
    }

    @Test
    fun proximityOnePointTwoFiveApproach() {
        assertEquals(
            AlertBellColor.White,
            AlertBellThresholdPolicy.proximityColor(126.0, 100.0, false),
        )
        assertEquals(
            AlertBellColor.Orange,
            AlertBellThresholdPolicy.proximityColor(125.0, 100.0, false),
        )
        assertEquals(
            AlertBellColor.Red,
            AlertBellThresholdPolicy.proximityColor(100.0, 100.0, false),
        )
        assertEquals(
            AlertBellColor.Red,
            AlertBellThresholdPolicy.proximityColor(200.0, 100.0, true),
        )
    }

    @Test
    fun wifiAndBridgeApproach() {
        assertEquals(AlertBellColor.White, AlertBellThresholdPolicy.wifiColor(80))
        assertEquals(AlertBellColor.Orange, AlertBellThresholdPolicy.wifiColor(74))
        assertEquals(AlertBellColor.Orange, AlertBellThresholdPolicy.wifiColor(60))
        assertEquals(AlertBellColor.Red, AlertBellThresholdPolicy.wifiColor(59))

        assertEquals(
            AlertBellColor.White,
            AlertBellThresholdPolicy.bridgeColor(20.0, true),
        )
        assertEquals(
            AlertBellColor.Orange,
            AlertBellThresholdPolicy.bridgeColor(25.6, true),
        )
        assertEquals(
            AlertBellColor.Red,
            AlertBellThresholdPolicy.bridgeColor(32.0, true),
        )
        assertEquals(
            AlertBellColor.White,
            AlertBellThresholdPolicy.bridgeColor(100.0, false),
        )
    }

    @Test
    fun muteIsSessionScopedAndLatchesBellVisibility() {
        assertFalse(AlertBellCenter.uiState.value.showBell)
        AlertBellCenter.setMuted(AlertBellKind.Altitude, true)
        assertTrue(AlertBellCenter.isMuted(AlertBellKind.Altitude))
        AlertBellCenter.setMuted(AlertBellKind.Altitude, false)
        assertFalse(AlertBellCenter.isMuted(AlertBellKind.Altitude))

        AlertBellCenter.updateMetrics(
            AlertBellMetrics(maxAglFt = 170.0),
        )
        assertFalse(AlertBellCenter.uiState.value.showBell)
        assertEquals(AlertBellColor.Orange, AlertBellCenter.uiState.value.aggregateColor)

        // Ambient red metrics alone must not latch the bell (startup bridge/WiFi).
        AlertBellCenter.updateMetrics(
            AlertBellMetrics(maxAglFt = 210.0),
        )
        assertFalse(AlertBellCenter.uiState.value.showBell)
        assertEquals(AlertBellColor.Red, AlertBellCenter.uiState.value.aggregateColor)

        AlertBellCenter.noteAlarmFired()
        assertTrue(AlertBellCenter.uiState.value.showBell)

        AlertBellCenter.updateMetrics(AlertBellMetrics())
        assertTrue(
            "Bell stays visible for the session after the first alarm",
            AlertBellCenter.uiState.value.showBell,
        )
        assertEquals(AlertBellColor.White, AlertBellCenter.uiState.value.aggregateColor)
    }

    @Test
    fun spokenWarningKindMapsToBellKind() {
        assertEquals(AlertBellKind.Proximity, SpokenWarningKind.Proximity.toAlertBellKind())
        assertEquals(AlertBellKind.Altitude, SpokenWarningKind.Altitude.toAlertBellKind())
        assertEquals(
            AlertBellKind.DroneSignalLoss,
            SpokenWarningKind.DroneTelemetry.toAlertBellKind(),
        )
        assertEquals(
            AlertBellKind.BridgeSignalLoss,
            SpokenWarningKind.BridgeNotDetected.toAlertBellKind(),
        )
        assertEquals(
            AlertBellKind.WifiStrength,
            SpokenWarningKind.ControllerSignalStrength.toAlertBellKind(),
        )
        assertEquals(
            AlertBellKind.VideoRequest,
            SpokenWarningKind.VideoStreamRequest.toAlertBellKind(),
        )
    }

    @Test
    fun unmuteAltitudeInvokesClearHook() {
        var cleared = false
        AlertBellCenter.onAltitudeTypeUnmuted = { cleared = true }
        AlertBellCenter.setMuted(AlertBellKind.Altitude, true)
        AlertBellCenter.setMuted(AlertBellKind.Altitude, false)
        assertTrue(cleared)
    }
}

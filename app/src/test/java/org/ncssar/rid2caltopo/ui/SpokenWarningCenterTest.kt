package org.ncssar.rid2caltopo.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenWarningCenterTest {
    @After
    fun tearDown() {
        SpokenWarningCenter.resetForTests()
    }

    @Test
    fun disablingProximityCancelsOnlyItsPendingWarning() {
        SpokenWarningCenter.requestWarning(SpokenWarningKind.Proximity, "A|B")
        SpokenWarningCenter.cancelProximityWarning()
        assertNull(SpokenWarningCenter.requests.value)
        SpokenWarningCenter.requestWarning(SpokenWarningKind.Altitude, "A")
        SpokenWarningCenter.cancelProximityWarning()
        assertEquals(SpokenWarningKind.Altitude, SpokenWarningCenter.requests.value?.kind)
    }

    @Test
    fun requestWarning_emitsProblemLabel() {
        SpokenWarningCenter.requestWarning(
            kind = SpokenWarningKind.Altitude,
            sourceKey = "drone-a",
            nowMs = 1_000L,
            cooldownMs = 0L,
            volumeFraction = 0.75f
        )

        val request = SpokenWarningCenter.requests.value
        assertEquals(SpokenWarningKind.Altitude, request?.kind)
        assertEquals("Altitude", request?.phrase)
        assertEquals(0.75f, request?.volumeFraction)
    }

    @Test
    fun requestWarningSequence_emitsAllProblemLabelsInOrder() {
        SpokenWarningCenter.requestWarningSequence(
            kinds = listOf(
                SpokenWarningKind.DroneTelemetry,
                SpokenWarningKind.Altitude,
                SpokenWarningKind.Proximity,
                SpokenWarningKind.ControllerSignalStrength,
                SpokenWarningKind.BridgeNotDetected
            ),
            sourceKey = "audio-alarm-test",
            nowMs = 1_000L,
            cooldownMs = 0L
        )

        val request = SpokenWarningCenter.requests.value
        assertEquals("Drone Telemetry", request?.phrase)
        assertEquals(
            listOf(
                "Drone Telemetry",
                "Altitude",
                "Proximity",
                "Stream Wi-Fi Signal Strength",
                "Bridge Not Detected",
            ),
            request?.phrases
        )
    }

    @Test
    fun requestWarning_suppressesRepeatedProblemInsideCooldown() {
        SpokenWarningCenter.requestWarning(
            kind = SpokenWarningKind.ControllerSignalStrength,
            sourceKey = "stream-controller",
            nowMs = 1_000L,
            cooldownMs = 30_000L
        )
        val first = SpokenWarningCenter.requests.value

        SpokenWarningCenter.requestWarning(
            kind = SpokenWarningKind.ControllerSignalStrength,
            sourceKey = "stream-controller",
            nowMs = 10_000L,
            cooldownMs = 30_000L
        )

        assertTrue(first === SpokenWarningCenter.requests.value)
    }

    @Test
    fun requestWarning_allowsRepeatedProblemAfterCooldown() {
        SpokenWarningCenter.requestWarning(
            kind = SpokenWarningKind.DroneTelemetry,
            sourceKey = "flight-a",
            nowMs = 1_000L,
            cooldownMs = 30_000L
        )
        val first = SpokenWarningCenter.requests.value

        SpokenWarningCenter.requestWarning(
            kind = SpokenWarningKind.DroneTelemetry,
            sourceKey = "flight-a",
            nowMs = 31_000L,
            cooldownMs = 30_000L
        )

        val second = SpokenWarningCenter.requests.value
        assertEquals(SpokenWarningKind.DroneTelemetry, second?.kind)
        assertTrue(first !== second)
    }

    @Test
    fun resetForTests_clearsCurrentRequest() {
        SpokenWarningCenter.requestWarning(
            kind = SpokenWarningKind.Proximity,
            sourceKey = "pair-a",
            nowMs = 1_000L,
            cooldownMs = 0L
        )

        SpokenWarningCenter.resetForTests()

        assertNull(SpokenWarningCenter.requests.value)
    }

    @Test
    fun consume_deliversWarningOnlyOnceAcrossHostRecreation() {
        SpokenWarningCenter.requestSpokenPhrase(
            kind = SpokenWarningKind.VideoStreamRequest,
            sourceKey = "sharing-request-a",
            phrase = "Now sharing video stream with requester@example.org",
            nowMs = 1_000L,
        )
        val emitted = SpokenWarningCenter.requests.value

        val firstHost = SpokenWarningCenter.consume(emitted!!.requestId)
        val recreatedHost = SpokenWarningCenter.consume(emitted.requestId)

        assertEquals(emitted, firstHost)
        assertNull(recreatedHost)
        assertNull(SpokenWarningCenter.requests.value)
    }

    @Test
    fun consume_doesNotClearANewerWarning() {
        SpokenWarningCenter.requestWarning(
            kind = SpokenWarningKind.Altitude,
            sourceKey = "flight-a",
            nowMs = 1_000L,
        )
        val older = SpokenWarningCenter.requests.value!!
        SpokenWarningCenter.requestWarning(
            kind = SpokenWarningKind.Proximity,
            sourceKey = "pair-a",
            nowMs = 2_000L,
        )
        val newer = SpokenWarningCenter.requests.value!!

        assertNull(SpokenWarningCenter.consume(older.requestId))
        assertTrue(newer === SpokenWarningCenter.requests.value)
        assertEquals(newer, SpokenWarningCenter.consume(newer.requestId))
        assertNull(SpokenWarningCenter.requests.value)
    }
}

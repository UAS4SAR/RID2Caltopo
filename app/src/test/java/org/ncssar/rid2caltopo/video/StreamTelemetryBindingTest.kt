package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertNull
import org.junit.Test
import org.ncssar.rid2caltopo.video.ffmpeg.FfmpegTelemetry
import org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetryRegistry

class StreamTelemetryBindingTest {
    @Before
    fun setUpFlightActivityRegistry() {
        StreamFlightActivityRegistry.resetForTests()
    }

    @After
    fun tearDownFlightActivityRegistry() {
        StreamFlightActivityRegistry.resetForTests()
        StreamCameraTelemetryRegistry.clear("RED1")
    }

    @Test
    fun streamPriorityRequiresAcceptedFreshPositionAndEndsOnDisconnect() {
        StreamFlightActivityRegistry.replaceConfigured(mapOf("RED1" to "1581F8"))
        StreamFlightActivityRegistry.replaceLivePublishers(listOf("RED1"), 10000)
        StreamCameraTelemetryRegistry.update("RED1", FfmpegTelemetry(
            sourceTag="dji-sei-245", gimbalPitchDeg=-14.56, cameraYawDeg=90.0,
            horizontalFovDeg=40.0, verticalFovDeg=25.0,
            latitude=39.0, longitude=-121.0, altitudeMeters=600.0,
            djiNorthMm=1000, djiEastMm=2000, djiDownMm=-605000,
        ), 10000)
        assertEquals(false, StreamFlightActivityRegistry.hasFreshPosition("1581F8", 10001))
        StreamFlightActivityRegistry.noteAcceptedPosition("1581F8", 10000)
        assertEquals(true, StreamFlightActivityRegistry.hasFreshPosition("1581F8", 10001))
        assertEquals(false, StreamFlightActivityRegistry.hasFreshPosition("OTHER", 10001))
        assertEquals(false, StreamFlightActivityRegistry.hasFreshPosition("1581F8", 13001))
        StreamFlightActivityRegistry.replaceLivePublishers(emptyList(), 10002)
        assertEquals(false, StreamFlightActivityRegistry.hasFreshPosition("1581F8", 10002))
    }

    @Test
    fun manualPairingBindsStreamToRemoteIdWithoutChangingMappedId() {
        val telemetry = testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")
        val bindings = mutableMapOf<String, String>()

        bindStreamToRemoteId(bindings, "NCSSAR_MTRC4TD", telemetry.remoteId)

        assertEquals("1581F8", bindings["NCSSAR_MTRC4TD"])
        assertEquals("1SAR138DjMtrc4td", telemetry.mappedId)
    }

    @Test
    fun pairedStreamUsesCurrentMappedIdAsPrimaryLabel() {
        val bindings = mutableMapOf("NCSSAR_MTRC4TD" to "1581F8")

        val resolved = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            runtimeStreamBindings = bindings
        )

        assertEquals(StreamTelemetryBindingStatus.PAIRED, resolved.status)
        assertEquals("1SAR138DjMtrc4td", resolved.primaryLabel)
        assertEquals("1581F8", resolved.telemetry?.remoteId)
    }

    @Test
    fun redAndYellowStreamsUseControllerDesignatorAsPrimaryLabel() {
        val red = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = emptyList(),
            runtimeStreamBindings = mutableMapOf()
        )
        val yellow = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            runtimeStreamBindings = mutableMapOf()
        )

        assertEquals(StreamTelemetryBindingStatus.NO_TELEMETRY, red.status)
        assertEquals("NCSSAR_MTRC4TD", red.primaryLabel)
        assertNull(red.telemetry)
        assertEquals(StreamTelemetryBindingStatus.UNPAIRED_WITH_CANDIDATES, yellow.status)
        assertEquals("NCSSAR_MTRC4TD", yellow.primaryLabel)
        assertNull(yellow.telemetry)
    }

    @Test
    fun existingRuntimeBindingSurvivesMappedIdChangesAndLaterFlights() {
        val bindings = mutableMapOf("NCSSAR_MTRC4TD" to "1581F8")

        val firstFlight = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR83Mtrc4TD")),
            runtimeStreamBindings = bindings
        )
        val afterConfirmation = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            runtimeStreamBindings = bindings
        )

        assertEquals(StreamTelemetryBindingStatus.PAIRED, firstFlight.status)
        assertEquals("1SAR83Mtrc4TD", firstFlight.primaryLabel)
        assertEquals(StreamTelemetryBindingStatus.PAIRED, afterConfirmation.status)
        assertEquals("1SAR138DjMtrc4td", afterConfirmation.primaryLabel)
        assertEquals("1581F8", bindings["NCSSAR_MTRC4TD"])
    }

    @Test
    fun configuredStreamBindingPairsByRidMapDefaultAndUsesCurrentMappedIdLabel() {
        val runtimeBindings = mutableMapOf<String, String>()
        val configuredBindings = mapOf("NCSSAR_MTRC4TD" to "1581F8")

        val resolved = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "NCSSAR_MTRC4TD")),
            runtimeStreamBindings = runtimeBindings,
            configuredStreamBindings = configuredBindings
        )
        val afterPilotCallsignChange = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            runtimeStreamBindings = runtimeBindings,
            configuredStreamBindings = configuredBindings
        )

        assertEquals(StreamTelemetryBindingStatus.PAIRED, resolved.status)
        assertEquals("NCSSAR_MTRC4TD", resolved.primaryLabel)
        assertNull(runtimeBindings["NCSSAR_MTRC4TD"])
        assertEquals(StreamTelemetryBindingStatus.PAIRED, afterPilotCallsignChange.status)
        assertEquals("1SAR138DjMtrc4td", afterPilotCallsignChange.primaryLabel)
    }

    @Test
    fun configuredStreamBindingMatchesControllerDesignatorCaseInsensitively() {
        val runtimeBindings = mutableMapOf<String, String>()
        val configuredBindings = mapOf("NCSSAR_MTRC4TD" to "1581F8")

        val resolved = resolveStreamTelemetryBinding(
            streamDesignator = "ncssar_mtrc4td",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "NCSSAR_MTRC4TD")),
            runtimeStreamBindings = runtimeBindings,
            configuredStreamBindings = configuredBindings
        )

        assertEquals(StreamTelemetryBindingStatus.PAIRED, resolved.status)
        assertEquals("NCSSAR_MTRC4TD", resolved.primaryLabel)
        assertEquals("1581F8", resolved.telemetry?.remoteId)
        assertNull(runtimeBindings["ncssar_mtrc4td"])
    }

    @Test
    fun pairAnywayRuntimeOverrideDoesNotPersistToConfiguredDefault() {
        val runtimeBindings = mutableMapOf<String, String>()
        val configuredBindings = mapOf("NCSSAR_MTRC4TD" to "1581F8")

        bindStreamToRemoteId(runtimeBindings, "1SAR83Mtrc4Td1", "1581F8")
        val resolved = resolveStreamTelemetryBinding(
            streamDesignator = "1SAR83Mtrc4Td1",
            telemetryStates = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            runtimeStreamBindings = runtimeBindings,
            configuredStreamBindings = configuredBindings
        )

        assertEquals(StreamTelemetryBindingStatus.PAIRED, resolved.status)
        assertEquals("1SAR138DjMtrc4td", resolved.primaryLabel)
        assertEquals(mapOf("NCSSAR_MTRC4TD" to "1581F8"), configuredBindings)
    }

    @Test
    fun warningIsShownWhenRuntimePairingDiffersFromConfiguredDesignator() {
        val warning = streamTelemetryPairingWarning(
            streamDesignator = "1SAR83Mtrc4Td1",
            selectedTelemetry = testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td"),
            configuredStreamDesignatorByRemoteId = mapOf("1581F8" to "NCSSAR_MTRC4TD")
        )

        assertEquals("1SAR83Mtrc4Td1", warning?.streamDesignator)
        assertEquals("1581F8", warning?.remoteId)
        assertEquals("NCSSAR_MTRC4TD", warning?.configuredStreamDesignator)
    }

    @Test
    fun noWarningWhenRuntimePairingMatchesConfiguredDesignator() {
        val warning = streamTelemetryPairingWarning(
            streamDesignator = "NCSSAR_MTRC4TD",
            selectedTelemetry = testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td"),
            configuredStreamDesignatorByRemoteId = mapOf("1581F8" to "NCSSAR_MTRC4TD")
        )

        assertNull(warning)
    }

    @Test
    fun singleCandidatePairingControlShowsMismatchWarningDirectly() {
        val action = streamTelemetryPairingControlAction(
            streamDesignator = "1SAR83Mtrc4Td1",
            candidateTelemetry = listOf(testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")),
            configuredStreamDesignatorByRemoteId = mapOf("1581F8" to "NCSSAR_MTRC4TD")
        )

        assertEquals(StreamTelemetryPairingControlAction.ShowWarning, action.kind)
        assertEquals("1581F8", action.warning?.remoteId)
        assertEquals("NCSSAR_MTRC4TD", action.warning?.configuredStreamDesignator)
    }

    @Test
    fun multipleCandidatePairingControlStillShowsPicker() {
        val action = streamTelemetryPairingControlAction(
            streamDesignator = "1SAR83Mtrc4Td1",
            candidateTelemetry = listOf(
                testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td"),
                testTelemetry(remoteId = "1581F9", mappedId = "1SAR139DjMtrc4td")
            ),
            configuredStreamDesignatorByRemoteId = mapOf("1581F8" to "NCSSAR_MTRC4TD")
        )

        assertEquals(StreamTelemetryPairingControlAction.ShowPicker, action.kind)
        assertNull(action.warning)
    }

    @Test
    fun confirmedDroneTargetsOnlyUnambiguousLiveUnpairedStream() {
        val target = automaticStreamTelemetryPairingTarget(
            confirmedRemoteId = "1581F8",
            candidateTelemetry = listOf(
                testTelemetry(remoteId = "1581F8", mappedId = "1SAR7DjMn4Pr")
            ),
            liveUnpairedStreamDesignators = listOf("1sar7mn4pr")
        )

        assertEquals("1sar7mn4pr", target)
        assertNull(automaticStreamTelemetryPairingTarget(
            confirmedRemoteId = "1581F8",
            candidateTelemetry = listOf(
                testTelemetry(remoteId = "1581F8", mappedId = "1SAR7DjMn4Pr"),
                testTelemetry(remoteId = "1581F9", mappedId = "1SAR62Mn4Pr")
            ),
            liveUnpairedStreamDesignators = listOf("1sar7mn4pr")
        ))
        assertNull(automaticStreamTelemetryPairingTarget(
            confirmedRemoteId = "1581F8",
            candidateTelemetry = listOf(
                testTelemetry(remoteId = "1581F8", mappedId = "1SAR7DjMn4Pr")
            ),
            liveUnpairedStreamDesignators = listOf("stream-one", "stream-two")
        ))
    }

    @Test
    fun configuredBindingMapsAreBuiltOnceFromPersistedRidMapDefaults() {
        val maps = configuredStreamTelemetryBindingMaps(
            listOf(
                testTelemetry(remoteId = "1581F8", mappedId = "NCSSAR_MTRC4TD"),
                testTelemetry(remoteId = "1581F9", mappedId = ""),
                testTelemetry(remoteId = "", mappedId = "NCSSAR_MTRC5TD")
            )
        )

        assertEquals(mapOf("NCSSAR_MTRC4TD" to "1581F8"), maps.streamDesignatorToRemoteId)
        assertEquals(mapOf("1581F8" to "NCSSAR_MTRC4TD"), maps.remoteIdToStreamDesignator)
    }

    @Test
    fun clearingRuntimeBindingDoesNotClearTelemetryMappedId() {
        val telemetry = testTelemetry(remoteId = "1581F8", mappedId = "1SAR138DjMtrc4td")
        val bindings = mutableMapOf("NCSSAR_MTRC4TD" to telemetry.remoteId)

        clearStreamTelemetryBinding(bindings, "NCSSAR_MTRC4TD")
        val resolved = resolveStreamTelemetryBinding(
            streamDesignator = "NCSSAR_MTRC4TD",
            telemetryStates = listOf(telemetry),
            runtimeStreamBindings = bindings
        )

        assertNull(bindings["NCSSAR_MTRC4TD"])
        assertEquals("1SAR138DjMtrc4td", telemetry.mappedId)
        assertEquals(StreamTelemetryBindingStatus.UNPAIRED_WITH_CANDIDATES, resolved.status)
    }

    @Test
    fun pairedPublisherDoesNotKeepFlightActiveWithoutSei() {
        StreamFlightActivityRegistry.bindRuntime("RC2/Red1", "RID-1")
        StreamFlightActivityRegistry.replaceLivePublishers(listOf("RC2/Red1"), 10_000L)

        val activity = StreamFlightActivityRegistry.activityForRemoteId("RID-1", 20_000L)

        assertEquals(true, activity.publisherActive)
        assertEquals(0L, activity.lastActivityAtMs)
    }

    @Test
    fun publisherStopAndRestartDoNotRefreshTelemetry() {
        StreamFlightActivityRegistry.bindRuntime("Red1", "RID-1")
        StreamFlightActivityRegistry.replaceLivePublishers(listOf("Red1"), 10_000L)
        StreamFlightActivityRegistry.replaceLivePublishers(emptyList(), 15_000L)

        val stopped = StreamFlightActivityRegistry.activityForRemoteId("RID-1", 20_000L)
        assertEquals(false, stopped.publisherActive)
        assertEquals(0L, stopped.lastActivityAtMs)

        StreamFlightActivityRegistry.replaceLivePublishers(listOf("red1"), 25_000L)
        val restarted = StreamFlightActivityRegistry.activityForRemoteId("RID-1", 26_000L)
        assertEquals(true, restarted.publisherActive)
        assertEquals(0L, restarted.lastActivityAtMs)
    }

    @Test
    fun actualSeiReceiptExpiresEvenWithStuckPublisherAndWallClockChange() {
        StreamFlightActivityRegistry.bindRuntime("red1", "RID-1")
        StreamFlightActivityRegistry.replaceLivePublishers(listOf("red1"), 1000)
        StreamFlightActivityRegistry.noteTelemetryReceived("red1", elapsedMs = 1000)
        val before = StreamFlightActivityRegistry.activityForRemoteId("RID-1", 100_000, 30_999)
        assertEquals(29_999L, 100_000 - before.lastActivityAtMs)
        val after = StreamFlightActivityRegistry.activityForRemoteId("RID-1", 9_000_000, 31_000)
        assertEquals(30_000L, 9_000_000 - after.lastActivityAtMs)
        StreamFlightActivityRegistry.replaceLivePublishers(emptyList(), 9_000_000)
        assertEquals(after.lastActivityAtMs, StreamFlightActivityRegistry.activityForRemoteId("RID-1", 9_000_000, 31_000).lastActivityAtMs)
    }

    @Test
    fun configuredBindingTracksCaseInsensitivePublisherReconnect() {
        StreamFlightActivityRegistry.replaceConfigured(mapOf("NCSSAR_MTRC4TD" to "RID-1"))
        StreamFlightActivityRegistry.replaceLivePublishers(listOf("ncssar_mtrc4td"), 10_000L)

        assertEquals(
            true,
            StreamFlightActivityRegistry.activityForRemoteId("RID-1", 11_000L).publisherActive,
        )
    }

    @Test
    fun pairedStreamExposesLastSeiReceiptForBoundAircraft() {
        StreamFlightActivityRegistry.bindRuntime("Red1", "RID-1")
        StreamCameraTelemetryRegistry.update(
            designator = "red1",
            telemetry = FfmpegTelemetry(
                sourceTag = "dji-sei-245",
                gimbalPitchDeg = -30.0,
                cameraYawDeg = 90.0,
                horizontalFovDeg = 40.0,
                verticalFovDeg = 25.0,
                latitude = 38.85,
                longitude = -121.06,
                altitudeMeters = 330.0,
            ),
            nowMs = 12_345L,
        )

        val activity = StreamFlightActivityRegistry.seiActivityForRemoteId("RID-1")

        assertEquals(true, activity.paired)
        assertEquals(12_345L, activity.lastSeiActivityAtMs)
    }

    private fun testTelemetry(remoteId: String, mappedId: String): StreamTelemetryState {
        return StreamTelemetryState(remoteId = remoteId, mappedId = mappedId)
    }
}

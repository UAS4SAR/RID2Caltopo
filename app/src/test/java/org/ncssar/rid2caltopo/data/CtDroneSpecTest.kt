package org.ncssar.rid2caltopo.data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class CtDroneSpecTest {
    @Before
    fun setUp() {
        clearLocationState()
    }

    @After
    fun tearDown() {
        clearLocationState()
    }

    private fun clearLocationState() {
        CtDroneSpec.ClearMyLocationBaselineForTests()
        CaltopoMap.MyLocation = null
        CaltopoMap.SetMyLocationOverride(null)
    }

    private fun setTabletLocation(lat: Double, lng: Double, timeMs: Long) {
        CtDroneSpec.UpdateMyLocationBaseline(lat, lng, timeMs)
    }

    @Test
    fun guessMakeModel_matchesKnownSerialPrefixes() {
        assertEquals("DJI Mini 4 Pro", CtDroneSpec.GuessMakeModel("1581F6Z9C24BH0036EJL"))
        assertEquals("DJI Mavic 3 Pro", CtDroneSpec.GuessMakeModel("1581F67QE239L00A00DE"))
        assertEquals("DJI Matrice 4TD", CtDroneSpec.GuessMakeModel("1581F8HGX255S00A0FZT"))
        assertEquals("DJI Avata 360", CtDroneSpec.GuessMakeModel("1581FBLKC262T00B07G1"))
        assertEquals("Autel Evo Max 4N", CtDroneSpec.GuessMakeModel("1748FEV3HMK924451281"))
        assertEquals("Potensic Atom LT", CtDroneSpec.GuessMakeModel("1910F916JJHWLHEFGVYC"))
        assertEquals("", CtDroneSpec.GuessMakeModel("1668BR40EA00Z5VX"))
    }

    @Test
    fun buildMappedId_usesModelAbbreviation() {
        assertEquals(
            "1sar7DjMn4Pr",
            CtDroneSpec.BuildMappedId("1sar7", "DJI Mini 4 Pro", "1581F6Z9C24BH0036EJL")
        )
        assertEquals(
            "1sar10PtnscAtm2lt",
            CtDroneSpec.BuildMappedId("1sar10", "Potensic Atom LT", "1910F916JJHWLHEFGVYC")
        )
    }

    @Test
    fun guessPilotCallsign_extractsPilotAndPreservesTeamSuffixes() {
        assertEquals(
            "1sar7",
            CtDroneSpec.GuessPilotCallsign(
                "1sar7DjMn4Pr",
                "DJI Mini 4 Pro",
                "1581F6Z9C24BH0036EJL"
            )
        )
        assertEquals(
            "1sar1001-01",
            CtDroneSpec.GuessPilotCallsign(
                "1sar1001DjMn4Pr-01",
                "DJI Mini 4 Pro",
                "1581F6Z9C2527003BZFX"
            )
        )
        assertEquals(
            "",
            CtDroneSpec.GuessPilotCallsign(
                "1668BR40EA00Z5VX",
                "",
                "1668BR40EA00Z5VX"
            )
        )
    }

    @Test
    fun buildMappedId_preservesTeamSuffixesInCallsign() {
        assertEquals(
            "1sar1002-02DjMtrc4td",
            CtDroneSpec.BuildMappedId("1sar1002-02", "DJI Matrice 4TD", "1581F8HGX256G00A0JPU")
        )
    }

    @Test
    fun displayLabel_usesPilotCallsignWithoutChangingStreamDesignator() {
        val drone = CtDroneSpec(
            "1581F6Z9C24BH0036EJL",
            "MINI4PRO",
            "NCSSAR",
            "DJI Mini 4 Pro",
            "1SAR7"
        )

        assertEquals("1SAR7", drone.displayLabel)
        assertEquals("MINI4PRO", drone.mappedId)
        drone.owner = "1SAR8"
        assertEquals("1SAR8", drone.displayLabel)
        assertEquals("MINI4PRO", drone.mappedId)
    }

    @Test
    fun checkNewWaypoint_acceptsAfterTabletLocationBaselineRefresh() {
        CtDroneSpec.MyLat = 38.0
        CtDroneSpec.MyLng = -120.0
        CtDroneSpec.UpdateMyLocationBaseline(39.0719204, -121.5505101)
        val drone = CtDroneSpec("RID123")

        val accepted = drone.checkNewWaypoint(
            39.0719113,
            -121.5508618,
            100.0,
            1_000L,
            1_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )

        assertTrue(accepted)
        assertEquals(39.0719204, CtDroneSpec.MyLat, 0.000001)
        assertEquals(-121.5505101, CtDroneSpec.MyLng, 0.000001)
    }

    @Test
    fun checkNewWaypoint_allowsGpsToleranceAboveOperatingLimitAndRejectsOverTwoHundredMph() {
        val drone = CtDroneSpec("RID123")
        val startMs = 10_000L
        val feetPerLatitudeDegree = 364_813.0
        val startLatitude = 39.0

        assertTrue(drone.checkNewWaypoint(
            startLatitude,
            -121.0,
            100.0,
            startMs,
            startMs,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        ))
        assertTrue(drone.checkNewWaypoint(
            startLatitude + 150.0 / feetPerLatitudeDegree,
            -121.0,
            100.0,
            startMs + 1_000L,
            startMs + 1_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        ))
        assertFalse(drone.checkNewWaypoint(
            startLatitude + 450.0 / feetPerLatitudeDegree,
            -121.0,
            100.0,
            startMs + 2_000L,
            startMs + 2_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        ))
    }

    @Test
    fun signalIdleTime_tracksReceivedRidPacketBeforeWaypointAcceptance() {
        val drone = CtDroneSpec("RID123")
        val nowMs = 12_345L

        drone.noteRidPositionPacketReceived(nowMs)

        assertEquals(nowMs, drone.getMostRecentSignalMsecTimestamp())
        assertEquals(1500L, drone.signalIdleTimeInMsec(nowMs + 1500L))
    }

    @Test
    fun trackTelemetryIdleTime_usesSignalPacketsThatAreNotAcceptedWaypoints() {
        val drone = CtDroneSpec("RID123")

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            1_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        drone.noteRidPositionPacketReceived(10_000L)

        assertEquals(9_500L, drone.idleTimeInMsec(10_500L))
        assertEquals(500L, drone.signalIdleTimeInMsec(10_500L))
        assertEquals(500L, drone.trackTelemetryIdleTimeInMsec(10_500L))
    }

    @Test
    fun trackTelemetryIdleTime_usesPeerTelemetryWithoutClearingLocalSignalIdle() {
        val drone = CtDroneSpec("RID123")

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            1_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        drone.notePeerTelemetryReceived(10_000L)

        assertEquals(9_500L, drone.idleTimeInMsec(10_500L))
        assertEquals(9_500L, drone.signalIdleTimeInMsec(10_500L))
        assertEquals(500L, drone.trackTelemetryIdleTimeInMsec(10_500L))
    }

    @Test
    fun trackTelemetryIdleTime_usesNonLocationAircraftMessagesWithoutClearingLocationStaleness() {
        val drone = CtDroneSpec("RID123")

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            1_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        drone.noteAircraftMessageReceived(10_000L)

        assertEquals(9_500L, drone.signalIdleTimeInMsec(10_500L))
        assertEquals(500L, drone.trackTelemetryIdleTimeInMsec(10_500L))
    }

    @Test
    fun noteRidPositionPacketReceived_learnsPacketCadenceAtIngress() {
        val drone = CtDroneSpec("RID123")
        val nowMs = 20_000L

        drone.noteRidPositionPacketReceived(nowMs)
        drone.noteRidPositionPacketReceived(nowMs + 3_000L)
        drone.noteRidPositionPacketReceived(nowMs + 9_000L)

        assertEquals(2, drone.learnedSignalIntervalSamples)
        assertEquals(3_750L, drone.learnedSignalIntervalMs)
    }

    @Test
    fun noteRidPositionPacketReceived_ignoresBurstSpacingForCadenceLearning() {
        val drone = CtDroneSpec("RID123")
        val nowMs = 30_000L

        drone.noteRidPositionPacketReceived(nowMs)
        drone.noteRidPositionPacketReceived(nowMs + 36L)
        drone.noteRidPositionPacketReceived(nowMs + 3_800L)
        drone.noteRidPositionPacketReceived(nowMs + 3_845L)
        drone.noteRidPositionPacketReceived(nowMs + 7_600L)

        assertEquals(nowMs + 7_600L, drone.getMostRecentSignalMsecTimestamp())
        assertEquals(2, drone.learnedSignalIntervalSamples)
        assertEquals(3_761L, drone.learnedSignalIntervalMs)
    }

    @Test
    fun updateAltitudeContext_firstLowAtoSampleSeedsImpliedTakeoffAltitude() {
        val drone = CtDroneSpec("RID123")

        drone.updateAltitudeContext(
            101.0,
            CtDroneSpec.AltSourceEnum.BARO,
            1.0,
            true
        )

        assertEquals(100.0, drone.impliedTakeoffAltM!!, 0.000001)
    }

    @Test
    fun updateAltitudeContext_followupLowAtoSampleDoesNotKeepNudgingSeed() {
        val drone = CtDroneSpec("RID123")

        drone.updateAltitudeContext(
            101.0,
            CtDroneSpec.AltSourceEnum.BARO,
            1.0,
            true
        )
        drone.updateAltitudeContext(
            103.0,
            CtDroneSpec.AltSourceEnum.BARO,
            1.0,
            true
        )

        assertEquals(100.0, drone.impliedTakeoffAltM!!, 0.000001)
    }

    @Test
    fun reset_clearsOutOfRangeClassification() {
        val drone = CtDroneSpec("RID123")

        drone.setOutOfRange(true)
        assertEquals(true, drone.isOutOfRange)

        drone.reset()

        assertEquals(false, drone.isOutOfRange)
    }

    @Test
    fun repeatedStationaryRidReports_areExposedForLandingSuppression() {
        val drone = CtDroneSpec("RID123")

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            1_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        assertEquals(false, drone.hasStationaryRidReports())

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            2_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        assertEquals(false, drone.hasStationaryRidReports())

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            3_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        assertEquals(true, drone.hasStationaryRidReports())

        drone.checkNewWaypoint(
            39.0001,
            -121.0,
            100.0,
            4_000L,
            4_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        assertEquals(false, drone.hasStationaryRidReports())
    }

    @Test
    fun firstAcceptedWaypoint_recordsTakeoffLocationUntilReset() {
        val drone = CtDroneSpec("RID123")

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            1_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )

        assertEquals(true, drone.hasTakeoffLocation())
        assertEquals(39.0, drone.takeoffLat, 0.000001)
        assertEquals(-121.0, drone.takeoffLng, 0.000001)

        drone.checkNewWaypoint(
            39.001,
            -121.001,
            110.0,
            2_000L,
            2_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )

        assertEquals(39.0, drone.takeoffLat, 0.000001)
        assertEquals(-121.0, drone.takeoffLng, 0.000001)

        drone.reset()

        assertEquals(false, drone.hasTakeoffLocation())
    }

    @Test
    fun acceptedWaypointNearTablet_recordsHomeLocationThatSurvivesTrackReset() {
        val drone = CtDroneSpec("RID123")
        val nowMs = System.currentTimeMillis()
        setTabletLocation(39.0, -121.0, nowMs)

        assertTrue(drone.checkNewWaypoint(
            39.0001,
            -121.0,
            100.0,
            nowMs,
            nowMs,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        ))

        assertTrue(drone.hasHomeLocation())
        assertEquals(39.0001, drone.homeLat, 0.000001)
        assertEquals(-121.0, drone.homeLng, 0.000001)

        drone.reset()

        assertFalse(drone.hasTakeoffLocation())
        assertTrue(drone.hasHomeLocation())
        assertEquals(39.0001, drone.homeLat, 0.000001)
        assertEquals(-121.0, drone.homeLng, 0.000001)
    }

    @Test
    fun acceptedWaypointFarFromTablet_doesNotRecordHomeUntilLaterNearTabletTrack() {
        val drone = CtDroneSpec("RID123")
        val firstNowMs = System.currentTimeMillis()
        val secondNowMs = firstNowMs + 70_000L
        setTabletLocation(39.0, -121.0, firstNowMs)

        assertTrue(drone.checkNewWaypoint(
            39.0100,
            -121.0,
            100.0,
            firstNowMs,
            firstNowMs,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        ))

        assertFalse(drone.hasHomeLocation())

        drone.reset()
        setTabletLocation(39.0, -121.0, secondNowMs)

        assertTrue(drone.checkNewWaypoint(
            39.0001,
            -121.0,
            100.0,
            secondNowMs,
            secondNowMs,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        ))

        assertTrue(drone.hasHomeLocation())
        assertEquals(39.0001, drone.homeLat, 0.000001)
        assertEquals(-121.0, drone.homeLng, 0.000001)
    }

    @Test
    fun activeTrackAwayFromHome_doesNotExtendConfiguredIdleLimit() {
        val drone = CtDroneSpec("RID123")
        val newTrackDelayMs = 30_000L
        val firstNowMs = System.currentTimeMillis()
        val secondNowMs = firstNowMs + 90_000L
        setTabletLocation(39.0, -121.0, firstNowMs)

        assertTrue(drone.checkNewWaypoint(
            39.0001,
            -121.0,
            100.0,
            firstNowMs,
            firstNowMs,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        ))
        assertTrue(drone.checkNewWaypoint(
            39.0040,
            -121.0,
            100.0,
            secondNowMs,
            secondNowMs,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        ))

        assertEquals(
            newTrackDelayMs,
            CaltopoClient.TrackDelayInMsecForDroneSpecForTests(drone, newTrackDelayMs)
        )
    }

    @Test
    fun activeTrackFarFromTabletBeforeHome_doesNotExtendConfiguredIdleLimit() {
        val drone = CtDroneSpec("RID123")
        val newTrackDelayMs = 30_000L
        val nowMs = System.currentTimeMillis()
        setTabletLocation(39.0, -121.0, nowMs)

        assertTrue(drone.checkNewWaypoint(
            39.0100,
            -121.0,
            100.0,
            nowMs,
            nowMs,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        ))

        assertFalse(drone.hasHomeLocation())
        assertEquals(
            newTrackDelayMs,
            CaltopoClient.TrackDelayInMsecForDroneSpecForTests(drone, newTrackDelayMs)
        )
    }

    @Test
    fun outOfRangeTrack_doesNotExtendConfiguredIdleLimit() {
        val drone = CtDroneSpec("RID123")
        val newTrackDelayMs = 30_000L

        drone.setOutOfRange(true)

        assertTrue(drone.isOutOfRange)
        assertEquals(
            newTrackDelayMs,
            CaltopoClient.TrackDelayInMsecForDroneSpecForTests(drone, newTrackDelayMs)
        )
    }

    @Test
    fun trackAging_terminatesAtConfiguredIdleLimit() {
        val newTrackDelayMs = 30_000L

        assertFalse(CaltopoClient.HasTrackAgedOutForTests(29_999L, newTrackDelayMs))
        assertTrue(CaltopoClient.HasTrackAgedOutForTests(30_000L, newTrackDelayMs))
        assertTrue(CaltopoClient.HasTrackAgedOutForTests(30_001L, newTrackDelayMs))
    }

    @Test
    fun flightIdle_withoutPairedVideo_usesRidPeerIdle() {
        assertEquals(29_999L, CaltopoClient.CombinedFlightIdleTimeInMsecForTests(
            29_999L, 100_000L, false, 0L
        ))
    }

    @Test
    fun flightIdle_withActivePairedPublisher_staysActiveWithoutSei() {
        assertEquals(0L, CaltopoClient.CombinedFlightIdleTimeInMsecForTests(
            60_000L, 100_000L, true, 100_000L
        ))
    }

    @Test
    fun flightIdle_afterVideoStops_startsAtLaterPresenceLoss() {
        assertEquals(29_999L, CaltopoClient.CombinedFlightIdleTimeInMsecForTests(
            120_000L, 100_000L, false, 70_001L
        ))
        assertEquals(30_000L, CaltopoClient.CombinedFlightIdleTimeInMsecForTests(
            120_000L, 100_000L, false, 70_000L
        ))
    }

    @Test
    fun flightIdle_recentRidWinsAfterVideoHasBeenGoneLonger() {
        assertEquals(5_000L, CaltopoClient.CombinedFlightIdleTimeInMsecForTests(
            5_000L, 100_000L, false, 40_000L
        ))
    }

    @Test
    fun activeTrackNearHome_usesConfiguredNewTrackDelay() {
        val drone = CtDroneSpec("RID123")
        val newTrackDelayMs = 30_000L
        val nowMs = System.currentTimeMillis()
        setTabletLocation(39.0, -121.0, nowMs)

        assertTrue(drone.checkNewWaypoint(
            39.0001,
            -121.0,
            100.0,
            nowMs,
            nowMs,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        ))

        assertEquals(
            newTrackDelayMs,
            CaltopoClient.TrackDelayInMsecForDroneSpecForTests(drone, newTrackDelayMs)
        )
    }
}

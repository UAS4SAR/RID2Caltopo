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
    fun stationaryRidRefreshesTelemetryButRecordsOnlyEveryThreeSeconds() {
        val spec = CtDroneSpec("RIDLIVE")
        val source = CtDroneSpec.TransportTypeEnum.WIFI
        var recorded = 0
        for (step in 0..60) {
            val now = 10_000L + step * 100L
            assertTrue(spec.checkNewWaypoint(39.0, -121.0, 100.0 + step, now, now, true, source))
            spec.updateAltitudeContext(100.0 + step, CtDroneSpec.AltSourceEnum.BARO, step.toDouble(), true, now)
            if (spec.shouldRecordWaypoint(39.0, -121.0, now, 2.0, source)) recorded++
            assertEquals(now, spec.mostRecentMsecTimestamp)
            assertEquals(step.toDouble(), spec.lastRidHeightM, 0.00001)
        }
        assertEquals(3, recorded) // 0, 3, 6 seconds; suppressed reports never move the recording clock.
        assertFalse(spec.checkNewWaypoint(40.0, -121.0, 500.0, 16_100, 16_100, true, source))
        assertEquals(16_000L, spec.mostRecentMsecTimestamp)
        assertTrue(spec.trackDiagnosticSummary(21_000, false, 0, 0, 30_000).contains("positionAgeMs=5000"))
        spec.reset()
        assertTrue(spec.checkNewWaypoint(39.0, -121.0, 100.0, 16_200, 16_200, true, source))
        assertTrue(spec.shouldRecordWaypoint(39.0, -121.0, 16_200, 2.0, source))
    }

    @Test
    fun recordingMeasuresMovementFromLastRecordedPointAndKeepsNearbyKeepalive() {
        val spec = CtDroneSpec("RIDMOVE")
        val source = CtDroneSpec.TransportTypeEnum.BT5
        assertTrue(spec.shouldRecordWaypoint(39.0, -121.0, 10_000, 2.0, source))
        assertFalse(spec.shouldRecordWaypoint(39.000002, -121.0, 11_000, 2.0, source))
        assertFalse(spec.shouldRecordWaypoint(39.000004, -121.0, 12_000, 2.0, source))
        assertTrue(spec.shouldRecordWaypoint(39.000006, -121.0, 12_100, 2.0, source))
        assertFalse(spec.shouldRecordWaypoint(39.000007, -121.0, 15_099, 2.0, source))
        assertTrue(spec.shouldRecordWaypoint(39.000007, -121.0, 15_100, 2.0, source))
    }

    @Test
    fun movingWaypointArrivingWithinOneSecondIsSuppressed() {
        val spec = CtDroneSpec("RIDFAST")
        val source = CtDroneSpec.TransportTypeEnum.BT5
        assertTrue(spec.shouldRecordWaypoint(39.0, -121.0, 10_000, 2.0, source))
        assertFalse(spec.shouldRecordWaypoint(39.0001, -121.0, 10_500, 2.0, source))
        assertTrue(spec.shouldRecordWaypoint(39.0002, -121.0, 11_000, 2.0, source))
    }

    @Test
    fun invalidCoordinatesNeverRefreshLiveTelemetry() {
        val spec = CtDroneSpec("RIDINVALID")
        val source = CtDroneSpec.TransportTypeEnum.WIFI
        for ((lat, lon) in listOf(Double.NaN to -121.0, 39.0 to Double.POSITIVE_INFINITY,
                91.0 to -121.0, 39.0 to -181.0, 0.0 to -121.0)) {
            assertFalse(spec.checkNewWaypoint(lat, lon, 100.0, 10_000, 10_000, true, source))
        }
        assertEquals(0, spec.goodCount)
    }

    @Test
    fun diagnosticSeparatesAircraftPresenceFromMissingPositionWithoutCoordinates() {
        val spec = CtDroneSpec("1748FEV2HN7824041047")
        val now = System.currentTimeMillis()
        assertTrue(spec.checkNewWaypoint(39.1, -121.1, 600.0, now, now, true,
            CtDroneSpec.TransportTypeEnum.WIFI))
        assertFalse(spec.checkNewWaypoint(0.0, 0.0, 600.0, now + 1000, now + 1000, false,
            CtDroneSpec.TransportTypeEnum.WIFI))
        spec.noteAircraftMessageReceived(now + 19_000)
        val summary = spec.trackDiagnosticSummary(now + 20_000, true, now + 20_000, 0, 30_000)
        assertTrue(summary.contains("accepted=1 invalid=1"))
        assertTrue(summary.contains("positionAgeMs=20000"))
        assertTrue(summary.contains("aircraftMessageAgeMs=1000"))
        assertTrue(summary.contains("publisherActive=true"))
        assertTrue(summary.contains("reportedAirborne=false"))
        assertEquals(summary, CaltopoClient.RedactLocationFromDiagnosticMessage(summary))
        assertFalse(summary.contains("39.1"))
        assertFalse(summary.contains("-121.1"))
        spec.reset()
        assertTrue(spec.trackDiagnosticSummary(now + 20_001, false, 0, 0, 30_000)
            .contains("accepted=0 invalid=0"))
    }

    @Test
    fun trackTimestampMatchesAppleRegardlessOfDeviceLanguage() {
        val oldLocale = java.util.Locale.getDefault()
        val oldZone = java.util.TimeZone.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("161522Sep12", CaltopoClient.TimeDatestampString(1789254922680L))
        } finally {
            java.util.Locale.setDefault(oldLocale)
            java.util.TimeZone.setDefault(oldZone)
        }
    }

    @Test
    fun embeddedVideoAcceptsHeightUpdatesWhileHoveringWithoutRid() {
        val spec = CtDroneSpec("1581F8HGX255S00A0FZT")
        val now = System.currentTimeMillis()
        assertTrue(spec.checkNewWaypoint(39.1536, -121.1322, 605.742, now, now, null,
            CtDroneSpec.TransportTypeEnum.DJI_STREAM))
        spec.updateAltitudeContext(605.742, CtDroneSpec.AltSourceEnum.DJI_STREAM, 4.803, true)
        assertTrue(spec.checkNewWaypoint(39.1536, -121.1322, 606.742, now + 1000, now + 1000, null,
            CtDroneSpec.TransportTypeEnum.DJI_STREAM))
        spec.updateAltitudeContext(606.742, CtDroneSpec.AltSourceEnum.DJI_STREAM, 5.803, true)
        assertEquals(5.803, spec.lastRidHeightM, 0.00001)
        assertFalse(spec.hasFreshAolGroundStatus(now + 1000))
        assertEquals(2, spec.getTransportCount(CtDroneSpec.TransportTypeEnum.DJI_STREAM))
        assertEquals(0, spec.getTransportCount(CtDroneSpec.TransportTypeEnum.BT5))
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
    fun displayLabel_usesLegacyDesignator() {
        val drone = CtDroneSpec(
            "1581F6Z9C24BH0036EJL",
            "MINI4PRO",
            "NCSSAR",
            "DJI Mini 4 Pro",
            "1SAR7"
        )

        assertEquals("MINI4PRO", drone.displayLabel)
        assertEquals("MINI4PRO", drone.mappedId)
        drone.owner = "1SAR8"
        assertEquals("MINI4PRO", drone.displayLabel)
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
        assertEquals(500L, drone.trackTelemetryIdleTimeInMsec(10_500L, android.os.SystemClock.elapsedRealtime() + 500L))
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
        assertEquals(500L, drone.trackTelemetryIdleTimeInMsec(10_500L, android.os.SystemClock.elapsedRealtime() + 500L))
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
        assertEquals(500L, drone.trackTelemetryIdleTimeInMsec(10_500L, android.os.SystemClock.elapsedRealtime() + 500L))
    }

    @Test
    fun trackExpiryUsesElapsedReceiptTimeAcrossWallClockChanges() {
        val drone = CtDroneSpec("CLOCK-RID")
        drone.noteAircraftMessageReceived(1_000_000)
        val receipt = android.os.SystemClock.elapsedRealtime()
        assertEquals(30_000L, drone.trackTelemetryIdleTimeInMsec(1, receipt + 30_000))
        assertEquals(30_000L, drone.trackTelemetryIdleTimeInMsec(9_000_000, receipt + 30_000))
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
    fun flightIdle_withActivePairedPublisher_expiresWithoutSei() {
        assertEquals(60_000L, CaltopoClient.CombinedFlightIdleTimeInMsecForTests(
            60_000L, 100_000L, true, 0L
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

package org.ncssar.rid2caltopo.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.FakePeerCoordinator
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
import org.ncssar.rid2caltopo.data.TestR2cRuntimeFactory
import org.ncssar.rid2caltopo.data.ProximityTelemetry
import org.ncssar.rid2caltopo.data.ProximityAlertConsent
import org.ncssar.rid2caltopo.data.ProximityPosition
import java.util.ArrayDeque
import java.util.concurrent.Executor

class ProximityAlertHostThresholdTest {
    @Before
    fun setUp() {
        ProximityAlertConsent.setAlertAllAircraft(false)
        ProximityAlertConsent.requestEnable()
        ProximityAlertConsent.confirmEnable()
        ProximityAlertCenter.setEvaluationExecutorForTests(Executor { it.run() })
    }

    @After
    fun tearDown() {
        ProximityAlertConsent.setAlertAllAircraft(false)
        ProximityAlertCenter.disableAlerts()
        ProximityAlertCenter.resetForTests()
        ProximityAlertCenter.clearEvaluationExecutorForTests()
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun exactHorizontalAndVerticalThresholdsStillCountAsInside() {
        val decision = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 40.0,
            effectiveVerticalFt = 40.0,
            effectiveThreeDFt = 56.57,
            currentThreeDFt = 60.0,
            thresholdFt = 40.0,
            predictionEnabled = true
        )

        assertTrue(decision.insideThreshold)
        assertTrue(decision.crossedIntoThreshold)
        assertTrue(decision.shouldAlert)
        assertFalse(decision.highSeverity)
        assertEquals(1.0, decision.severityScore, 0.0001)
    }

    @Test
    fun altitudeAboveThresholdBlocksAlertEvenWhenHorizontalIsInside() {
        val decision = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 20.0,
            effectiveVerticalFt = 40.1,
            effectiveThreeDFt = 44.82,
            currentThreeDFt = 50.0,
            thresholdFt = 40.0,
            predictionEnabled = true
        )

        assertFalse(decision.insideThreshold)
        assertFalse(decision.shouldAlert)
        assertTrue(decision.highSeverity)
    }

    @Test
    fun nonTeamPairsIgnoreVerticalSeparationForTrafficProximity() {
        val decision = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 20.0,
            effectiveVerticalFt = 400.0,
            effectiveThreeDFt = 400.5,
            currentThreeDFt = 400.5,
            thresholdFt = 40.0,
            predictionEnabled = true,
            altitudeSensitive = false
        )

        assertTrue(decision.insideThreshold)
        assertTrue(decision.shouldAlert)
        assertTrue(decision.highSeverity)
        assertEquals(0.5, decision.severityScore, 0.0001)
    }

    @Test
    fun proximityAlertRequiresAtLeastOneOwnedDroneButAllowsNonOwnedTraffic() {
        assertTrue(
            ProximityAlertCenter.shouldAlertForPairForTests(
                firstTeamDrone = true,
                firstLocalAlertEligible = true,
                secondTeamDrone = true,
                secondLocalAlertEligible = false
            )
        )
        assertTrue(
            ProximityAlertCenter.shouldAlertForPairForTests(
                firstTeamDrone = true,
                firstLocalAlertEligible = true,
                secondTeamDrone = false,
                secondLocalAlertEligible = false
            )
        )
        assertFalse(
            ProximityAlertCenter.shouldAlertForPairForTests(
                firstTeamDrone = true,
                firstLocalAlertEligible = false,
                secondTeamDrone = false,
                secondLocalAlertEligible = false
            )
        )
        assertFalse(
            ProximityAlertCenter.shouldAlertForPairForTests(
                firstTeamDrone = false,
                firstLocalAlertEligible = true,
                secondTeamDrone = false,
                secondLocalAlertEligible = true
            )
        )
    }

    @Test
    fun updateDronesAlertsWhenOwnedDroneIsNearNonOwnedTraffic() {
        CaltopoClient.SetProximityAlertSpacingFeet(40)
        CaltopoClient.SetPredictiveHeadEnabled(false)
        val fixture = TestR2cRuntimeFactory.create("proximity-owner")
        fixture.setAsDefaultRuntime()
        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        ProximityAlertCenter.resetForTests()

        val owned = proximityDrone(
            remoteId = "TEAMDRONE1",
            mappedId = "1sar7mn4pr",
            lat = 39.153099,
            lng = -121.132858,
            altMeters = 527.0,
            localArchiveOnly = false
        )
        val traffic = proximityDrone(
            remoteId = "TRAFFIC1",
            mappedId = "traffic1",
            lat = 39.153099,
            lng = -121.132828,
            altMeters = 544.5,
            localArchiveOnly = true
        )

        ProximityAlertCenter.updateDrones(listOf(owned, traffic))

        assertNull(ProximityAlertCenter.uiState.value)
        assertFalse(ProximityAlertCenter.debugPairs.value.single().alerting)

        peerCoordinator.setLocalOwnership("TEAMDRONE1", true)
        ProximityAlertCenter.resetForTests()
        ProximityAlertCenter.updateDrones(listOf(owned, traffic))

        val alert = ProximityAlertCenter.uiState.value
        assertNotNull(alert)
        assertEquals("1sar7mn4pr", alert?.nearestDroneMappedId)
        assertTrue(ProximityAlertCenter.debugPairs.value.single().alerting)

        ProximityAlertCenter.resetForTests()
        val otherTraffic = proximityDrone(
            remoteId = "TRAFFIC2",
            mappedId = "traffic2",
            lat = 39.153099,
            lng = -121.132858,
            altMeters = 527.0,
            localArchiveOnly = true
        )

        ProximityAlertCenter.updateDrones(listOf(traffic, otherTraffic))

        assertNull(ProximityAlertCenter.uiState.value)
        assertFalse(ProximityAlertCenter.debugPairs.value.single().alerting)
    }

    @Test
    fun updateDronesQueuesEvaluationOffCallerThread() {
        CaltopoClient.SetProximityAlertSpacingFeet(40)
        CaltopoClient.SetPredictiveHeadEnabled(false)
        val fixture = TestR2cRuntimeFactory.create("proximity-async")
        fixture.setAsDefaultRuntime()
        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        peerCoordinator.setLocalOwnership("TEAMDRONE1", true)
        val executor = ManualExecutor()
        ProximityAlertCenter.setEvaluationExecutorForTests(executor)
        ProximityAlertCenter.resetForTests()

        val owned = proximityDrone(
            remoteId = "TEAMDRONE1",
            mappedId = "1sar7mn4pr",
            lat = 39.153099,
            lng = -121.132858,
            altMeters = 527.0,
            localArchiveOnly = false
        )
        val traffic = proximityDrone(
            remoteId = "TRAFFIC1",
            mappedId = "traffic1",
            lat = 39.153099,
            lng = -121.132828,
            altMeters = 544.5,
            localArchiveOnly = true
        )

        ProximityAlertCenter.updateDrones(listOf(owned, traffic))

        assertEquals(1, executor.pendingCount)
        assertNull(ProximityAlertCenter.uiState.value)
        assertTrue(ProximityAlertCenter.debugPairs.value.isEmpty())

        executor.runNext()

        assertNotNull(ProximityAlertCenter.uiState.value)
        assertTrue(ProximityAlertCenter.debugPairs.value.single().alerting)
    }

    @Test
    fun highSeverityRequiresDroppingBelowSeventyFivePercentThreshold() {
        val atBoundary = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 30.0,
            effectiveVerticalFt = 40.0,
            effectiveThreeDFt = 50.0,
            currentThreeDFt = 55.0,
            thresholdFt = 40.0,
            predictionEnabled = true
        )
        val belowBoundary = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 29.9,
            effectiveVerticalFt = 40.0,
            effectiveThreeDFt = 49.93,
            currentThreeDFt = 55.0,
            thresholdFt = 40.0,
            predictionEnabled = true
        )

        assertFalse(atBoundary.highSeverity)
        assertTrue(belowBoundary.highSeverity)
    }

    @Test
    fun nonPredictiveModeDoesNotReAlertWhenPairIsStillInsideButSeparating() {
        val decision = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 20.0,
            effectiveVerticalFt = 10.0,
            effectiveThreeDFt = 22.36,
            currentThreeDFt = 22.36,
            thresholdFt = 40.0,
            predictionEnabled = false,
            previousHorizontalFt = 18.0,
            previousVerticalFt = 8.0,
            previousThreeDFt = 19.7
        )

        assertTrue(decision.insideThreshold)
        assertFalse(decision.crossedIntoThreshold)
        assertTrue(decision.isGettingFartherApart)
        assertFalse(decision.actuallyApproaching)
        assertFalse(decision.shouldAlert)
    }

    private fun qualityAlert(feet: Double, first: ProximityTelemetry = ProximityTelemetry(),
                             second: ProximityTelemetry = ProximityTelemetry(), ageMs: Long = 0): ProximityAlertUiState? {
        CaltopoClient.SetProximityAlertSpacingFeet(100) // Default; lower configured values are tested separately.
        CaltopoClient.SetPredictiveHeadEnabled(false)
        val fixture = TestR2cRuntimeFactory.create("proximity-quality")
        fixture.setAsDefaultRuntime()
        (fixture.peerCoordinator as FakePeerCoordinator).setLocalOwnership("A", true)
        val now = System.currentTimeMillis() - ageMs
        val a = proximityDrone("A", "A", 39.0, -121.0, 200.0, false)
        val b = proximityDrone("B", "B", 39.0 + feet * 0.3048 / 6371000 * 180 / Math.PI, -121.0, 50.0, false)
        a.proximityPosition = ProximityPosition(a.lastLat, a.lastLng, now, first)
        b.proximityPosition = ProximityPosition(b.lastLat, b.lastLng, now, second)
        ProximityAlertCenter.resetForTests()
        ProximityAlertCenter.updateDrones(listOf(a, b))
        return ProximityAlertCenter.uiState.value
    }

    private fun geodetic(altitude: Double, error: Double = 1.0, horizontal: Double = 1.0) =
        ProximityTelemetry(horizontal, altitude, ProximityTelemetry.Reference.GEODETIC, error)

    @Test fun unknownAccuracyAddsFiftyFeetPerAircraftAndPreservesReportedDistance() {
        val result = qualityAlert(150.0)
        assertNotNull(result)
        assertEquals(100.0, result!!.thresholdFt, 0.001)
        assertTrue(result.horizontalSeparationFt > 149.0)
        assertFalse(result.verticalSeparationKnown)
        assertNull(qualityAlert(250.0))
    }

    @Test fun reportedAccuracyExpandsThresholdAndDifferentTakeoffsUseAbsoluteAltitude() {
        assertNotNull(qualityAlert(250.0, geodetic(1200.0, horizontal = 30.0), geodetic(1200.0, horizontal = 30.0)))
        assertNull(qualityAlert(350.0, geodetic(1200.0, horizontal = 30.0), geodetic(1200.0, horizontal = 30.0)))
        val sameAltitude = qualityAlert(10.0, geodetic(1200.0), geodetic(1200.0))!!
        assertTrue(sameAltitude.verticalSeparationKnown)
        assertEquals(0.0, sameAltitude.verticalSeparationFt, 0.001)
        assertNull(qualityAlert(10.0, geodetic(100.0), geodetic(200.0)))
    }

    @Test fun verticalUncertaintyIncompatibleReferencesAndUnknownAccuracy() {
        assertNotNull(qualityAlert(10.0, geodetic(100.0, error = 25.0), geodetic(160.0, error = 25.0)))
        val pressure = ProximityTelemetry(1.0, 500.0, ProximityTelemetry.Reference.PRESSURE, 1.0)
        assertFalse(qualityAlert(10.0, geodetic(100.0), pressure)!!.verticalSeparationKnown)
        val unknown = ProximityTelemetry(1.0, 500.0, ProximityTelemetry.Reference.GEODETIC, null)
        assertFalse(qualityAlert(10.0, geodetic(100.0), unknown)!!.verticalSeparationKnown)
        assertFalse(qualityAlert(10.0, geodetic(100.0))!!.verticalSeparationKnown)
    }

    @Test fun ageExpandsHorizontalAndStaleAltitudeCannotSuppress() {
        assertNull(qualityAlert(250.0, geodetic(100.0), geodetic(100.0)))
        assertNull(qualityAlert(250.0, geodetic(100.0), geodetic(100.0), ageMs = 1000))
        assertNull(qualityAlert(10.0, geodetic(100.0), geodetic(900.0), ageMs = 6000))
    }

    @Test fun predictionCannotHideCurrentConflictAndSourceSwitchRemovesVerticalConfidence() {
        CaltopoClient.SetProximityAlertSpacingFeet(100)
        CaltopoClient.SetPredictiveHeadEnabled(true)
        val fixture = TestR2cRuntimeFactory.create("proximity-prediction")
        fixture.setAsDefaultRuntime()
        (fixture.peerCoordinator as FakePeerCoordinator).setLocalOwnership("A", true)
        val a = proximityDrone("A", "A", 39.0, -121.0, 100.0, false)
        val b = proximityDrone("B", "B", 39.0, -121.0, 100.0, false)
        val initial = System.currentTimeMillis() - 1000
        a.proximityPosition = ProximityPosition(39.0, -121.0, initial, geodetic(100.0))
        b.proximityPosition = ProximityPosition(39.0 + 10.0 * 0.3048 / 6371000 * 180 / Math.PI, -121.0, initial, geodetic(100.0))
        ProximityAlertCenter.updateDrones(listOf(a, b))
        val now = System.currentTimeMillis()
        a.proximityPosition = ProximityPosition(39.0, -121.0, now, geodetic(100.0))
        b.proximityPosition = ProximityPosition(39.0 + 90.0 * 0.3048 / 6371000 * 180 / Math.PI, -121.0, now, geodetic(100.0))
        ProximityAlertCenter.updateDrones(listOf(a, b))
        assertNotNull(ProximityAlertCenter.uiState.value)
        assertFalse(ProximityAlertCenter.uiState.value!!.usesProjection)
        assertTrue(ProximityAlertCenter.uiState.value!!.verticalSeparationKnown)
        b.proximityPosition = ProximityPosition(b.proximityPosition!!.latitude, -121.0, now, ProximityTelemetry())
        ProximityAlertCenter.updateDrones(listOf(a, b))
        assertFalse(ProximityAlertCenter.uiState.value!!.verticalSeparationKnown)
        ProximityAlertCenter.suspendCurrentAlert()
        assertTrue(ProximityAlertCenter.canResumeAlert.value)
        ProximityAlertCenter.resumeSuspendedAlert()
        assertNotNull(ProximityAlertCenter.uiState.value)
    }

    @Test fun proximityDoesNotInventVerticalMotion() {
        CaltopoClient.SetProximityAlertSpacingFeet(100)
        CaltopoClient.SetPredictiveHeadEnabled(true)
        val fixture = TestR2cRuntimeFactory.create("proximity-prediction")
        fixture.setAsDefaultRuntime()
        (fixture.peerCoordinator as FakePeerCoordinator).setLocalOwnership("A", true)
        val a = proximityDrone("A", "A", 39.0, -121.0, 100.0, false)
        val b = proximityDrone("B", "B", 39.0, -121.0, 100.0, false)
        val initial = System.currentTimeMillis() - 1000
        a.proximityPosition = ProximityPosition(39.0, -121.0, initial, geodetic(100.0))
        b.proximityPosition = ProximityPosition(39.0 + 100.0 * 0.3048 / 6371000 * 180 / Math.PI, -121.0, initial, geodetic(150.0))
        ProximityAlertCenter.updateDrones(listOf(a, b))
        val now = System.currentTimeMillis()
        a.proximityPosition = ProximityPosition(39.0, -121.0, now, geodetic(100.0))
        b.proximityPosition = ProximityPosition(39.0 + 90.0 * 0.3048 / 6371000 * 180 / Math.PI, -121.0, now, geodetic(150.0))
        ProximityAlertCenter.updateDrones(listOf(a, b))
        assertNull(ProximityAlertCenter.uiState.value)
    }

    @Test fun disabledAlertsCannotBeRecreatedByPendingEvaluationOrResume() {
        qualityAlert(10.0)
        assertNotNull(ProximityAlertCenter.uiState.value)
        ProximityAlertCenter.suspendCurrentAlert()
        assertTrue(ProximityAlertCenter.canResumeAlert.value)
        val executor = ManualExecutor()
        ProximityAlertCenter.setEvaluationExecutorForTests(executor)
        val a = proximityDrone("A", "A", 39.0, -121.0, 100.0, false)
        val b = proximityDrone("B", "B", 39.00001, -121.0, 100.0, false)
        ProximityAlertCenter.updateDrones(listOf(a, b))
        ProximityAlertCenter.disableAlerts()
        executor.runNext()
        ProximityAlertCenter.resumeSuspendedAlert()
        assertNull(ProximityAlertCenter.uiState.value)
        assertNull(ProximityAlertCenter.suspendedAlert.value)
        assertFalse(ProximityAlertCenter.canResumeAlert.value)
        ProximityAlertConsent.requestEnable()
        ProximityAlertCenter.updateDrones(listOf(a, b))
        executor.runNext()
        assertNull(ProximityAlertCenter.uiState.value) // An unanswered notice is still off.
        ProximityAlertConsent.cancel()
        assertFalse(ProximityAlertConsent.state.value.enabled)
        assertEquals(100L, CaltopoClient.GetProximityAlertSpacingFeet())
    }

    @Test fun suspendedStatusCanResumeEvenWhenNoPairRemains() {
        ProximityAlertCenter.resetForTests()
        ProximityAlertCenter.suspendCurrentAlert()
        assertTrue(ProximityAlertCenter.isSuspended.value)
        ProximityAlertCenter.updateDrones(emptyList())
        assertTrue(ProximityAlertCenter.isSuspended.value)
        ProximityAlertCenter.resumeSuspendedAlert()
        assertFalse(ProximityAlertCenter.isSuspended.value)
    }

    @Test fun mappedButUnconfirmedAircraftCannotTriggerLocalProximity() {
        qualityAlert(10.0)
        val a = proximityDrone("A", "A", 39.0, -121.0, 100.0, false)
        val b = proximityDrone("B", "B", 39.00001, -121.0, 100.0, true)
        a.setCurrentFlightConfirmed(false)
        ProximityAlertCenter.resetForTests()
        ProximityAlertCenter.updateDrones(listOf(a, b))
        assertNull(ProximityAlertCenter.uiState.value)
        a.setCurrentFlightConfirmed(true)
        ProximityAlertCenter.updateDrones(listOf(a, b))
        assertNotNull(ProximityAlertCenter.uiState.value)
    }

    @Test fun allAircraftAlertsWithoutClaimsAndScopeChangesImmediately() {
        qualityAlert(10.0)
        val a = proximityDrone("A", "A", 39.0, -121.0, 500.0, true)
        val b = proximityDrone("B", "B", 39.00001, -121.0, 100.0, true)
        a.setCurrentFlightConfirmed(false)
        b.setCurrentFlightConfirmed(false)
        ProximityAlertCenter.resetForTests()
        ProximityAlertCenter.updateDrones(listOf(a, b))
        assertNull(ProximityAlertCenter.uiState.value)
        ProximityAlertCenter.setAlertAllAircraft(true)
        assertNotNull(ProximityAlertCenter.uiState.value)
        ProximityAlertCenter.setAlertAllAircraft(false)
        assertNull(ProximityAlertCenter.uiState.value)
        ProximityAlertCenter.disableAlerts()
        ProximityAlertCenter.setAlertAllAircraft(true)
        assertFalse(ProximityAlertConsent.state.value.enabled)
        assertNull(ProximityAlertCenter.uiState.value)
    }

    @Test fun stalePositionsExpireWithoutPacketsAndFreshSeparationClears() {
        qualityAlert(10.0)
        val now = System.currentTimeMillis()
        for (second in 1..10) ProximityAlertCenter.reevaluateForTests(now + second * 1000)
        assertNull(ProximityAlertCenter.uiState.value)
        assertEquals(2, ProximityAlertCenter.stalePositionCount.value)
        val a = proximityDrone("A", "A", 39.0, -121.0, 100.0, false)
        val b = proximityDrone("B", "B", 39.00001, -121.0, 100.0, false)
        ProximityAlertCenter.updateDrones(listOf(a, b))
        assertNotNull(ProximityAlertCenter.uiState.value)
        for (second in 1..4) {
            val time = now + second * 1000
            a.proximityPosition = ProximityPosition(39.0, -121.0, time, ProximityTelemetry())
            b.proximityPosition = ProximityPosition(39.0 + 650.0 * 0.3048 / 6371000 * 180 / Math.PI, -121.0, time, ProximityTelemetry())
            ProximityAlertCenter.evaluationTimeForTests = time
            ProximityAlertCenter.updateDrones(listOf(a, b))
        }
        assertNull(ProximityAlertCenter.uiState.value)
        assertEquals(0, ProximityAlertCenter.stalePositionCount.value)
    }

    @Test fun configuredSpacingAllows50And75Feet() {
        qualityAlert(10.0)
        for (spacing in listOf(50L, 75L)) {
            CaltopoClient.SetProximityAlertSpacingFeet(spacing)
            assertEquals(spacing, CaltopoClient.GetProximityAlertSpacingFeet())
            ProximityAlertCenter.reevaluateForTests(System.currentTimeMillis())
            assertEquals(spacing.toDouble(), ProximityAlertCenter.uiState.value!!.thresholdFt, 0.001)
        }
        CaltopoClient.SetProximityAlertSpacingFeet(40)
        assertEquals(50L, CaltopoClient.GetProximityAlertSpacingFeet())
    }

    @Test fun legacyPredictionFlagCannotEnableMotionPadding() {
        CaltopoClient.SetPredictiveHeadEnabled(true)
        assertFalse(CaltopoClient.GetPredictiveHeadEnabled())
        for (age in listOf(0L, 1000L, 2400L, 4900L)) {
            assertNull(qualityAlert(220.0, geodetic(100.0, horizontal = 30.0), geodetic(100.0, horizontal = 3.0), ageMs = age))
            assertNotNull(qualityAlert(200.0, geodetic(100.0, horizontal = 30.0), geodetic(100.0, horizontal = 3.0), ageMs = age))
        }
    }

    @Test
    fun completedFlightDoesNotToggleStaleBannerWhenCallersAlternateLists() {
        val fixture = TestR2cRuntimeFactory.create("proximity-completed")
        fixture.setAsDefaultRuntime()
        val completed = CtDroneSpec("MINI")
        val active = proximityDrone("MATRICE", "M4TD", 39.0, -121.0, 50.0, false)
        repeat(3) {
            ProximityAlertCenter.updateDrones(listOf(completed, active))
            assertEquals(0, ProximityAlertCenter.stalePositionCount.value)
            ProximityAlertCenter.updateDrones(listOf(active))
            assertEquals(0, ProximityAlertCenter.stalePositionCount.value)
        }
        active.mostRecentMsecTimestamp = System.currentTimeMillis() - 6000L
        ProximityAlertCenter.updateDrones(listOf(completed, active))
        assertEquals(0, ProximityAlertCenter.stalePositionCount.value)
        val secondActive = proximityDrone("SECOND", "SECOND", 39.001, -121.0, 50.0, false)
        ProximityAlertCenter.updateDrones(listOf(completed, active, secondActive))
        assertEquals(1, ProximityAlertCenter.stalePositionCount.value)
        ProximityAlertCenter.updateDrones(listOf(completed))
        assertEquals(0, ProximityAlertCenter.stalePositionCount.value)
    }

    private fun proximityDrone(
        remoteId: String,
        mappedId: String,
        lat: Double,
        lng: Double,
        altMeters: Double,
        localArchiveOnly: Boolean
    ): CtDroneSpec {
        return CtDroneSpec(remoteId).apply {
            setMappedId(mappedId)
            // Model an active flight without involving waypoint ingestion side effects.
            javaClass.getDeclaredField("trackLabel").apply { isAccessible = true }.set(this, "test-flight")
            setCurrentFlightConfirmed(true)
            setLocalArchiveOnly(localArchiveOnly)
            lastLat = lat
            lastLng = lng
            lastAlt = altMeters
            mostRecentMsecTimestamp = System.currentTimeMillis()
        }
    }

    private class ManualExecutor : Executor {
        private val queued = ArrayDeque<Runnable>()
        val pendingCount: Int
            get() = queued.size

        override fun execute(command: Runnable) {
            queued.add(command)
        }

        fun runNext() {
            queued.removeFirst().run()
        }
    }
}

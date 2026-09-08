package org.ncssar.rid2caltopo.video

import DroneDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.PilotDisplayPreference

class MapPanePilotDisplayTest {
    @Test
    fun cameraFovBoundaryBearings_centerOnCameraAzimuthAndWrapNorth() {
        val east = cameraFovBoundaryBearings(90.0, 40.0)
        assertEquals(70.0, east!!.leftBearingDeg, 0.001)
        assertEquals(110.0, east.rightBearingDeg, 0.001)

        val north = cameraFovBoundaryBearings(5.0, 30.0)
        assertEquals(350.0, north!!.leftBearingDeg, 0.001)
        assertEquals(20.0, north.rightBearingDeg, 0.001)
    }

    @Test
    fun cameraFovBoundaryBearings_rejectMissingOrInvalidTelemetry() {
        assertNull(cameraFovBoundaryBearings(null, 37.7))
        assertNull(cameraFovBoundaryBearings(90.0, null))
        assertNull(cameraFovBoundaryBearings(90.0, 0.0))
        assertNull(cameraFovBoundaryBearings(90.0, 181.0))
    }

    @Test
    fun markerInfoWindowTapAction_togglesVisibleInfoWindowClosed() {
        assertEquals(MarkerInfoWindowTapAction.Show, markerInfoWindowTapAction(isInfoWindowShown = false))
        assertEquals(MarkerInfoWindowTapAction.Close, markerInfoWindowTapAction(isInfoWindowShown = true))
    }

    @Test
    fun droneStatusLabel_includesExplicitUnitsAndHeading() {
        assertEquals(
            "ATO:125' AGL:90' RNG:420' HDG:273°",
            droneStatusLabelText(
                atoFeet = 125.2,
                aglFeet = 90.4,
                aglStale = false,
                rangeFeet = 420.0,
                headingDeg = 273.2
            )
        )
    }

    @Test
    fun streamTelemetryHeader_matchesMapEntriesAndOrder() {
        assertEquals(
            "ATO:125' AGL:90' RNG:420' HDG:273°",
            streamTelemetryHeaderText(
                DroneDisplayState(
                    headingDeg = 273.2,
                    aglFt = 90.4,
                    atoFt = 125.2,
                    rangeFt = 420.0,
                )
            )
        )
    }

    @Test
    fun streamTelemetryHeader_prefersFreshCameraAzimuthWithoutCallingItAircraftHeading() {
        assertEquals(
            "ATO:125' AGL:90' RNG:420' CAM:91°",
            streamTelemetryHeaderText(
                displayState = DroneDisplayState(
                    headingDeg = 273.2,
                    aglFt = 90.4,
                    atoFt = 125.2,
                    rangeFt = 420.0,
                ),
                cameraAzimuthDeg = 90.6,
            )
        )
    }

    @Test
    fun droneStatusLabel_usesMissingTokensAndStaleAglMarker() {
        assertEquals(
            "ATO:--' AGL:75?' RNG:--' HDG:--°",
            droneStatusLabelText(
                atoFeet = null,
                aglFeet = 75.0,
                aglStale = true,
                rangeFeet = null,
                headingDeg = null
            )
        )
    }

    @Test
    fun droneStatusLabel_rejectsOutOfBandAltitudeTokens() {
        assertEquals(
            "ATO:--' AGL:--' RNG:1250' HDG:5°",
            droneStatusLabelText(
                atoFeet = 1_500.0,
                aglFeet = -1_500.0,
                aglStale = false,
                rangeFeet = 1_250.0,
                headingDeg = 365.0
            )
        )
    }

    @Test
    fun droneDetailLines_useRangeAndHeadingInCombinedPopup() {
        assertEquals(
            listOf(
                "Location: 39.12345, -121.12345 (Decimal Degrees)",
                "ATO: 121'",
                "AGL: 76?'",
                "RNG: 432'",
                "HDG: 218°"
            ),
            droneDetailLines(
                locationText = "39.12345, -121.12345",
                coordinateFormatLabel = "Decimal Degrees",
                atoFeet = 120.6,
                aglFeet = 75.5,
                aglStale = true,
                rangeFeet = 431.5,
                headingDeg = 217.7
            )
        )
    }

    @Test
    fun bearingLineToViewportEdge_extendsCardinalHeadingsToEdge() {
        val north = bearingLineToViewportEdge(50.0, 50.0, 0.0, 100, 100)
        assertEquals(50.0, north!!.endX, 0.01)
        assertEquals(0.0, north.endY, 0.01)

        val east = bearingLineToViewportEdge(50.0, 50.0, 90.0, 100, 100)
        assertEquals(100.0, east!!.endX, 0.01)
        assertEquals(50.0, east.endY, 0.01)

        val south = bearingLineToViewportEdge(50.0, 50.0, 180.0, 100, 100)
        assertEquals(50.0, south!!.endX, 0.01)
        assertEquals(100.0, south.endY, 0.01)

        val west = bearingLineToViewportEdge(50.0, 50.0, 270.0, 100, 100)
        assertEquals(0.0, west!!.endX, 0.01)
        assertEquals(50.0, west.endY, 0.01)
    }

    @Test
    fun bearingLineToViewportEdge_extendsDiagonalHeadingToFirstEdge() {
        val northeast = bearingLineToViewportEdge(50.0, 50.0, 45.0, 100, 100)

        assertEquals(100.0, northeast!!.endX, 0.01)
        assertEquals(0.0, northeast.endY, 0.01)
    }

    @Test
    fun bearingLineToViewportEdge_returnsNullForInvalidHeadingOrViewport() {
        assertNull(bearingLineToViewportEdge(50.0, 50.0, Double.NaN, 100, 100))
        assertNull(bearingLineToViewportEdge(50.0, 50.0, 90.0, 0, 100))
        assertNull(bearingLineToViewportEdge(50.0, 50.0, 90.0, 100, 0))
    }

    @Test
    fun travelBearing_requiresClearDisplacementAndSurvivesStationaryKeepalives() {
        val origin = LocalTrackPoint("TEST", 39.0, -121.0, 100.0, 1_000L, 1_000L)
        val jitter = LocalTrackPoint("TEST", 39.00001, -121.0, 100.0, 2_000L, 2_000L)
        assertNull(travelBearingDegrees(listOf(origin, jitter)))

        val north = LocalTrackPoint("TEST", 39.00012, -121.0, 100.0, 5_000L, 5_000L)
        val movingBearing = travelBearingDegrees(listOf(origin, jitter, north))
        assertEquals(0.0, movingBearing!!, 0.1)

        val stationaryKeepalive = north.copy(timestampMsec = 8_000L, receivedAtMsec = 8_000L)
        val stoppedBearing = travelBearingDegrees(listOf(origin, jitter, north, stationaryKeepalive))
        assertEquals(0.0, stoppedBearing!!, 0.1)
    }

    @Test
    fun travelBearing_followsLatestVisibleMovementImmediately() {
        val points = listOf(
            trackPoint(0, 39.00000, -121.00000),
            trackPoint(2, 39.00005, -121.00000),
            trackPoint(4, 39.00010, -121.00000),
            trackPoint(6, 39.00015, -121.00000),
            trackPoint(7, 39.00015, -120.99982)
        )

        val bearing = travelBearingDegrees(points)!!
        assertEquals(90.0, bearing, 0.2)
    }

    @Test
    fun travelBearing_worksWithTwoSparsePointsAndTurnsWithoutHistoryLag() {
        val firstMovement = listOf(
            trackPoint(0, 39.00000, -121.00000),
            trackPoint(30, 39.00000, -120.99997)
        )
        assertEquals(90.0, travelBearingDegrees(firstMovement)!!, 0.2)

        val northTurn = firstMovement + trackPoint(60, 39.00003, -120.99997)
        assertEquals(0.0, travelBearingDegrees(northTurn)!!, 0.2)
    }

    private fun trackPoint(seconds: Long, lat: Double, lng: Double) = LocalTrackPoint(
        mappedId = "TEST",
        lat = lat,
        lng = lng,
        altitudeM = 100.0,
        timestampMsec = seconds * 1_000,
        receivedAtMsec = seconds * 1_000
    )

    @Test
    fun pilotDisplayPreferencesByMappedId_fansOutToCurrentAndAliasedTracks() {
        val alphaPreference = PilotDisplayPreference(
            activeTrackColor = "#43A047",
            archiveTrackColor = "#8E24AA",
            bearingEnabled = true
        )
        val betaPreference = PilotDisplayPreference(
            activeTrackColor = "#E53935",
            archiveTrackColor = "#FB8C00",
            bearingEnabled = false
        )
        val mapped = pilotDisplayPreferencesByMappedId(
            dronePoints = listOf(
                DroneMapPoint(
                    designator = "ALPHA-M3",
                    remoteId = "rid-alpha",
                    lat = 39.0,
                    lng = -121.0,
                    altitudeM = 100.0,
                    timestampMsec = 1L,
                    droneSpec = CtDroneSpec("rid-alpha", "ALPHA-M3", "", "", "alpha")
                ),
                DroneMapPoint(
                    designator = "BETA-M3",
                    remoteId = "rid-beta",
                    lat = 39.1,
                    lng = -121.1,
                    altitudeM = 110.0,
                    timestampMsec = 2L,
                    droneSpec = CtDroneSpec("rid-beta", "BETA-M3", "", "", "beta")
                )
            ),
            mappedIdsByRemoteId = mapOf(
                "rid-alpha" to setOf("ALPHA-OLD", "ALPHA-M3"),
                "rid-beta" to setOf("BETA-OLD")
            )
        ) { pilotKey ->
            when (pilotKey) {
                "ALPHA" -> alphaPreference
                "BETA" -> betaPreference
                else -> PilotDisplayPreference()
            }
        }

        assertEquals(alphaPreference, mapped["ALPHA-M3"])
        assertEquals(alphaPreference, mapped["ALPHA-OLD"])
        assertEquals(betaPreference, mapped["BETA-M3"])
        assertEquals(betaPreference, mapped["BETA-OLD"])
    }
}

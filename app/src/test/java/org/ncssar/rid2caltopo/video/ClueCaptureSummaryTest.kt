import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.video.CoordinateDisplayFormat
import java.io.File

class ClueCaptureSummaryTest {
    @Test
    fun clueCaptureUsesFrameAssociatedSeiWithoutPerReadRidAnchoring() {
        val source = sequenceOf(
            File("src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt"),
            File("app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt"),
        ).first(File::isFile).readText()

        assertTrue(source.contains("renderedFrameSourceTimestampUs(designator)"))
        assertTrue(source.contains("StreamCameraTelemetryRegistry.freshForFrame("))
        assertTrue(!source.contains("StreamCameraTelemetryRegistry.freshPositionAfterRidValidation("))
        assertTrue(!source.contains("StreamCameraTelemetryRegistry.freshAnchored("))
    }

    @Test
    fun videoMslAgl_prefersPlausibleAltitudeDifference() {
        assertEquals(64.595, videoMslAglMeters(574.595, 510.0) ?: 0.0, 0.000001)
        assertNull(videoMslAglMeters(500.0, 600.0))
        assertNull(videoMslAglMeters(null, 500.0))
    }

    @Test
    fun buildClueCaptureSummary_usesPreferredUsngAndRetainsDecimal() {
        val summary = buildClueCaptureSummary(
            PendingClue(
                droneSpec = CtDroneSpec("RID123"),
                designator = "1SAR7",
                droneLat = 39.0,
                droneLng = -75.0,
                droneAlt = 120.0,
                lat = 39.9526,
                lng = -75.1652,
                alt = 102.0,
                headingDeg = 273.2,
                headingSourceLabel = "Camera yaw",
                aglMeters = 25.0,
                atoMeters = 40.0,
                projectionHeightMeters = 25.0,
                projectionHeightSourceLabel = "fresh AGL",
                gimbalAngleDeg = -45.0,
                timestamp = 1_000L,
                bitmap = null,
                preview = null,
                title = "Clue",
                description = "",
                terrainProjectionApplied = true,
                demSource = "usgs-geotiff-local-1m",
                demResolutionMeters = 1.0,
            ),
            CoordinateDisplayFormat.USNG,
        )

        assertTrue(summary.contains("  Position (USNG): 18S VK "))
        assertTrue(summary.contains("  Decimal: 39.952600, -75.165200"))
        assertTrue(summary.contains("  Camera Azimuth: 273.2°"))
        assertTrue(!summary.contains("Heading used for clue"))
        assertTrue(summary.contains("  Gimbal angle at capture: -45.0°"))
        assertTrue(summary.contains("  AGL: 82'"))
        assertTrue(summary.contains("  ATO: 131'"))
        assertTrue(summary.contains("  Projection height: 82' (fresh AGL)"))
        assertTrue(summary.contains("  DEM used: local USGS GeoTIFF (1 m grid)"))
        assertTrue(summary.contains("  Distance to clue:"))
    }

    @Test
    fun clueDemSummary_distinguishesFlatFallbackFromUnknownOnlineResolution() {
        assertEquals(
            "  DEM used: none (flat-ground estimate)",
            formatClueDemSummary(false, null, null, false),
        )
        assertEquals(
            "  DEM used: USGS elevation service (resolution not reported), cached",
            formatClueDemSummary(true, "usgs-epqs", null, true),
        )
    }

    @Test
    fun clueHeading_isNormalizedAndCannotRoundTo360() {
        assertEquals(1.0, normalizeClueHeading(361.0)!!, 0.0)
        assertEquals(359.0, normalizeClueHeading(-1.0)!!, 0.0)
        assertEquals("0.0", formatClueHeading(359.96))
        assertEquals("1.0", formatClueHeading(361.0))
    }

    @Test
    fun clueHeading_prefersMostRecentDerivedDroneHeading() {
        val selection = selectClueHeading(
            djiVideoCourseDeg = null,
            telemetry = null,
            derivedHeadingDeg = 274.0,
            ridTrackDeg = 90.0,
        )

        assertEquals(274.0, selection.headingDeg!!, 0.0)
        assertEquals("Derived drone heading", selection.sourceLabel)
    }

    @Test
    fun clueHeading_prefersFreshDjiVideoCourseOverRidDerivedCourse() {
        val selection = selectClueHeading(
            djiVideoCourseDeg = 111.46,
            telemetry = null,
            derivedHeadingDeg = 274.0,
            ridTrackDeg = 90.0,
        )

        assertEquals(111.46, selection.headingDeg!!, 1e-9)
        assertEquals("DJI video-derived course", selection.sourceLabel)
    }

    @Test
    fun clueHeading_prefersFreshDjiCameraAzimuthOverVideoCourse() {
        val selection = selectClueHeading(
            djiCameraAzimuthDeg = 346.6,
            djiVideoCourseDeg = 153.5,
            telemetry = null,
            derivedHeadingDeg = 274.0,
            ridTrackDeg = 90.0,
        )

        assertEquals(346.6, selection.headingDeg!!, 1e-9)
        assertEquals("DJI camera azimuth", selection.sourceLabel)
    }

    @Test
    fun clueHeading_hasNoHeadingWithoutAnyFreshSource() {
        val selection = selectClueHeading(
            djiVideoCourseDeg = null,
            telemetry = null,
            derivedHeadingDeg = null,
            ridTrackDeg = null,
        )

        assertNull(selection.headingDeg)
        assertNull(selection.sourceLabel)
    }

    @Test
    fun streamTelemetryDisplayStateUsesPairedMappedIdBeforeStreamDesignator() {
        val streamState = DroneDisplayState(
            headingDeg = 90.0,
            aglFt = 100.0,
            atoFt = 120.0
        )
        val pairedState = DroneDisplayState(
            headingDeg = 180.0,
            aglFt = 200.0,
            atoFt = 220.0
        )

        val resolved = streamTelemetryDisplayState(
            streamDesignator = "NCSSAR_MTRC4TD",
            pairedMappedId = "1SAR138DjMtrc4td",
            displayStateByDesignator = mapOf(
                "NCSSAR_MTRC4TD" to streamState,
                "1SAR138DjMtrc4td" to pairedState
            )
        )

        assertSame(pairedState, resolved)
    }

    @Test
    fun streamTelemetrySummaryLabelUsesPairedMappedIdBeforeStreamDesignator() {
        val droneSpec = CtDroneSpec("RID123").apply {
            setMappedId("1SAR138DjMtrc4td")
        }

        val label = streamTelemetrySummaryDesignatorLabel(
            streamDesignator = "NCSSAR_MTRC4TD",
            droneSpec = droneSpec
        )

        assertEquals("1SAR138DjMtrc4td", label)
    }
}

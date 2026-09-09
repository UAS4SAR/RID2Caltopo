import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlin.math.cos
import org.ncssar.rid2caltopo.video.ffmpeg.DjiCameraOrientation
import org.ncssar.rid2caltopo.video.mapcache.DemElevationSample

class ClueProjectionTest {
    @Test
    fun projectionHeight_prefersDjiSeiThenFallsBackToAglAndAto() {
        val agl = selectClueProjectionHeight(30.0, 25.0, 24.0)!!
        assertEquals(24.0, agl.meters, 0.0)
        assertEquals("DJI SEI relative altitude", agl.sourceLabel)

        val ato = selectClueProjectionHeight(null, 25.0, null)!!
        assertEquals(25.0, ato.meters, 0.0)
        assertEquals("ATO flat-ground fallback", ato.sourceLabel)
        val fieldFallback = projectClueLocation(
            droneLat = 39.0,
            droneLng = -121.0,
            droneAlt = 550.0,
            headingDeg = 44.0,
            aglMeters = ato.meters,
            gimbalAngleDeg = -25.6,
        )
        val northMeters = (fieldFallback.lat - 39.0) * 111_320.0
        val eastMeters = (fieldFallback.lng + 121.0) * 111_320.0 * cos(Math.toRadians(39.0))
        val fieldDistance = kotlin.math.hypot(northMeters, eastMeters)
        assertTrue(fieldDistance > 51.0)
        assertTrue(fieldDistance < 53.0)

        val dji = selectClueProjectionHeight(Double.NaN, -1.0, 24.0)!!
        assertEquals(24.0, dji.meters, 0.0)
        assertEquals("DJI SEI relative altitude", dji.sourceLabel)

        assertEquals(null, selectClueProjectionHeight(null, null, null))
    }

    @Test
    fun projectClueLocation_keepsPointUnderDroneAtMinusNinetyDegrees() {
        val projection = projectClueLocation(
            droneLat = 37.0,
            droneLng = -122.0,
            droneAlt = 150.0,
            headingDeg = 180.0,
            aglMeters = 30.0,
            gimbalAngleDeg = -90.0,
        )

        assertEquals(37.0, projection.lat, 1e-9)
        assertEquals(-122.0, projection.lng, 1e-9)
        assertEquals(120.0, projection.alt, 1e-9)
    }

    @Test
    fun projectClueLocation_projectsForwardUsingHeadingAndAgl() {
        val droneLat = 37.0
        val droneLng = -122.0
        val projection = projectClueLocation(
            droneLat = droneLat,
            droneLng = droneLng,
            droneAlt = 150.0,
            headingDeg = 0.0,
            aglMeters = 40.0,
            gimbalAngleDeg = -45.0,
        )

        val latMeters = (projection.lat - droneLat) * 111_320.0
        val lngMeters = (projection.lng - droneLng) * 111_320.0 * cos(Math.toRadians(droneLat))

        assertTrue("expected northward projection", projection.lat > droneLat)
        assertEquals(40.0, latMeters, 2.0)
        assertEquals(0.0, lngMeters, 1.0)
        assertEquals(110.0, projection.alt, 1e-9)
    }

    @Test
    fun m4tdControllerHeading_projectsAugust24ClueIntoNorthwestQuadrant() {
        val droneLat = 39.154044
        val droneLng = -121.131754
        val heading = DjiCameraOrientation.controllerAzimuthDeg(16.733)
        val projection = projectClueLocation(
            droneLat = droneLat,
            droneLng = droneLng,
            droneAlt = 531.9,
            headingDeg = heading,
            aglMeters = 22.0,
            gimbalAngleDeg = -17.5,
        )

        assertTrue("expected northward projection", projection.lat > droneLat)
        assertTrue("expected westward projection", projection.lng < droneLng)
    }

    @Test
    fun projectClueLocation_fallsBackToDroneLocationWithoutProjectionInputs() {
        val projection = projectClueLocation(
            droneLat = 37.0,
            droneLng = -122.0,
            droneAlt = 150.0,
            headingDeg = null,
            aglMeters = 40.0,
            gimbalAngleDeg = -30.0,
        )

        assertEquals(37.0, projection.lat, 1e-9)
        assertEquals(-122.0, projection.lng, 1e-9)
        assertEquals(110.0, projection.alt, 1e-9)
    }

    @Test
    fun projectClueLocation_keepsPointAtDroneWhenCameraLooksAboveHorizon() {
        val projection = projectClueLocation(
            droneLat = 37.0,
            droneLng = -122.0,
            droneAlt = 150.0,
            headingDeg = 90.0,
            aglMeters = 40.0,
            gimbalAngleDeg = 35.0,
        )

        assertEquals(37.0, projection.lat, 1e-9)
        assertEquals(-122.0, projection.lng, 1e-9)
        assertEquals(110.0, projection.alt, 1e-9)
    }

    @Test
    fun inferDemScaleToMeters_prefersFootScaleWhenRawDemMatchesFeet() {
        val scale = inferDemScaleToMeters(
            droneAltMeters = 548.0,
            knownGroundMeters = 529.2,
            droneDemRaw = 1563.3,
        )

        assertEquals(0.3048, scale, 1e-9)
    }

    @Test
    fun projectClueLocationWithDemSamples_usesRelativeTerrainWhenDemLooksFootBased() = runBlocking {
        val projection = projectClueLocationWithDemSamples(
            droneLat = 39.153294,
            droneLng = -121.132378,
            droneAlt = 548.0,
            headingDeg = 244.0,
            aglMeters = 18.8,
            gimbalAngleDeg = -31.0,
            sampleElevationMeters = { lat, lng ->
                if (kotlin.math.abs(lat - 39.153294) < 1e-6 && kotlin.math.abs(lng + 121.132378) < 1e-6) {
                    DemElevationSample(1563.3, false, "usgs-geotiff-local-1m", 1.0)
                } else {
                    DemElevationSample(1545.0, false, "usgs-geotiff-local-1m", 1.0)
                }
            },
        )

        assertTrue("expected DEM-refined projection to stay ahead of the drone", projection.lng < -121.1325)
        assertTrue("expected downhill terrain to lower projected ground elevation", projection.alt < 529.2)
    }

    @Test
    fun projectClueLocationWithDemSamples_followsShallowSightlineAcrossDescendingTerrain() = runBlocking {
        val droneLat = 39.0
        val projection = projectClueLocationWithDemSamples(
            droneLat = droneLat,
            droneLng = -105.0,
            droneAlt = 1_030.0,
            headingDeg = 0.0,
            aglMeters = 30.0,
            gimbalAngleDeg = -8.0,
            sampleElevationMeters = { lat, _ ->
                val northMeters = ((lat - droneLat) * 111_195.0).coerceAtLeast(0.0)
                val groundMeters = if (northMeters <= 1_500.0) {
                    1_000.0 - (northMeters * 0.2)
                } else {
                    700.0 + ((northMeters - 1_500.0) * 0.1)
                }
                DemElevationSample(groundMeters, false, "usgs-geotiff-local-1m", 1.0)
            },
        )

        val northMeters = (projection.lat - droneLat) * 111_195.0
        assertTrue("expected terrain intersection beyond the old shallow-angle search", northMeters > 1_900.0)
        assertTrue("expected terrain intersection near the far hillside", northMeters < 2_100.0)
        assertTrue(projection.terrainProjectionApplied)
        assertEquals(1.0, projection.demResolutionMeters ?: 0.0, 0.0)
    }

    @Test
    fun projectClueLocationWithDemSamples_anchorsSeiRelativeUpAtHomeTerrain() = runBlocking {
        val homeLat = 39.0
        val droneLat = homeLat + (20.0 / 111_195.0)
        val projection = projectClueLocationWithDemSamples(
            droneLat = droneLat,
            droneLng = -105.0,
            droneAlt = 9_999.0,
            headingDeg = 0.0,
            aglMeters = 5.0,
            gimbalAngleDeg = -45.0,
            terrainReferenceLatitude = homeLat,
            terrainReferenceLongitude = -105.0,
            seiRelativeUpMeters = 50.0,
            sampleElevationMeters = { lat, _ ->
                val northMeters = (lat - homeLat) * 111_195.0
                DemElevationSample(
                    elevationMeters = 100.0 + northMeters * 0.5,
                    stale = false,
                    source = "usgs-geotiff-local-1m",
                    horizontalResolutionMeters = 1.0,
                )
            },
        )

        val northFromDrone = (projection.lat - droneLat) * 111_195.0
        assertTrue("expected SEI/DEM intersection near 27 m", northFromDrone in 25.0..28.0)
        assertTrue(projection.terrainProjectionApplied)
        assertTrue("RID-like altitude must not leak into result", projection.alt < 200.0)
    }
}

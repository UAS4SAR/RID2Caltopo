import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.video.mapcache.DemElevationSample

class ClueTerrainPreparationTest {
    private fun captured() = PendingClue(
        droneSpec = CtDroneSpec("RID-CAPTURE"), designator = "camera",
        droneLat = 39.001, droneLng = -121.0, droneAlt = 580.7,
        lat = 39.001, lng = -120.9995, alt = 560.8,
        headingDeg = 90.0, headingSourceLabel = "DJI camera azimuth",
        aglMeters = 19.9, atoMeters = 19.9, projectionHeightMeters = 19.9,
        terrainReferenceLatitude = 39.0, terrainReferenceLongitude = -121.0,
        seiRelativeUpMeters = 19.9, gimbalAngleDeg = -26.2,
        timestamp = 1_000L, bitmap = null, preview = null, title = "cows", description = "capture",
    )

    @Test fun flatSurfaceNoteIsOnlyForAnUnrefinedObliquePublish() {
        val note = FLAT_SURFACE_LOCATION_NOTE
        val oblique = appendFlatSurfaceLocationNote("Photo", terrainProjectionApplied = false, gimbalAngleDeg = -26.2)
        assertEquals("Photo\n\n$note", oblique)
        assertEquals(note, appendFlatSurfaceLocationNote("  ", terrainProjectionApplied = false, gimbalAngleDeg = -45.0))
        assertEquals("Photo", appendFlatSurfaceLocationNote("Photo", terrainProjectionApplied = true, gimbalAngleDeg = -26.2))
        assertEquals("Photo", appendFlatSurfaceLocationNote("Photo", terrainProjectionApplied = false, gimbalAngleDeg = -90.0))
        assertEquals("Photo", appendFlatSurfaceLocationNote("Photo", terrainProjectionApplied = false, gimbalAngleDeg = -89.9))
        assertEquals(oblique, appendFlatSurfaceLocationNote(oblique, terrainProjectionApplied = false, gimbalAngleDeg = -26.2))
    }

    @Test fun saveRefinesWhenTerrainArrivesWithoutBlockingLiveInputs() = runBlocking {
        val clue = captured()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val save = async {
            val prepared = prepareClueTerrainForSave(clue) { lat, _ ->
                entered.complete(Unit)
                release.await()
                DemElevationSample(if (lat == 39.0) 100.0 else 90.0, false, "usgs-geotiff-local-1m", 1.0)
            }
            assertTrue(prepared.terrainProjectionApplied)
            assertEquals(1_000L, prepared.timestamp)
            assertEquals(clue.droneLat, prepared.droneLat, 0.0)
            assertEquals(clue.droneLng, prepared.droneLng, 0.0)
            assertEquals("cows", prepared.title)
            // Falling terrain puts the intersection beyond the provisional 40 m range.
            assertTrue((prepared.lng - clue.droneLng) * 86_000 > 59.0)
            assertEquals(90.0, prepared.alt, 0.01)
            prepared
        }
        withTimeout(2_000) { entered.await() }
        assertFalse(save.isCompleted)
        // Incoming live telemetry cannot alter a photograph already accepted for save.
        clue.droneSpec.lastLat = 40.0
        release.complete(Unit)
        assertTrue(save.await().terrainProjectionApplied)
    }

    @Test fun missingTerrainPublishesTheFlatGroundFallback() = runBlocking {
        val clue = captured()
        val prepared = prepareClueTerrainForSave(clue) { _, _ -> null }
        assertFalse(prepared.terrainProjectionApplied)
        assertNull(prepared.demSource)
        assertEquals(clue.timestamp, prepared.timestamp)
        assertEquals(clue.title, prepared.title)
    }

    @Test fun slowProviderTimesOutToFlatGroundWithoutUsingLaterTelemetry() = runBlocking {
        val clue = captured()
        val prepared = prepareClueTerrainForSave(clue, timeoutMillis = 30) { _, _ -> awaitCancellation() }
        assertFalse(prepared.terrainProjectionApplied)
        assertEquals(clue.lat, prepared.lat, 0.0)
        assertEquals(clue.lng, prepared.lng, 0.0)
    }

    @Test fun completedFreshProjectionIsNotRestartedAtSave() = runBlocking {
        val clue = captured().copy(terrainProjectionApplied = true, demSource = "local", demSampleStale = false)
        val prepared = prepareClueTerrainForSave(clue) { _, _ -> error("Completed terrain was needlessly resampled") }
        assertSame(clue, prepared)
    }

    @Test fun staleTerrainIsRetriedThenAllowedAsFlatFallback() = runBlocking {
        val clue = captured().copy(terrainProjectionApplied = true, demSampleStale = true)
        val prepared = prepareClueTerrainForSave(clue) { _, _ -> null }
        assertFalse(prepared.terrainProjectionApplied)
    }
}

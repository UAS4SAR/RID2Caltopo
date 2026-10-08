package org.ncssar.rid2caltopo.video

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.WaypointTrack

class MapPaneLocalTrackSeedTest {
    @Test
    fun october7RecordedFlightBackfillKeepsCappedVideoHistoryChronological() {
        // Coordinates only from the supplied 11:44:32 flight. Video samples are
        // interpolated for buffer-pressure coverage, not recovered SEI telemetry.
        val json = checkNotNull(javaClass.getResource("/tracks/oct07-114432-coordinates.json")).readText()
        val snapshot = JsonParser.parseString(json).asJsonArray.map { element ->
            val c = element.asJsonArray
            WaypointTrack.TrackPoint(c[1].asDouble, c[0].asDouble, c[2].asDouble, c[3].asLong)
        }
        val video = snapshot.zipWithNext().flatMap { (a, b) ->
            (0..6).map { step ->
                val f = step / 7.0
                val time = a.timestampMsec + ((b.timestampMsec - a.timestampMsec) * f).toLong()
                LocalTrackPoint("A", a.lat + (b.lat - a.lat) * f,
                    a.lng + (b.lng - a.lng) * f, a.ele + (b.ele - a.ele) * f, time, time)
            }
        }
        val recent = mutableListOf<LocalTrackPoint>()
        val flight = video.takeLast(10_000).toMutableList()
        val last = snapshot.last()
        flight.add(LocalTrackPoint("A", last.lat, last.lng, last.ele, last.timestampMsec, last.timestampMsec))
        flight.removeAt(0)
        repeat(3) {
            seedLocalTrackPointsFromSnapshot("A", snapshot, last.timestampMsec + 1000, recent, flight)
            assertEquals(10_000, flight.size)
            assertEquals(last.timestampMsec, flight.last().timestampMsec)
            assertTrue("Backfill must not return from the live head to older flight history",
                flight.zipWithNext().all { (a, b) -> a.receivedAtMsec <= b.receivedAtMsec })
        }
    }

    @Test
    fun snapshotBackfillMergesBehindVideoHeadUsingReceiptTime() {
        val recent = mutableListOf<LocalTrackPoint>()
        val flight = mutableListOf(
            LocalTrackPoint("A", 39.003, -121.0, 100.0, 3000L, 3000L)
        )
        // The drone clock is ahead of the tablet. Ordering by drone time would
        // still join the current video head back to the start of the flight.
        val snapshot = listOf(
            WaypointTrack.TrackPoint(39.001, -121.0, 100.0, 61000L, 1000L),
            WaypointTrack.TrackPoint(39.002, -121.0, 100.0, 62000L, 2000L)
        )
        seedLocalTrackPointsFromSnapshot("A", snapshot, 4000L, recent, flight)
        assertEquals(listOf(1000L, 2000L, 3000L), flight.map { it.receivedAtMsec })
        flight.add(LocalTrackPoint("A", 39.005, -121.0, 100.0, 5000L, 5000L))
        val extended = snapshot + WaypointTrack.TrackPoint(39.004, -121.0, 100.0, 64000L, 4000L)
        seedLocalTrackPointsFromSnapshot("A", extended, 6000L, recent, flight)
        assertEquals(listOf(1000L, 2000L, 3000L, 4000L, 5000L), flight.map { it.receivedAtMsec })
        assertTrue(!seedLocalTrackPointsFromSnapshot("A", extended, 7000L, recent, flight))
    }

    @Test
    fun seedLocalTrackPointsFromSnapshot_backfillsFullFlightAndLatestRecentPoint() {
        val recent = mutableListOf<LocalTrackPoint>()
        val flight = mutableListOf<LocalTrackPoint>()
        val snapshot = listOf(
            WaypointTrack.TrackPoint(39.153000, -121.132000, 100.0, 1_000L),
            WaypointTrack.TrackPoint(39.154000, -121.133000, 101.0, 2_000L),
            WaypointTrack.TrackPoint(39.155000, -121.134000, 102.0, 3_000L),
        )

        val changed = seedLocalTrackPointsFromSnapshot(
            mappedId = "1SAR7",
            snapshot = snapshot,
            receivedAtMsec = 10_000L,
            recentPoints = recent,
            flightPoints = flight
        )

        assertTrue(changed)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), flight.map { it.timestampMsec })
        assertEquals(listOf(3_000L), recent.map { it.timestampMsec })
        assertEquals("1SAR7", flight.first().mappedId)
    }

    @Test
    fun seedLocalTrackPointsFromSnapshot_doesNotDuplicateExistingFlightPoints() {
        val recent = mutableListOf<LocalTrackPoint>()
        val flight = mutableListOf<LocalTrackPoint>()
        val snapshot = listOf(
            WaypointTrack.TrackPoint(39.153000, -121.132000, 100.0, 1_000L),
            WaypointTrack.TrackPoint(39.154000, -121.133000, 101.0, 2_000L),
        )

        seedLocalTrackPointsFromSnapshot("1SAR7", snapshot, 10_000L, recent, flight)
        val changed = seedLocalTrackPointsFromSnapshot("1SAR7", snapshot, 11_000L, recent, flight)

        assertTrue(!changed)
        assertEquals(listOf(1_000L, 2_000L), flight.map { it.timestampMsec })
        assertEquals(listOf(2_000L), recent.map { it.timestampMsec })
    }

    @Test
    fun seedLocalTrackPointsFromSnapshot_ignoresInvalidCoordinates() {
        val recent = mutableListOf<LocalTrackPoint>()
        val flight = mutableListOf<LocalTrackPoint>()
        val snapshot = listOf(
            WaypointTrack.TrackPoint(0.0, 0.0, 100.0, 1_000L),
            WaypointTrack.TrackPoint(Double.NaN, -121.133000, 101.0, 2_000L),
        )

        val changed = seedLocalTrackPointsFromSnapshot("1SAR7", snapshot, 10_000L, recent, flight)

        assertTrue(!changed)
        assertEquals(emptyList<LocalTrackPoint>(), flight)
        assertEquals(emptyList<LocalTrackPoint>(), recent)
    }

    @Test
    fun shouldSeedLocalTrackSnapshotForDesignator_whenSnapshotHasNewerPoint() {
        val lastSeeded = mutableMapOf<String, Long>()
        val firstSnapshot = listOf(
            WaypointTrack.TrackPoint(39.153000, -121.132000, 100.0, 1_000L),
            WaypointTrack.TrackPoint(39.154000, -121.133000, 101.0, 2_000L),
        )
        val sameSnapshot = listOf(
            WaypointTrack.TrackPoint(39.154000, -121.133000, 101.0, 2_000L),
        )
        val newerSnapshot = listOf(
            WaypointTrack.TrackPoint(39.154000, -121.133000, 101.0, 2_000L),
            WaypointTrack.TrackPoint(39.155000, -121.134000, 102.0, 3_000L),
        )

        assertTrue(shouldSeedLocalTrackSnapshotForDesignator("1SAR7", firstSnapshot, lastSeeded))
        assertEquals(2_000L, lastSeeded["1SAR7"])
        assertTrue(!shouldSeedLocalTrackSnapshotForDesignator("1SAR7", sameSnapshot, lastSeeded))
        assertTrue(shouldSeedLocalTrackSnapshotForDesignator("1SAR7", newerSnapshot, lastSeeded))
        assertEquals(3_000L, lastSeeded["1SAR7"])
    }

    @Test
    fun shouldSeedLocalTrackSnapshotForDesignator_ignoresInvalidNewestPoint() {
        val lastSeeded = mutableMapOf("1SAR7" to 2_000L)
        val invalidNewerSnapshot = listOf(
            WaypointTrack.TrackPoint(39.154000, -121.133000, 101.0, 2_000L),
            WaypointTrack.TrackPoint(0.0, 0.0, 102.0, 3_000L),
        )

        assertTrue(!shouldSeedLocalTrackSnapshotForDesignator("1SAR7", invalidNewerSnapshot, lastSeeded))
        assertEquals(2_000L, lastSeeded["1SAR7"])
    }

    @Test
    fun seed_preservesDistinctPointsAtSameTimestampAndTolerance() {
        val recent = mutableListOf<LocalTrackPoint>()
        val flight = mutableListOf<LocalTrackPoint>()
        val snapshot = listOf(
            WaypointTrack.TrackPoint(39.0, -121.0, 100.0, 1000L),
            WaypointTrack.TrackPoint(39.0000005, -121.0, 100.4, 1000L),
            WaypointTrack.TrackPoint(39.001, -121.0, 100.0, 1000L),
            WaypointTrack.TrackPoint(39.0, -121.0, 101.0, 1000L),
            WaypointTrack.TrackPoint(39.0, -121.0, 100.0, 999L)
        )
        seedLocalTrackPointsFromSnapshot("A", snapshot, 2000L, recent, flight)
        assertEquals(4, flight.size)
        assertTrue(!seedLocalTrackPointsFromSnapshot("A", snapshot, 3000L, recent, flight))
    }

    @Test
    fun seed_longFlightRetainsLimitAndNewestPoint() {
        val recent = mutableListOf<LocalTrackPoint>()
        val flight = mutableListOf<LocalTrackPoint>()
        val snapshot = (0..10_000).map {
            WaypointTrack.TrackPoint(39.0, -121.0, 100.0, it.toLong())
        }
        seedLocalTrackPointsFromSnapshot("A", snapshot.take(10_000), 20000L, recent, flight)
        seedLocalTrackPointsFromSnapshot("A", snapshot, 20001L, recent, flight)
        assertEquals(10_000, flight.size)
        assertEquals((1L..10_000L).toList(), flight.map { it.timestampMsec })
    }
}

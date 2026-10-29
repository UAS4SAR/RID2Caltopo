package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.ncssar.rid2caltopo.data.WaypointTrack

class LocalTrackPreparationTest {
    @Test fun largeHistoryIsReadAndMergedOnAWorkerWithoutLosingTheLiveHead() = runBlocking {
        val caller = Thread.currentThread()
        val recent = listOf(LocalTrackPoint("A", 39.9, -121.0, 100.0, 30_001, 30_001))
        val request = LocalTrackSeedRequest("A", "A_120000Oct29", recent, recent, null, 31_000)
        val prepared = prepareLocalTrackSeed(request) { label ->
            assertNotSame(caller, Thread.currentThread())
            assertEquals("A_120000Oct29", label)
            List(30_000) { WaypointTrack.TrackPoint(39.0 + it / 1e6, -121.0, 100.0, it.toLong(), it.toLong()) }
        }!!
        assertEquals(10_000, prepared.flight.size)
        assertEquals(30_001L, prepared.flight.last().receivedAtMsec)
        assertTrue(prepared.flight.zipWithNext().all { (a, b) -> a.receivedAtMsec <= b.receivedAtMsec })
        assertSame(recent.last(), prepared.recent.last())
        assertEquals(1, recent.size) // The worker never mutated displayed state.
    }

    @Test fun delayedSeedCannotReplaceNewerLivePointsOrANewFlight() {
        val points = listOf(LocalTrackPoint("A", 39.0, -121.0, 100.0, 1, 1))
        val request = LocalTrackSeedRequest("A", "first", points, points, null, 2)
        assertTrue(localTrackSeedStillCurrent(request, "first", points, points))
        assertFalse(localTrackSeedStillCurrent(request, "next", points, points))
        assertFalse(localTrackSeedStillCurrent(request, "first", points, points + points.last()))
        assertFalse(localTrackSeedStillCurrent(request, null, emptyList(), emptyList()))
    }

    @Test fun geometryPreparationRunsOffCallerAndNeverMutatesAnAttachedRender() = runBlocking {
        val caller = Thread.currentThread()
        val points = List(10_000) { LocalTrackPoint("A", 39.0, -121.0, 100.0, it.toLong(), it.toLong()) }
        var created = 0
        val create: (List<LocalTrackPoint>) -> Any = {
            assertNotSame(caller, Thread.currentThread())
            created++
            Any()
        }
        val first = prepareTrackRenders(mapOf("A" to points), emptyMap(), create)
        val repeated = prepareTrackRenders(mapOf("A" to points), first, create)
        assertSame(first["A"], repeated["A"])
        assertEquals(1, created)
        val changed = prepareTrackRenders(mapOf("A" to (points + points.last())), first, create)
        assertNotSame(first["A"]!!.render, changed["A"]!!.render)
        assertEquals(10_000, first["A"]!!.points.size)
        assertTrue(prepareTrackRenders(emptyMap(), changed, create).isEmpty())
    }
}

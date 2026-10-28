package org.ncssar.rid2caltopo.video

import org.ncssar.rid2caltopo.data.ManagedVideoStreamAdvertisement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class ManagedVideoStreamPresenceTest {
    @After
    fun resetPresence() {
        ManagedVideoStreamPresence.resetForTests()
    }

    @Test fun slowMetadataProviderDoesNotBlockLiveStatusLookups() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val snapshot = workers.submit {
                ManagedVideoStreamPresence.snapshot(
                    streams = mapOf("camera" to StreamInfo("camera", sourcePath = "camera", state = StreamState.LIVE)),
                    sourceInfoProvider = {
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        null
                    },
                )
            }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            workers.submit<Boolean> { ManagedVideoStreamPresence.hasLiveDesignator("camera") }
                .get(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            release.countDown()
            snapshot.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `live stream is advertised before any telemetry association exists`() {
        val advertisements = ManagedVideoStreamPresence.snapshot(
            streams = mapOf(
                "camera/path" to StreamInfo(
                    designator = "Rescue 1",
                    sourcePath = "camera/path",
                    state = StreamState.LIVE,
                )
            ),
        )

        assertEquals(listOf("Rescue 1"), advertisements.map { it.droneDesignator })
        assertTrue(ManagedVideoStreamPresence.hasLiveDesignator("Rescue 1"))
        assertEquals(
            advertisements.single().sessionId,
            ManagedVideoStreamPresence.matchingLiveSessionId("rescue 1"),
        )
    }

    @Test
    fun `live stream is advertised before its offscreen decoder produces a frame`() {
        val advertisements = ManagedVideoStreamPresence.snapshot(
            streams = mapOf(
                "camera/path" to StreamInfo(
                    designator = "Rescue 1",
                    sourcePath = "camera/path",
                    state = StreamState.LIVE,
                )
            ),
            hasRecentFrame = { false },
        )

        assertEquals(listOf("Rescue 1"), advertisements.map { it.droneDesignator })
    }

    @Test
    fun `paired drone designator is advertised without changing stream session identity`() {
        val first = ManagedVideoStreamPresence.snapshot(
            streams = mapOf(
                "camera/path" to StreamInfo(
                    designator = "1sar7mn4pr",
                    sourcePath = "camera/path",
                    state = StreamState.LIVE,
                )
            ),
        ).single()
        val paired = ManagedVideoStreamPresence.snapshot(
            streams = mapOf(
                "camera/path" to StreamInfo(
                    designator = "1sar7mn4pr",
                    sourcePath = "camera/path",
                    state = StreamState.LIVE,
                )
            ),
            droneDesignatorProvider = { "1sar7DjMn4Pr" },
        ).single()

        assertEquals("1sar7DjMn4Pr", paired.droneDesignator)
        assertEquals(first.sessionId, paired.sessionId)
        assertEquals(
            "1sar7mn4pr",
            ManagedVideoStreamPresence.localLiveDesignator(paired.sessionId),
        )
    }

    @Test
    fun `ended live session cannot resolve a stale local source`() {
        val live = ManagedVideoStreamPresence.snapshot(
            streams = mapOf(
                "camera/path" to StreamInfo(
                    designator = "1sar7mn4pr",
                    sourcePath = "camera/path",
                    state = StreamState.LIVE,
                )
            ),
            droneDesignatorProvider = { "1sar7DjMn4Pr" },
        ).single()

        ManagedVideoStreamPresence.snapshot(streams = emptyMap())

        assertEquals(null, ManagedVideoStreamPresence.localLiveDesignator(live.sessionId))
    }

    @Test
    fun `implausible controller time base is advertised as nominal thirty fps`() {
        assertEquals(30.0, nominalManagedVideoSourceFps(240.0), 0.0)
        assertEquals(30.0, nominalManagedVideoSourceFps(24.0), 0.0)
    }

    @Test
    fun `slow and unavailable source cadence remains honest`() {
        assertEquals(12.5, nominalManagedVideoSourceFps(12.5), 0.0)
        assertEquals(0.0, nominalManagedVideoSourceFps(0.0), 0.0)
        assertEquals(0.0, nominalManagedVideoSourceFps(Double.NaN), 0.0)
    }

    @Test
    fun `live thumbnail outranks older recordings after eligibility filtering`() {
        val recordings = (0 until 12).map { index ->
            thumbnailAdvertisement("recording-$index", "recording")
        }
        val live = thumbnailAdvertisement("live-session", "live")

        val candidates = ManagedVideoStreamPresence.thumbnailCaptureCandidates(
            advertisements = recordings + live,
            forceDesignators = emptySet(),
            hasThumbnail = { it in recordings.take(8).map { recording -> recording.sessionId } },
        )

        assertEquals(5, candidates.size)
        assertEquals("live-session", candidates.first().sessionId)
        assertTrue(candidates.any { it.sessionId == "recording-8" })
    }

    private fun thumbnailAdvertisement(
        sessionId: String,
        mediaKind: String,
    ) = ManagedVideoStreamAdvertisement(
        sessionId,
        "1sar7DjMn4Pr",
        1280,
        720,
        30.0,
        0,
        "H264",
        mediaKind,
        null,
        0,
        "",
        null,
    )
}

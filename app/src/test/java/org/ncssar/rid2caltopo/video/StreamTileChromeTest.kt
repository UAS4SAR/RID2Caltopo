package org.ncssar.rid2caltopo.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact PiP is the video and one "Observation only" badge; full-size tiles are unchanged
 * (Ken, Nov 20: "too much text in the PiP"). Same cases as the iPad's StreamTileChromeTests.
 */
class StreamTileChromeTest {
    @Test
    fun compactPipShowsOnlyTheBadgeAndRecordingMarker() {
        // Ken, Nov 25: "show the recording marker on the PiP too".
        assertEquals(listOf(StreamTileChrome.SAFETY_BADGE, StreamTileChrome.RECORDING_MARKER),
            StreamTileChrome.entries.filter { streamTileShows(it, compactPip = true) }.sortedBy { it.ordinal })
    }

    @Test
    fun fullSizeTileUnchanged() {
        assertEquals(listOf(StreamTileChrome.SAFETY_BADGE), StreamTileChrome.entries.filter { !streamTileShows(it, compactPip = false) })
        assertEquals(11, StreamTileChrome.entries.size)
    }

    @Test
    fun everyTileSizeShowsTheRecordingMarker() {
        assertTrue(streamTileShows(StreamTileChrome.RECORDING_MARKER, compactPip = true))
        assertTrue(streamTileShows(StreamTileChrome.RECORDING_MARKER, compactPip = false))
        val tile = File("src/main/java/org/ncssar/rid2caltopo/video/StreamTile.kt").readText()
        assertTrue(tile.contains("StreamRecordingMarker(streamDesignator"))
        // Ken, Nov 25: the status-row chip was redundant; the tile marker is adequate.
        val streams = File("src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt").readText()
        assertFalse(streams.contains("RecordingStatusChip()"))
    }

    @Test
    fun badgeText() {
        assertEquals("Observation only", VIDEO_SAFETY_BADGE_TEXT)
    }

    @Test
    fun compactEmptyPipShowsShortWaitingLabel() {
        assertEquals("Waiting for stream", STREAM_PIP_WAITING_TEXT)
        val streams = File("src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt").readText()
        assertTrue(streams.contains("STREAM_PIP_WAITING_TEXT"))
        // Compact empty PiP must not dump controller setup / RTMP URLs into the inset.
        assertFalse(streams.contains("EmptyStreamsView(") && streams.split("if (!streamTileShows(StreamTileChrome.WAITING_PLACEHOLDER, compactPip))")[1].split("else Column")[0].contains("EmptyStreamsView("))
    }

    @Test
    fun streamDoubleTapOpensDeskPodOnlyWhenConnectedAndNotOnThePip() {
        assertTrue(streamDoubleTapOpensDeskPod(compactPip = false, deskPodConnected = true))
        assertFalse(streamDoubleTapOpensDeskPod(compactPip = false, deskPodConnected = false))
        assertFalse(streamDoubleTapOpensDeskPod(compactPip = true, deskPodConnected = true))
        assertFalse(streamDoubleTapOpensDeskPod(compactPip = true, deskPodConnected = false))
        val tile = File("src/main/java/org/ncssar/rid2caltopo/video/StreamTile.kt").readText()
        assertFalse(tile.contains("requestClueCapture(\"double-tap\")"))
        assertTrue(tile.contains("requestClueCapture(\"camera-button\")"))
        assertTrue(tile.contains("streamDoubleTapOpensDeskPod("))
        assertTrue(tile.contains("openDeskPodScreenFromMap()"))
    }
}

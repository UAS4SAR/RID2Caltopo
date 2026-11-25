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
    fun compactPipShowsOnlyTheBadge() {
        assertEquals(listOf(StreamTileChrome.SAFETY_BADGE), StreamTileChrome.entries.filter { streamTileShows(it, compactPip = true) })
    }

    @Test
    fun fullSizeTileUnchanged() {
        assertEquals(listOf(StreamTileChrome.SAFETY_BADGE), StreamTileChrome.entries.filter { !streamTileShows(it, compactPip = false) })
        assertEquals(10, StreamTileChrome.entries.size)
    }

    @Test
    fun badgeText() {
        assertEquals("Observation only", VIDEO_SAFETY_BADGE_TEXT)
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

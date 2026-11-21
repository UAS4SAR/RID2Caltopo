package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
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
}

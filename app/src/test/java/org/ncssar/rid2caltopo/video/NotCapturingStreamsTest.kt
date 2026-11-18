package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotCapturingStreamsTest {
    private fun live(path: String, conn: String? = "c1", state: StreamState = StreamState.LIVE, local: Boolean = false) =
        StreamInfo(designator = path.substringAfterLast('/'), sourcePath = path, state = state, publisherConnId = conn, isLocalPlayback = local)

    @Test fun showsLiveStreamsOnlyWhileCaptureIsOff() {
        val streams = listOf(live("rc/1sar7DjMtrc4td"), live("rc/alpha", state = StreamState.CONNECTING), live("file", local = true))
        assertTrue(NotCapturingStreams.pending(streams, captureEnabled = true, dismissed = emptySet()).isEmpty())
        val pending = NotCapturingStreams.pending(streams, captureEnabled = false, dismissed = emptySet())
        assertEquals(listOf("1sar7DjMtrc4td"), pending.map { it.designator })
        assertEquals("Not capturing stream: 1sar7DjMtrc4td", NotCapturingStreams.title(pending.single()))
    }

    @Test fun confirmDismissesThatPublishOnlyAndANewPublishAsksAgain() {
        val first = live("rc/drone", conn = "c1")
        val key = NotCapturingStreams.key(first)
        assertTrue(NotCapturingStreams.pending(listOf(first), false, setOf(key)).isEmpty())
        val republished = live("rc/drone", conn = "c2")
        assertEquals(1, NotCapturingStreams.pending(listOf(republished), false, setOf(key)).size)
        val other = live("rc/other")
        assertEquals(listOf("other"), NotCapturingStreams.pending(listOf(first, other), false, setOf(key)).map { it.designator })
    }

    @Test fun buttonLabels() {
        assertEquals("Confirm", NotCapturingStreams.CONFIRM)
        assertEquals("Turn on Stream capture", NotCapturingStreams.TURN_ON)
    }
}

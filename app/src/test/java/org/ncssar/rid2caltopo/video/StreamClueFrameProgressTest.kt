package org.ncssar.rid2caltopo.video

import org.junit.Assert.*
import org.junit.Test

class StreamClueFrameProgressTest {
    @Test fun ordinaryPlaybackKeepsExactCountWithoutRequestingUiWork() {
        val progress = StreamClueFrameProgress()
        for (frame in 1..9000) assertFalse(progress.onFrame(frame, 0, 0))
        assertEquals(9000, progress.renderedFrameCount)
    }

    @Test fun pendingClueRetriesUntilHandledAndCanRequestAgain() {
        val progress = StreamClueFrameProgress()
        assertTrue(progress.onFrame(1, 1, 0))
        assertTrue(progress.onFrame(2, 1, 0)) // The first capture could fail.
        assertFalse(progress.onFrame(3, 1, 1))
        assertTrue(progress.onFrame(4, 2, 1))
        assertFalse(progress.onFrame(5, 0, 0))
    }

    @Test fun replacementSurfaceMustReceiveItsOwnFirstFrame() {
        val progress = StreamClueFrameProgress()
        progress.onFrame(100, 0, 0)
        progress.reset()
        assertEquals(0, progress.renderedFrameCount)
        assertTrue(progress.onFrame(1, 1, 0))
        assertEquals(1, progress.renderedFrameCount)
    }
    @Test fun playerCaptureWorksWithoutFfmpegAndCannotUseItsOldFrame() {
        val image = captureFromActiveClueRenderer(false,
            ffmpeg = { error("Inactive FFmpeg renderer must not be queried") },
            player = { "visible player image" })
        assertEquals("visible player image", image)
        assertNull(captureFromActiveClueRenderer(false,
            ffmpeg = { "stale FFmpeg image" }, player = { null }))
    }

    @Test fun ffmpegCaptureKeepsFrameMatchedDataAndDoesNotUsePlayerFallback() {
        val frame = "image with frame-matched camera metadata"
        assertSame(frame, captureFromActiveClueRenderer(true,
            ffmpeg = { frame }, player = { error("Inactive player") }))
        assertNull(captureFromActiveClueRenderer(true,
            ffmpeg = { null }, player = { "stale player image" }))
    }
}

package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMtxLogParserTest {
    @Test
    fun parseLine_recordFileComplete_emitsClosedFilePathAndDuration() {
        val result = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[path alpha] [recorder] record file complete path=/records/alpha/file.mp4 durationMs=177400",
        )

        val completed = result.event as MediaMTXEvent.RecordFileCompleted
        assertEquals("alpha", completed.path)
        assertEquals("/records/alpha/file.mp4", completed.filePath)
        assertEquals(177_400L, completed.durationMs)
    }

    @Test
    fun parseLine_runOnReadyCommand_isIgnored() {
        val result = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[path alpha] runOnReady command",
        )
        assertNull(result.event)
        assertEquals(emptySet<String>(), result.state.pendingRunOnReadyPaths)
        assertEquals(emptySet<String>(), result.state.pendingRunOnReadyVerbPaths)
    }

    @Test
    fun parseLine_rtmpPublishing_emitsStartedAndClearsSuppressionState() {
        val preState = MediaMtxParserState(
            pendingRunOnReadyPaths = linkedSetOf("alpha"),
            pendingRunOnReadyVerbPaths = linkedSetOf("alpha"),
        )

        val result = MediaMtxLogParser.parseLine(
            preState,
            "[RTMP] [conn 127.0.0.1:5555] is publishing to path 'alpha'",
        )

        assertEquals(MediaMTXEvent.StreamStarted("alpha").path, (result.event as MediaMTXEvent.StreamStarted).path)
        assertEquals(emptySet<String>(), result.state.pendingRunOnReadyPaths)
        assertEquals(emptySet<String>(), result.state.pendingRunOnReadyVerbPaths)
        assertEquals("alpha", result.state.rtmpConnPathMap["127.0.0.1:5555"])
    }

    @Test
    fun parseLine_duplicateRtmpPublishingForSameConnection_isIgnored() {
        val first = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[RTMP] [conn 127.0.0.1:5555] is publishing to path 'alpha'",
        )
        assertTrue(first.event is MediaMTXEvent.StreamStarted)

        val duplicate = MediaMtxLogParser.parseLine(
            first.state,
            "[RTMP] [conn 127.0.0.1:5555] is publishing to path 'alpha'",
        )

        assertNull(duplicate.event)
        assertEquals("alpha", duplicate.state.rtmpConnPathMap["127.0.0.1:5555"])
        assertEquals("127.0.0.1:5555", duplicate.state.pathPublisherConnMap["alpha"])
    }

    @Test
    fun parseLine_rtmpAcceptSucceeded_emitsPublishDiagnosticWithPathAndConnection() {
        val result = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[RTMP] [conn 192.168.1.123:48174] RTMP accept succeeded: publish=true path=/1sar34DjN2 query=",
        )

        val diagnostic = result.event as MediaMTXEvent.RtmpPublishDiagnostic
        assertEquals("1sar34DjN2", diagnostic.path)
        assertEquals("192.168.1.123:48174", diagnostic.publisherConnId)
        assertEquals("accept_succeeded", diagnostic.phase)
    }

    @Test
    fun parseLine_rtmpReaderInitialized_emitsSetupTimingDiagnostic() {
        val result = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[RTMP] [conn 192.168.1.123:34130] RTMP publish: reader initialized for path '1sar34DjN2' in 1.103496616s with 1 RTMP tracks (videoOnly=true)",
        )

        val diagnostic = result.event as MediaMTXEvent.RtmpPublishDiagnostic
        assertEquals("1sar34DjN2", diagnostic.path)
        assertEquals("192.168.1.123:34130", diagnostic.publisherConnId)
        assertEquals("reader_initialized", diagnostic.phase)
        assertEquals(1103L, diagnostic.elapsedMs)
        assertEquals("1 RTMP tracks (videoOnly=true)", diagnostic.detail)
    }

    @Test
    fun parseLine_rtmpPublisherInactive_emitsIdleDiagnosticBeforeClose() {
        val result = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[RTMP] [conn 192.168.1.123:47054] RTMP publisher inactive for 30s on path '1sar34DjN2', closing connection",
        )

        val diagnostic = result.event as MediaMTXEvent.RtmpPublishDiagnostic
        assertEquals("1sar34DjN2", diagnostic.path)
        assertEquals("192.168.1.123:47054", diagnostic.publisherConnId)
        assertEquals("publisher_inactive", diagnostic.phase)
        assertEquals(30_000L, diagnostic.elapsedMs)
    }

    @Test
    fun parseLine_rtmpPublishIdle_emitsIdleDiagnosticWithFractionalSeconds() {
        val result = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[RTMP] [conn 192.168.1.123:47054] RTMP publish idle for 30.3s on path '1sar34DjN2' (connBytesReceived=654124478 pathBytesReceived=1339016668 pathPacketsReceived=954239 pathUnitsReceived=63059)",
        )

        val diagnostic = result.event as MediaMTXEvent.RtmpPublishDiagnostic
        assertEquals("1sar34DjN2", diagnostic.path)
        assertEquals("192.168.1.123:47054", diagnostic.publisherConnId)
        assertEquals("publish_idle", diagnostic.phase)
        assertEquals(30_300L, diagnostic.elapsedMs)
        assertEquals(
            "connBytesReceived=654124478 pathBytesReceived=1339016668 pathPacketsReceived=954239 pathUnitsReceived=63059",
            diagnostic.detail,
        )
    }

    @Test
    fun parseLine_hlsCreated_emitsHlsStarted() {
        val result = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[HLS] [muxer bravo] created",
        )

        assertEquals(MediaMTXEvent.HlsStreamStarted("bravo").path, (result.event as MediaMTXEvent.HlsStreamStarted).path)
    }

    @Test
    fun parseLine_rtmpClosedWithMappedConnection_emitsErrorAndSuppressesImmediateStop() {
        val start = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[RTMP] [conn 192.168.1.10:5000] is publishing to path 'alpha'",
        )
        assertTrue(start.event is MediaMTXEvent.StreamStarted)

        val closed = MediaMtxLogParser.parseLine(
            start.state,
            "[RTMP] [conn 192.168.1.10:5000] closed: received an audio packet, track is H264",
        )
        val error = closed.event as MediaMTXEvent.StreamError
        assertEquals("alpha", error.path)
        assertEquals(
            "RTMP closed: received an audio packet, track is H264",
            error.reason,
        )

        val stopped = MediaMtxLogParser.parseLine(
            closed.state,
            "[path alpha] runOnReady command stopped",
        )
        assertNull(stopped.event)
    }

    @Test
    fun parseLine_rtmpExtendedChunkIdsError_isRetainedAcrossTeardownLines() {
        val start = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[RTMP] [conn 192.168.1.10:5000] is publishing to path 'alpha'",
        )

        val closed = MediaMtxLogParser.parseLine(
            start.state,
            "[RTMP] [conn 192.168.1.10:5000] closed: extended chunk stream IDs are not supported (yet)",
        )
        val error = closed.event as MediaMTXEvent.StreamError
        assertEquals("alpha", error.path)
        assertEquals(
            "RTMP closed: publisher uses extended chunk stream IDs unsupported by current MediaMTX",
            error.reason,
        )

        val hlsDestroyed = MediaMtxLogParser.parseLine(
            closed.state,
            "[HLS] [muxer alpha] destroyed: terminated",
        )
        assertNull(hlsDestroyed.event)

        val pathDestroyed = MediaMtxLogParser.parseLine(
            hlsDestroyed.state,
            "[path alpha] destroyed: terminated",
        )
        assertNull(pathDestroyed.event)
    }

    @Test
    fun parseLine_runOnReadyCommandStopped_isIgnoredWithoutRtmpState() {
        val result = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[path alpha] runOnReady command stopped",
        )
        assertNull(result.event)
    }

    @Test
    fun parseLine_publisherHandoff_suppressesTerminatedCloseError() {
        val started = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[RTMP] [conn 192.168.1.10:5000] is publishing to path 'alpha'",
        )
        assertTrue(started.event is MediaMTXEvent.StreamStarted)

        val handoff = MediaMtxLogParser.parseLine(
            started.state,
            "[path alpha] closing existing publisher",
        )
        assertEquals(
            "alpha",
            (handoff.event as MediaMTXEvent.StreamPublisherHandoff).path,
        )

        val closed = MediaMtxLogParser.parseLine(
            handoff.state,
            "[RTMP] [conn 192.168.1.10:5000] closed: terminated",
        )
        assertNull(closed.event)
    }

    @Test
    fun parseLine_publisherHandoffWithNewPublisherFirst_suppressesOldTerminatedCloseError() {
        val initial = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[RTMP] [conn 192.168.1.10:5000] is publishing to path 'alpha'",
        )
        assertTrue(initial.event is MediaMTXEvent.StreamStarted)

        val handoff = MediaMtxLogParser.parseLine(
            initial.state,
            "[path alpha] closing existing publisher",
        )
        assertTrue(handoff.event is MediaMTXEvent.StreamPublisherHandoff)

        val replacement = MediaMtxLogParser.parseLine(
            handoff.state,
            "[RTMP] [conn 192.168.1.10:5001] is publishing to path 'alpha'",
        )
        assertTrue(replacement.event is MediaMTXEvent.StreamStarted)

        val oldClosed = MediaMtxLogParser.parseLine(
            replacement.state,
            "[RTMP] [conn 192.168.1.10:5000] closed: terminated",
        )
        assertNull(oldClosed.event)
    }
}

class MediaMtxRecorderLineTest {
    private fun parse(line: String) = MediaMtxLogParser.parseLine(MediaMtxParserState(), line).event

    @Test fun recorderStartStopAndSkip() {
        assertEquals("alpha", (parse("[path alpha] [recorder] recording 2 tracks (H264, MPEG-4 Audio)") as MediaMTXEvent.RecorderStarted).path)
        assertEquals("alpha", (parse("[path alpha] [recorder] recording 1 track (H265)") as MediaMTXEvent.RecorderStarted).path)
        assertEquals("alpha", (parse("[path alpha] [recorder] recording stopped") as MediaMTXEvent.RecorderStopped).path)
        assertEquals("alpha", (parse("[path alpha] [recorder] no supported tracks found, skipping recording") as MediaMTXEvent.RecorderStopped).path)
        assertTrue(parse("[path alpha] [recorder] record file complete path=/r/a.mp4 durationMs=1") is MediaMTXEvent.RecordFileCompleted)
    }
}

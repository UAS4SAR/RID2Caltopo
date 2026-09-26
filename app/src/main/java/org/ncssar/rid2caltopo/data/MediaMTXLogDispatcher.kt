package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

data class MediaMtxParserState(
    val pendingRunOnReadyPaths: LinkedHashSet<String> = linkedSetOf(),
    val pendingRunOnReadyVerbPaths: LinkedHashSet<String> = linkedSetOf(),
    val rtmpConnPathMap: Map<String, String> = emptyMap(),
    val pathPublisherConnMap: Map<String, String> = emptyMap(),
    val suppressStopForPath: LinkedHashSet<String> = linkedSetOf(),
    val publisherHandoffClosingConns: LinkedHashSet<String> = linkedSetOf(),
    val publisherIntentionalCloseConns: LinkedHashSet<String> = linkedSetOf(),
)

data class MediaMtxParserResult(
    val event: MediaMTXEvent?,
    val state: MediaMtxParserState,
)

object MediaMtxLogParser {
    private val pathRegex = Regex("""\[path ([^\]]+)]\s*(.+)""")
    private val startRegex = Regex("""MediaMTX (v[0-9]+\.[0-9]+\.[0-9]+)""")
    private val hlsEventRegex = Regex("""\[HLS]\s+\[muxer ([^\]]+)]\s+(.+)""")
    private val rtspNoPublishingRegex =
        Regex("""(?:no one is publishing to path|no stream is available on path) '([^']+)'""")
    private val rtmpPublishingRegex =
        Regex("""\[RTMP]\s+\[conn ([^\]]+)]\s+is publishing to path '([^']+)'""")
    private val rtmpAcceptSucceededRegex =
        Regex("""\[RTMP]\s+\[conn ([^\]]+)]\s+RTMP accept succeeded:\s+publish=true\s+path=/([^ ]+)\s+query=.*""")
    private val rtmpReaderInitializedRegex =
        Regex("""\[RTMP]\s+\[conn ([^\]]+)]\s+RTMP publish:\s+reader initialized for path '([^']+)'\s+in\s+([^ ]+)\s+with\s+(.+)""")
    private val rtmpPublishIdleRegex =
        Regex("""\[RTMP]\s+\[conn ([^\]]+)]\s+RTMP publish idle for\s+([^ ]+)\s+on path '([^']+)'\s+\((.+)\)""")
    private val rtmpPublisherInactiveRegex =
        Regex("""\[RTMP]\s+\[conn ([^\]]+)]\s+RTMP publisher inactive for\s+([^ ]+)\s+on path '([^']+)', closing connection""")
    private val rtmpClosedRegex =
        Regex("""\[RTMP]\s+\[conn ([^\]]+)]\s+closed:\s*(.+)""")
    private val rtmpPublishCommandRegex =
        Regex("""\[RTMP]\s+\[conn ([^\]]+)]\s+RTMP control:\s+received command '([^']+)'\s+\(id=\d+\)\s+during publish on path '([^']+)'""")
    private val recordFileCompleteRegex = Regex(
        """\[path ([^\]]+)]\s+\[recorder]\s+record file complete path=(.+) durationMs=(\d+)"""
    )

    fun parseLine(state: MediaMtxParserState, line: String): MediaMtxParserResult {
        val pendingRunOnReadyPaths = LinkedHashSet(state.pendingRunOnReadyPaths)
        val pendingRunOnReadyVerbPaths = LinkedHashSet(state.pendingRunOnReadyVerbPaths)
        val rtmpConnPathMap = state.rtmpConnPathMap.toMutableMap()
        val pathPublisherConnMap = state.pathPublisherConnMap.toMutableMap()
        val suppressStopForPath = LinkedHashSet(state.suppressStopForPath)
        val publisherHandoffClosingConns = LinkedHashSet(state.publisherHandoffClosingConns)
        val publisherIntentionalCloseConns = LinkedHashSet(state.publisherIntentionalCloseConns)
        val trimmed = line.trim()

        fun updatedState() = MediaMtxParserState(
            pendingRunOnReadyPaths = pendingRunOnReadyPaths,
            pendingRunOnReadyVerbPaths = pendingRunOnReadyVerbPaths,
            rtmpConnPathMap = rtmpConnPathMap,
            pathPublisherConnMap = pathPublisherConnMap,
            suppressStopForPath = suppressStopForPath,
            publisherHandoffClosingConns = publisherHandoffClosingConns,
            publisherIntentionalCloseConns = publisherIntentionalCloseConns,
        )

        fun emitStop(path: String): MediaMTXEvent? {
            if (suppressStopForPath.contains(path)) return null
            return MediaMTXEvent.StreamStopped(path)
        }

        fun normalizeRtmpCloseReason(reason: String): String {
            if (reason.contains("extended chunk stream IDs are not supported", ignoreCase = true)) {
                return "RTMP closed: publisher uses extended chunk stream IDs unsupported by current MediaMTX"
            }
            if (reason.contains("unexpected EOF", ignoreCase = true)) {
                return "RTMP closed: publisher disconnected unexpectedly"
            }
            return "RTMP closed: $reason"
        }

        fun parseDurationMs(value: String): Long? {
            val trimmedValue = value.trim()
            val suffix = when {
                trimmedValue.endsWith("ms") -> "ms"
                trimmedValue.endsWith("s") -> "s"
                else -> return null
            }
            val numeric = trimmedValue.removeSuffix(suffix).toDoubleOrNull() ?: return null
            return when (suffix) {
                "ms" -> numeric.toLong()
                "s" -> (numeric * 1000.0).toLong()
                else -> null
            }
        }

        val recordFileCompleteMatch = recordFileCompleteRegex.find(line)
        if (recordFileCompleteMatch != null) {
            return MediaMtxParserResult(
                MediaMTXEvent.RecordFileCompleted(
                    path = recordFileCompleteMatch.groupValues[1],
                    filePath = recordFileCompleteMatch.groupValues[2],
                    durationMs = recordFileCompleteMatch.groupValues[3].toLongOrNull() ?: 0L,
                ),
                updatedState(),
            )
        }

        val match = pathRegex.find(line)
        if (match != null) {
            val path = match.groupValues[1]
            val rem = match.groupValues[2]
            val event = when {
                rem.contains("runOnReady command") -> {
                    pendingRunOnReadyPaths.remove(path)
                    pendingRunOnReadyVerbPaths.remove(path)
                    null
                }

                rem.contains("runOnReady") -> {
                    pendingRunOnReadyPaths.remove(path)
                    pendingRunOnReadyVerbPaths.remove(path)
                    null
                }

                rem.contains("closing existing publisher") -> {
                    suppressStopForPath.add(path)
                    pathPublisherConnMap[path]?.let { publisherHandoffClosingConns.add(it) }
                    MediaMTXEvent.StreamPublisherHandoff(path)
                }

                rem.contains("created") -> null
                rem.contains("destroyed") -> {
                    val publisherConn = pathPublisherConnMap.remove(path)
                    publisherConn?.let { publisherHandoffClosingConns.remove(it) }
                    if (publisherConn != null || suppressStopForPath.contains(path)) {
                        emitStop(path)
                    } else {
                        null
                    }
                }
                else -> null
            }
            return MediaMtxParserResult(
                event,
                updatedState(),
            )
        }

        val rtmpPublishingMatch = rtmpPublishingRegex.find(line)
        if (rtmpPublishingMatch != null) {
            val conn = rtmpPublishingMatch.groupValues[1]
            val path = rtmpPublishingMatch.groupValues[2]
            val previousPathForConn = rtmpConnPathMap[conn]
            val previousConnForPath = pathPublisherConnMap[path]
            val isDuplicatePublisherLine =
                previousPathForConn == path && previousConnForPath == conn

            if (previousPathForConn != null &&
                previousPathForConn != path &&
                pathPublisherConnMap[previousPathForConn] == conn
            ) {
                pathPublisherConnMap.remove(previousPathForConn)
            }
            rtmpConnPathMap[conn] = path
            pathPublisherConnMap[path] = conn
            pendingRunOnReadyPaths.remove(path)
            pendingRunOnReadyVerbPaths.remove(path)
            suppressStopForPath.remove(path)
            publisherIntentionalCloseConns.remove(conn)
            if (isDuplicatePublisherLine) {
                return MediaMtxParserResult(
                    null,
                    updatedState(),
                )
            }
            return MediaMtxParserResult(
                MediaMTXEvent.StreamStarted(path),
                updatedState(),
            )
        }

        val rtmpAcceptSucceededMatch = rtmpAcceptSucceededRegex.find(line)
        if (rtmpAcceptSucceededMatch != null) {
            val conn = rtmpAcceptSucceededMatch.groupValues[1]
            val path = rtmpAcceptSucceededMatch.groupValues[2].trim('/')
            rtmpConnPathMap[conn] = path
            return MediaMtxParserResult(
                MediaMTXEvent.RtmpPublishDiagnostic(
                    path = path,
                    publisherConnId = conn,
                    phase = "accept_succeeded",
                ),
                updatedState(),
            )
        }

        val rtmpReaderInitializedMatch = rtmpReaderInitializedRegex.find(line)
        if (rtmpReaderInitializedMatch != null) {
            val conn = rtmpReaderInitializedMatch.groupValues[1]
            val path = rtmpReaderInitializedMatch.groupValues[2].trim('/')
            rtmpConnPathMap[conn] = path
            return MediaMtxParserResult(
                MediaMTXEvent.RtmpPublishDiagnostic(
                    path = path,
                    publisherConnId = conn,
                    phase = "reader_initialized",
                    elapsedMs = parseDurationMs(rtmpReaderInitializedMatch.groupValues[3]),
                    detail = rtmpReaderInitializedMatch.groupValues[4],
                ),
                updatedState(),
            )
        }

        val rtmpPublisherInactiveMatch = rtmpPublisherInactiveRegex.find(line)
        if (rtmpPublisherInactiveMatch != null) {
            val conn = rtmpPublisherInactiveMatch.groupValues[1]
            val path = rtmpPublisherInactiveMatch.groupValues[3].trim('/')
            return MediaMtxParserResult(
                MediaMTXEvent.RtmpPublishDiagnostic(
                    path = path,
                    publisherConnId = conn,
                    phase = "publisher_inactive",
                    elapsedMs = parseDurationMs(rtmpPublisherInactiveMatch.groupValues[2]),
                ),
                updatedState(),
            )
        }

        val rtmpPublishIdleMatch = rtmpPublishIdleRegex.find(line)
        if (rtmpPublishIdleMatch != null) {
            val conn = rtmpPublishIdleMatch.groupValues[1]
            val path = rtmpPublishIdleMatch.groupValues[3].trim('/')
            return MediaMtxParserResult(
                MediaMTXEvent.RtmpPublishDiagnostic(
                    path = path,
                    publisherConnId = conn,
                    phase = "publish_idle",
                    elapsedMs = parseDurationMs(rtmpPublishIdleMatch.groupValues[2]),
                    detail = rtmpPublishIdleMatch.groupValues[4],
                ),
                updatedState(),
            )
        }

        val rtmpPublishCommandMatch = rtmpPublishCommandRegex.find(line)
        if (rtmpPublishCommandMatch != null) {
            val conn = rtmpPublishCommandMatch.groupValues[1]
            val command = rtmpPublishCommandMatch.groupValues[2]
            val path = rtmpPublishCommandMatch.groupValues[3]
            if (command.equals("FCUnpublish", ignoreCase = true) ||
                command.equals("deleteStream", ignoreCase = true)
            ) {
                if (rtmpConnPathMap[conn] == path || pathPublisherConnMap[path] == conn) {
                    publisherIntentionalCloseConns.add(conn)
                }
            }
            return MediaMtxParserResult(
                null,
                updatedState(),
            )
        }

        val rtmpClosedMatch = rtmpClosedRegex.find(line)
        if (rtmpClosedMatch != null) {
            val conn = rtmpClosedMatch.groupValues[1]
            val reason = rtmpClosedMatch.groupValues[2].trim()
            val path = rtmpConnPathMap.remove(conn)
            if (path != null) {
                val intentionalPublisherStop = publisherIntentionalCloseConns.remove(conn)
                if (pathPublisherConnMap[path] == conn) {
                    pathPublisherConnMap.remove(path)
                }
                suppressStopForPath.add(path)
                if (publisherHandoffClosingConns.remove(conn) &&
                    reason.equals("terminated", ignoreCase = true)
                ) {
                    return MediaMtxParserResult(
                        null,
                        updatedState(),
                    )
                }
                if (intentionalPublisherStop) {
                    suppressStopForPath.remove(path)
                    return MediaMtxParserResult(
                        MediaMTXEvent.StreamStopped(path = path, publisherConnId = conn),
                        updatedState(),
                    )
                }
                if (reason.contains("connection reset by peer", ignoreCase = true)) {
                    suppressStopForPath.remove(path)
                    return MediaMtxParserResult(
                        MediaMTXEvent.StreamStopped(path = path, publisherConnId = conn),
                        updatedState(),
                    )
                }
                return MediaMtxParserResult(
                    MediaMTXEvent.StreamError(path = path, publisherConnId = conn, reason = normalizeRtmpCloseReason(reason)),
                    updatedState(),
                )
            }
        }

        val hlsEventMatch = hlsEventRegex.find(line)
        if (hlsEventMatch != null) {
            val path = hlsEventMatch.groupValues[1]
            val rem = hlsEventMatch.groupValues[2]
            val event = when {
                rem.contains("created") -> MediaMTXEvent.HlsStreamStarted(path)
                rem.contains("destroyed") -> emitStop(path)
                else -> null
            }
            return MediaMtxParserResult(
                event,
                updatedState(),
            )
        }

        val rtspNoPublishingMatch = rtspNoPublishingRegex.find(line)
        if (rtspNoPublishingMatch != null) {
            val path = rtspNoPublishingMatch.groupValues[1]
            val publisherConn = pathPublisherConnMap.remove(path)
            publisherConn?.let {
                publisherHandoffClosingConns.remove(it)
            }
            val event = if (publisherConn != null || suppressStopForPath.contains(path)) {
                emitStop(path)
            } else {
                null
            }
            return MediaMtxParserResult(
                event,
                updatedState(),
            )
        }

        val startMatch = startRegex.find(line)
        if (startMatch != null) {
            return MediaMtxParserResult(
                MediaMTXEvent.ServerStarted(startMatch.groupValues[1]),
                updatedState(),
            )
        }

        return MediaMtxParserResult(
            null,
            updatedState(),
        )
    }
}

object MediaMTXLogDispatcher {
    private val listeners = mutableSetOf<(MediaMTXEvent) -> Unit>()
    private var parserState = MediaMtxParserState()
    private var pendingChunkFragment = ""

    fun addListener(listener: (MediaMTXEvent) -> Unit) {
        listeners += listener
    }

    private fun emit(event: MediaMTXEvent) {
        listeners.forEach { it(event) }
    }

    fun onLogLine(line: String) {
        val result = MediaMtxLogParser.parseLine(parserState, line)
        parserState = result.state
        result.event?.let { emit(it) }
    }

    internal fun dispatchChunk(
        chunk: String,
        shouldLog: Boolean,
        flushTrailingFragment: Boolean = true,
    ) {
        val combined = pendingChunkFragment + chunk
        val endsWithNewline = combined.endsWith('\n') || combined.endsWith('\r')
        val rawLines = combined.split(Regex("""\r?\n"""))
        val completeLines = if (endsWithNewline || flushTrailingFragment) rawLines else rawLines.dropLast(1)
        pendingChunkFragment = if (endsWithNewline || flushTrailingFragment) "" else rawLines.lastOrNull().orEmpty()

        completeLines
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { chunkLine ->
                if (shouldLog && !chunkLine.contains("RTMP control: sent Acknowledge")) {
                    CTDebug("MediaMTX", chunkLine)
                }
                onLogLine(chunkLine)
            }
    }

    @JvmStatic
    fun dispatch(line: String) {
        dispatchChunk(line, shouldLog = true, flushTrailingFragment = true)
    }

    internal fun resetForTests() {
        listeners.clear()
        parserState = MediaMtxParserState()
        pendingChunkFragment = ""
    }
}

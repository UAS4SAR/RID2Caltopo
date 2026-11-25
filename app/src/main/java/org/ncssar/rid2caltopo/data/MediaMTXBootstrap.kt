package org.ncssar.rid2caltopo.data

import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.video.StreamRegistry

object MediaMTXBootstrap {
    private const val SIGNAL_TAG = "MediaMTXSignal"

    @JvmStatic
    fun init() {
        // Structured events are the primary lifecycle feed from MediaMTX.
        MediaMTXStructuredDispatcher.addListener { event ->
            when (event) {
                is MediaMTXEvent.StreamConnecting ->
                    if (shouldApplyStructuredStreamLifecycleEvent(event)) {
                        StreamRegistry.onStreamConnecting(event.path)
                    }

                is MediaMTXEvent.StreamStarted -> {
                    org.ncssar.rid2caltopo.video.RecordingStatus.onStreamStarted(event.path)
                    StreamRegistry.onStreamStarted(event.path, event.publisherConnId)
                }

                is MediaMTXEvent.StreamPublisherHandoff ->
                    StreamRegistry.onStreamPublisherHandoff(event.path, event.publisherConnId)

                is MediaMTXEvent.StreamStopped ->
                    if (shouldApplyStructuredStreamLifecycleEvent(event)) {
                        StreamRegistry.onStreamStopped(event.path, event.publisherConnId)
                    }

                is MediaMTXEvent.StreamError ->
                    StreamRegistry.onStreamError(event.path, event.reason, event.publisherConnId)

                is MediaMTXEvent.RtmpSessionClosed -> {}
                is MediaMTXEvent.RtmpPublishDiagnostic -> {
                    logRtmpPublishDiagnostic(event)
                    if (shouldStopStreamForRtmpDiagnostic(event)) {
                        StreamRegistry.onStreamStopped(event.path, event.publisherConnId)
                    }
                }

                is MediaMTXEvent.ServerStarted -> {
                    org.ncssar.rid2caltopo.video.RecordingStatus.onServerStarted()
                    MediaMTXStatus.onServerStarted(event.version)
                }

                is MediaMTXEvent.HlsStreamStarted -> {}
                is MediaMTXEvent.RecordFileCompleted -> {}
                //    StreamRegistry.onStreamStarted(event.path)
                is MediaMTXEvent.RecorderStarted, is MediaMTXEvent.RecorderStopped -> {}
            }
        }
        // Raw log parsing is intentionally narrower: only use it for stop signals
        // that some controllers expose in logs before MediaMTX emits/destroys the
        // corresponding structured path state.
        MediaMTXLogDispatcher.addListener { event ->
            when (event) {
                is MediaMTXEvent.StreamStopped ->
                    StreamRegistry.onStreamStopped(event.path, event.publisherConnId)

                is MediaMTXEvent.RtmpPublishDiagnostic -> {
                    logRtmpPublishDiagnostic(event)
                    if (shouldStopStreamForRtmpDiagnostic(event)) {
                        StreamRegistry.onStreamStopped(event.path, event.publisherConnId)
                    }
                }

                // The recorder's own lines are the only proof that a stream is being written.
                is MediaMTXEvent.RecorderStarted -> {
                    CTDebug(SIGNAL_TAG, "Recorder started path=${event.path}")
                    org.ncssar.rid2caltopo.video.RecordingStatus.onRecorderStarted(event.path)
                }
                is MediaMTXEvent.RecorderStopped -> {
                    CTDebug(SIGNAL_TAG, "Recorder stopped path=${event.path}")
                    org.ncssar.rid2caltopo.video.RecordingStatus.onRecorderStopped(event.path)
                }

                else -> {}
            }
        }
    }

    private fun logRtmpPublishDiagnostic(event: MediaMTXEvent.RtmpPublishDiagnostic) {
        val elapsed = event.elapsedMs?.let { " elapsedMs=$it" }.orEmpty()
        val conn = event.publisherConnId?.let { " publisherConnId=$it" }.orEmpty()
        val detail = event.detail?.let { " detail=$it" }.orEmpty()
        CTDebug(
            SIGNAL_TAG,
            "RTMP setup path=${event.path} phase=${event.phase}$conn$elapsed$detail"
        )
    }
}

internal fun shouldApplyStructuredStreamLifecycleEvent(event: MediaMTXEvent): Boolean =
    when (event) {
        is MediaMTXEvent.StreamConnecting -> false
        is MediaMTXEvent.StreamStopped -> !event.publisherConnId.isNullOrBlank()
        else -> true
    }

internal fun shouldStopStreamForRtmpDiagnostic(event: MediaMTXEvent.RtmpPublishDiagnostic): Boolean =
    event.phase == "publisher_inactive" && !event.publisherConnId.isNullOrBlank()

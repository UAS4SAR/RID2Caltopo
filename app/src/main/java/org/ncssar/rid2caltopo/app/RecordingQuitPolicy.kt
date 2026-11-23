package org.ncssar.rid2caltopo.app

/** Whether Quit must first finish recordings (see MediaMTXService.FinishRecordingsBeforeQuit). */
object RecordingQuitPolicy {
    const val SAVING_TITLE = "Saving video…"
    const val SAVING_MESSAGE =
        "The app closes as soon as the recorded video is saved. Closing now leaves the video to be finished the next time the app opens."
    const val CLOSE_NOW = "Close now"

    /**
     * A running relay with Capture Streams on can hold an open segment that is only closed
     * when the server stops; a finalization lease means a segment is being remuxed or copied.
     */
    @JvmStatic
    fun mustFinishBeforeQuit(relayRunning: Boolean, captureEnabled: Boolean, finalizationActive: Boolean): Boolean =
        finalizationActive || (relayRunning && captureEnabled)
}

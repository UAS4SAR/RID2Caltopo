package org.ncssar.rid2caltopo.video

/** Main-thread bookkeeping, deliberately not Compose state. */
internal class StreamClueFrameProgress {
    var renderedFrameCount: Int = 0
        private set

    fun reset() {
        renderedFrameCount = 0
    }

    /** True only when a pending clue needs to retry against this new frame. */
    fun onFrame(count: Int, pendingRequest: Long, handledRequest: Long): Boolean {
        renderedFrameCount = count
        return pendingRequest != 0L && pendingRequest != handledRequest
    }
}

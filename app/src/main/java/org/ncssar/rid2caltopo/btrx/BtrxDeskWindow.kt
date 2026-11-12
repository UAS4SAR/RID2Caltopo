package org.ncssar.rid2caltopo.btrx

import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * The Desk Pod's pairing window, tracked from DESK_PAIR frames by arrival order.
 *
 * Frames carry no timestamp, and a tap can land while the link is still being made,
 * so its DESK_PAIR open=1 arrives in the connect-time backlog. That frame still
 * counts: the window is open from the latest DESK_PAIR open=1 until a later
 * DESK_PAIR open=0 arrives or `seconds_left` (default 60) has passed since the
 * open frame arrived. A repeat of the same open frame (same seq) does not extend it.
 * An open=1 followed in the same backlog by open=0 is closed: the close is newer.
 */
class BtrxDeskWindow {
    enum class CloseReason { NONE, CLOSED_BY_DESK_POD, EXPIRED }

    var openedAtMs = 0L
        private set
    var expiresAtMs = 0L
        private set
    var source = 0
        private set
    /** True when the open frame came in the connect-time backlog. */
    var openedFromBacklog = false
        private set
    /** Bumped each time a new window opens, so a flow can tell windows apart. */
    var generation = 0
        private set
    private var isOpenFlag = false
    private var openSeq = -1
    var lastClose = CloseReason.NONE
        private set

    fun onDeskPair(seq: Int, dp: P.DeskPair, arrivalMs: Long, backlog: Boolean) {
        if (dp.open == 1) {
            if (isOpenFlag && seq == openSeq && arrivalMs < expiresAtMs) return
            val seconds = if (dp.secondsLeft > 0) dp.secondsLeft else DEFAULT_SECONDS
            isOpenFlag = true
            openSeq = seq
            openedAtMs = arrivalMs
            expiresAtMs = arrivalMs + seconds * 1000L
            source = dp.source
            openedFromBacklog = backlog
            lastClose = CloseReason.NONE
            generation++
        } else {
            if (isOpenFlag) lastClose = CloseReason.CLOSED_BY_DESK_POD
            isOpenFlag = false
            openSeq = -1
        }
    }

    fun isOpen(nowMs: Long): Boolean {
        if (isOpenFlag && nowMs >= expiresAtMs) {
            isOpenFlag = false
            lastClose = CloseReason.EXPIRED
        }
        return isOpenFlag
    }

    fun secondsLeft(nowMs: Long): Int = if (isOpen(nowMs)) ((expiresAtMs - nowMs + 999) / 1000).toInt() else 0

    fun reset() {
        isOpenFlag = false
        openSeq = -1
        lastClose = CloseReason.NONE
    }

    companion object {
        const val DEFAULT_SECONDS = 60
    }
}

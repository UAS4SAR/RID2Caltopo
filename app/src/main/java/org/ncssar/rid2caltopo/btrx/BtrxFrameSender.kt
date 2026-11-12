package org.ncssar.rid2caltopo.btrx

/** What [BtrxDeskPodModel] needs from the current link's [BtrxClient] to send frames. */
interface BtrxFrameSender {
    val isReady: Boolean
    fun nextSeq(): Int
    /** One btrx frame on port 256 to [to], hop_limit 0. Never blocks. */
    fun sendFrame(to: Long, frame: ByteArray)
}

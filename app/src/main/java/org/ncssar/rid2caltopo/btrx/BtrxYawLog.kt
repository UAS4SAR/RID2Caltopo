package org.ncssar.rid2caltopo.btrx

import java.util.Locale
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * Machine-readable gyro-yaw and SEI yaw lines for the session / BTRX log
 * (firmware 0x010C STEER format 2, PROTOCOL.md "Gyro yaw" and "Drift test").
 * Nothing here is sent to CalTopo.
 */
object BtrxYawLog {
    /** Minimum gap between `Btrx sei_yaw` lines (10 Hz is enough for interpolation). */
    const val SEI_LOG_INTERVAL_MS = 100L

    data class SeiSample(val tMs: Long, val yawTrueDeg: Double, val stream: String)

    @Volatile var sink: ((String) -> Unit)? = null

    @Volatile var latestSei: SeiSample? = null
        private set

    private val lock = Any()
    private var lastSeiLogMs = Long.MIN_VALUE / 2

    fun resetForTest() {
        synchronized(lock) {
            latestSei = null
            lastSeiLogMs = Long.MIN_VALUE / 2
        }
    }

    fun steerFields(st: P.Steer): String {
        val y = st.yaw ?: return ""
        val sample = y.sampleMs(st.windowEndMs)?.toString() ?: "-"
        return "yaw_x10=${y.yawX10} yaw_fixed_x10=${y.yawFixedX10} turns=${y.turns} " +
            "bias_z_mdps=${y.biasZMdps} t_off_ms=${y.tOffMs} sample_ms=$sample " +
            "yflags=0x%02x".format(y.yflags)
    }

    fun steerLine(arrivalMs: Long, st: P.Steer, backlog: Boolean = false): String? {
        val y = st.yaw ?: return null
        val sample = y.sampleMs(st.windowEndMs)?.toString() ?: "-"
        val sei = synchronized(lock) { latestSei }
        val seiPart = if (sei != null)
            " sei_t_ms=${sei.tMs} sei_yaw_true_deg=${"%.3f".format(Locale.US, sei.yawTrueDeg)}" +
                if (sei.stream.isNotEmpty()) " sei_stream=${sei.stream}" else ""
        else ""
        return "Btrx gyro_yaw arrival_ms=$arrivalMs window_end_ms=${st.windowEndMs} sample_ms=$sample " +
            "yaw_x10=${y.yawX10} yaw_fixed_x10=${y.yawFixedX10} turns=${y.turns} " +
            "bias_z_mdps=${y.biasZMdps} yflags=0x%02x t_off_ms=${y.tOffMs} backlog=${if (backlog) 1 else 0}".format(y.yflags) +
            seiPart
    }

    fun seiLine(sample: SeiSample): String =
        "Btrx sei_yaw t_ms=${sample.tMs} yaw_true_deg=${"%.3f".format(Locale.US, sample.yawTrueDeg)}" +
            if (sample.stream.isNotEmpty()) " stream=${sample.stream}" else ""

    fun noteSei(tMs: Long, yawTrueDeg: Double, stream: String = "") {
        if (!yawTrueDeg.isFinite()) return
        val sample = SeiSample(tMs, yawTrueDeg, stream)
        val emit: String? = synchronized(lock) {
            latestSei = sample
            if (tMs - lastSeiLogMs >= SEI_LOG_INTERVAL_MS || tMs < lastSeiLogMs) {
                lastSeiLogMs = tMs
                seiLine(sample)
            } else null
        }
        if (emit != null) sink?.invoke(emit)
    }

    fun logSteer(arrivalMs: Long, st: P.Steer, backlog: Boolean = false) {
        steerLine(arrivalMs, st, backlog)?.let { sink?.invoke(it) ?: Unit }
    }
}

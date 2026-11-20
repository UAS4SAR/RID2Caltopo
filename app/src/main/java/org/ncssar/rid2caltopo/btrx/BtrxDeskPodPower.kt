package org.ncssar.rid2caltopo.btrx

import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * Desk Pod power diagnostics (Ken, Nov 17). At the Nov 16 training the Desk Pod lost power
 * 25-50 s after each power-on (reset_reason 0, then minutes without advertising) and the chip
 * only said the link was down. This tells the operator what the log showed:
 *
 *  - a session that ended because the Desk Pod stopped answering (supervision timeout, GATT
 *    133 on a read or write, an operation with no answer) is "Desk Pod went silent — check
 *    its power" until the next session starts; when its last HEALTH or DeviceMetrics just
 *    before the drop showed good power ([powerOkAtDrop]) it is "Desk Pod link dropped —
 *    reconnecting" instead (Nov 20: 7 drops after landing, with USB power and a full battery
 *    and the Desk Pod's uptime unbroken);
 *  - a Desk Pod HEALTH with uptime under [RESTART_UPTIME_S] within [RESTART_AFTER_DROP_MS] of a
 *    link drop is "Desk Pod restarted (power-on)" (or the reset reason it reported), shown for
 *    [RESTART_SHOW_MS];
 *  - the Desk Pod's standard Meshtastic DeviceMetrics (battery_level, voltage), when it sends
 *    them, are kept for the Desk Pod screen.
 *
 * Pure state; [BtrxDeskPodModel] calls it under its lock. Same rules and strings on the iPad
 * (BtrxDeskPodPower.swift).
 */
class BtrxDeskPodPower {
    data class Restart(val text: String, val atMs: Long, val uptimeS: Long, val downMs: Long?)

    /** When the latest session that ended with the Desk Pod silent ended; null once a new session starts. */
    var silentSinceMs: Long? = null
        private set
    /** Why that session ended. */
    var silentReason: String? = null
        private set
    /** The Desk Pod's power was fine just before that session ended: the radio link dropped. */
    var silentPowerOk: Boolean = false
        private set
    /** [SILENT] or [LINK_DROPPED] while silent, else null. */
    val silentText: String? get() = silentSinceMs?.let { if (silentPowerOk) LINK_DROPPED else SILENT }
    /** The latest link drop not yet matched to a restart. */
    private var lastDropAtMs: Long? = null
    /** The latest restart seen after a drop. */
    var restart: Restart? = null
        private set
    var metrics: MeshtasticSerial.DeviceMetrics? = null
        private set
    var metricsAtMs: Long? = null
        private set
    var metricsSource: String? = null
        private set

    /** The session ended. Returns the warning to log when the Desk Pod went silent, else null. */
    fun onDrop(reason: String, nowMs: Long, powerOk: Boolean = false): String? {
        lastDropAtMs = nowMs
        if (!isSilentDrop(reason)) return null
        silentSinceMs = nowMs
        silentReason = reason
        silentPowerOk = powerOk
        return if (powerOk) "$LINK_DROPPED ($reason; its power was fine)" else "$SILENT ($reason)"
    }

    /** A new session started: the Desk Pod answered a connect, so it is not silent. */
    fun onSessionStarted() {
        silentSinceMs = null
        silentReason = null
        silentPowerOk = false
    }

    /**
     * The Desk Pod's own live HEALTH. Returns the warning to log when it shows a restart after
     * a recent drop (once per drop), else null.
     */
    fun onDeskHealth(uptimeS: Long, resetReason: Long?, arrivalMs: Long): String? {
        val drop = lastDropAtMs ?: return null
        if (arrivalMs - drop > RESTART_AFTER_DROP_MS || uptimeS >= RESTART_UPTIME_S) return null
        lastDropAtMs = null
        val text = restartText(resetReason)
        val bootMs = arrivalMs - uptimeS * 1000
        val downMs = (bootMs - drop).takeIf { it >= 0 }
        restart = Restart(text, arrivalMs, uptimeS, downMs)
        val down = downMs?.let { "; it came back about ${it / 1000} s after the link dropped" } ?: ""
        return "$text: HEALTH uptime_s=$uptimeS, ${(arrivalMs - drop) / 1000} s after the link dropped$down"
    }

    fun onDeviceMetrics(m: MeshtasticSerial.DeviceMetrics, source: String, nowMs: Long) {
        metrics = m; metricsAtMs = nowMs; metricsSource = source
    }

    /** The restart notice while it is fresh ([RESTART_SHOW_MS]), for the map chip. */
    fun restartNotice(nowMs: Long): String? = restart?.takeIf { nowMs - it.atMs in 0..RESTART_SHOW_MS }?.text

    companion object {
        const val SILENT = "Desk Pod went silent — check its power"
        const val LINK_DROPPED = "Desk Pod link dropped — reconnecting"
        /** A Desk Pod HEALTH at most this old at the drop counts for [powerOkAtDrop]. */
        const val POWER_HEALTH_FRESH_MS = 60_000L
        /** DeviceMetrics come less often (Meshtastic telemetry interval). */
        const val POWER_METRICS_FRESH_MS = 15 * 60_000L
        /** A battery at or above this (mV) is fine without USB. */
        const val POWER_OK_VBAT_MV = 3600
        /** battery_level at or above this is fine (101 = external power). */
        const val POWER_OK_LEVEL = 20

        /**
         * The Desk Pod's power was fine just before a drop: a fresh HEALTH with USB power or a
         * good battery and no low-supply flags, or fresh DeviceMetrics with external power or
         * a good battery. Ages are null when nothing was received.
         */
        fun powerOkAtDrop(health: P.HealthPower?, healthAgeMs: Long?, metrics: MeshtasticSerial.DeviceMetrics?, metricsAgeMs: Long?): Boolean {
            if (health != null && healthAgeMs != null && healthAgeMs in 0..POWER_HEALTH_FRESH_MS) {
                val f = health.powerFlags
                val bad = f and (P.PWR_VDD_LOW or P.PWR_POF or P.PWR_VBUS_LOST) != 0
                if (bad) return false
                if (f and P.PWR_VBUS != 0 || health.vbatMv >= POWER_OK_VBAT_MV) return true
            }
            if (metrics != null && metricsAgeMs != null && metricsAgeMs in 0..POWER_METRICS_FRESH_MS) {
                val level = metrics.batteryLevel
                if (level != null && level >= POWER_OK_LEVEL) return true
            }
            return false
        }
        const val RESTARTED = "Desk Pod restarted"
        /** A Desk Pod HEALTH with uptime below this soon after a drop: it restarted. */
        const val RESTART_UPTIME_S = 60L
        /** How long after a drop a low uptime counts as a restart. */
        const val RESTART_AFTER_DROP_MS = 10 * 60_000L
        /** The map chip shows the restart this long. */
        const val RESTART_SHOW_MS = 60_000L

        /** "Desk Pod restarted (power-on)"; just "Desk Pod restarted" when the build sends no reset_reason. */
        fun restartText(resetReason: Long?): String =
            if (resetReason == null) RESTARTED else "$RESTARTED (${P.resetReasonNames(resetReason)})"

        /**
         * The Desk Pod stopped answering (as opposed to a session this app closed, a refused
         * bond, the wrong device, or the Desk Pod closing the link on purpose). Reasons are the
         * Bluetooth links' sessionLost texts (Android and iPad).
         */
        fun isSilentDrop(reason: String): Boolean {
            val r = reason.lowercase()
            if (NOT_SILENT.any { it in r }) return false
            return SILENT_MARKERS.any { it in r }
        }

        private val SILENT_MARKERS = listOf(
            "timed out", "got no answer", "no connection after", "disconnected", "write failed", "read failed",
            "notifications could not be enabled", "connection failed",
        )
        private val NOT_SILENT = listOf(
            "not bonded", "not paired", "lost its pairing", "not a desk pod", "does not offer", "refused", "replaced", "usb", "turned off",
            "bluetooth state", "(status 19)", "(status 22)",
        )

        /** "Desk Pod battery: 87%, 3.95 V" / "Desk Pod power: external (battery_level 101), 5.02 V"; null with no fields. */
        fun metricsLine(m: MeshtasticSerial.DeviceMetrics): String? {
            val v = m.voltage?.let { "%.2f V".format(java.util.Locale.US, it) }
            val level = m.batteryLevel
            return when {
                level != null && level > 100 -> "Desk Pod power: external (battery_level $level)" + (v?.let { ", $it" } ?: "")
                level != null -> "Desk Pod battery: $level%" + (v?.let { ", $it" } ?: "")
                v != null -> "Desk Pod voltage: $v"
                else -> null
            }
        }
    }
}

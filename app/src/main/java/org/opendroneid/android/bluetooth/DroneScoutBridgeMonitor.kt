package org.opendroneid.android.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DroneScoutBridgeSignal(
    val rssiDbm: Int,
    val lastSeenMonotonicMs: Long,
    val eventCount: Long,
)

/** Tracks the synthetic Basic ID emitted when DroneScout Relay ping is enabled. */
object DroneScoutBridgeMonitor {
    const val LOSS_ANNOUNCEMENT_AFTER_MS = 32_000L
    // Retain the most recent measured RSSI throughout the same interval used to decide that
    // the bridge is absent. A normal relay-ping delay must not flash the bars off prematurely.
    const val SIGNAL_STALE_AFTER_MS = LOSS_ANNOUNCEMENT_AFTER_MS
    private const val DEFAULT_RELAY_PING_IDENTITY = "DRONESCOUTBRIDGE"

    private val _signal = MutableStateFlow<DroneScoutBridgeSignal?>(null)
    val signal: StateFlow<DroneScoutBridgeSignal?> = _signal.asStateFlow()
    private val _audioMuted = MutableStateFlow(false)
    val audioMuted: StateFlow<Boolean> = _audioMuted.asStateFlow()
    private var bridgeEventCount = 0L

    @JvmStatic
    @JvmOverloads
    fun noteCandidate(
        identity: String?,
        rssiDbm: Int,
        nowMonotonicMs: Long = System.nanoTime() / 1_000_000L,
    ) {
        if (!isRelayPingIdentity(identity)) return
        noteConfirmedBridgePacket(rssiDbm, nowMonotonicMs)
    }

    /** Refresh bridge health from any packet positively identified by its DroneScout Self ID. */
    @JvmStatic
    @JvmOverloads
    fun noteRelayedPacket(
        rssiDbm: Int,
        nowMonotonicMs: Long = System.nanoTime() / 1_000_000L,
    ): Long {
        return noteConfirmedBridgePacket(rssiDbm, nowMonotonicMs)
    }

    @JvmStatic
    fun isRelayPingIdentity(identity: String?): Boolean {
        val normalized = identity
            ?.uppercase()
            ?.filter(Char::isLetterOrDigit)
            .orEmpty()
        return normalized.startsWith(DEFAULT_RELAY_PING_IDENTITY)
    }

    fun currentRssi(
        signal: DroneScoutBridgeSignal?,
        nowMonotonicMs: Long,
        staleAfterMs: Long = SIGNAL_STALE_AFTER_MS,
    ): Int? {
        if (signal == null) return null
        // Compose's display clock is refreshed periodically. A packet can arrive after that
        // snapshot, making its timestamp slightly newer than nowMonotonicMs. It is fresh, not a
        // clock reversal; clamping prevents the Bridge RSSI indicator from flashing blank.
        val ageMs = (nowMonotonicMs - signal.lastSeenMonotonicMs).coerceAtLeast(0L)
        return signal.rssiDbm.takeIf { ageMs <= staleAfterMs }
    }

    fun toggleAudioMuted() {
        _audioMuted.value = !_audioMuted.value
    }

    fun setAudioMuted(muted: Boolean) {
        _audioMuted.value = muted
    }

    internal fun setAudioMutedForTests(muted: Boolean) = setAudioMuted(muted)

    internal fun resetForTests() {
        _signal.value = null
        _audioMuted.value = false
        bridgeEventCount = 0
    }

    @Synchronized
    private fun noteConfirmedBridgePacket(rssiDbm: Int, nowMonotonicMs: Long): Long {
        bridgeEventCount += 1
        _signal.value = DroneScoutBridgeSignal(
            rssiDbm = rssiDbm,
            lastSeenMonotonicMs = nowMonotonicMs,
            eventCount = bridgeEventCount,
        )
        return bridgeEventCount
    }
}

internal class DroneScoutBridgeLossAnnouncementGate(
    private val thresholdMs: Long = DroneScoutBridgeMonitor.LOSS_ANNOUNCEMENT_AFTER_MS,
) {
    private var monitoringStartedAtMs: Long? = null
    private var lossActive = false

    fun shouldAnnounce(
        monitoringActive: Boolean,
        lastPingAtMs: Long?,
        nowMs: Long,
        muted: Boolean,
    ): Boolean {
        if (!monitoringActive) {
            monitoringStartedAtMs = null
            lossActive = false
            return false
        }

        val startedAt = monitoringStartedAtMs ?: nowMs.also { monitoringStartedAtMs = it }
        val baseline = maxOf(startedAt, lastPingAtMs ?: startedAt)
        val missing = nowMs - baseline > thresholdMs
        if (!missing) {
            lossActive = false
            return false
        }
        if (lossActive) return false
        lossActive = true
        return !muted
    }
}

/**
 * Produces one diagnostic record when the bridge meter first appears and whenever it changes
 * between visible and blank. RSSI fluctuations while the meter remains visible are intentionally
 * suppressed so a field log remains readable.
 */
internal class DroneScoutBridgeStatusLogGate {
    private var lastAvailability: Boolean? = null

    fun transitionMessage(
        surface: String,
        signal: DroneScoutBridgeSignal?,
        nowMonotonicMs: Long,
    ): String? {
        val ageMs = signal?.let {
            (nowMonotonicMs - it.lastSeenMonotonicMs).coerceAtLeast(0L)
        }
        val rssi = DroneScoutBridgeMonitor.currentRssi(signal, nowMonotonicMs)
        val available = rssi != null
        if (lastAvailability == available) return null
        lastAvailability = available

        val reason = when {
            signal == null -> "no_signal"
            ageMs == null -> "no_signal"
            ageMs > DroneScoutBridgeMonitor.SIGNAL_STALE_AFTER_MS -> "stale"
            else -> "fresh"
        }
        return "surface=$surface state=${if (available) "visible" else "blank"} " +
            "rssi=${rssi ?: signal?.rssiDbm ?: "none"} ageMs=${ageMs ?: "none"} " +
            "eventCount=${signal?.eventCount ?: 0L} reason=$reason " +
            "staleAfterMs=${DroneScoutBridgeMonitor.SIGNAL_STALE_AFTER_MS}"
    }

    fun reset() {
        lastAvailability = null
    }
}

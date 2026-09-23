package org.ncssar.rid2caltopo.notam

import java.util.Locale
import org.ncssar.rid2caltopo.airspace.OperatingArea

internal object NotamPolicy {
    private fun severityRank(severity: NotamChipSeverity): Int = when (severity) {
        NotamChipSeverity.Danger -> 0
        NotamChipSeverity.Caution -> 1
        NotamChipSeverity.Normal -> 2
        NotamChipSeverity.Neutral -> 3
    }

    fun isStale(lastSuccessfulAtMs: Long, nowMs: Long, refreshIntervalSeconds: Int): Boolean =
        lastSuccessfulAtMs > 0L && nowMs - lastSuccessfulAtMs >
            maxOf(600_000L, refreshIntervalSeconds.toLong().coerceAtLeast(30L) * 2_000L)

    fun sort(notices: List<NearbyNotam>): List<NearbyNotam> {
        return notices.sortedWith(
            compareBy<NearbyNotam>(
                { !it.intersectsPilotBubble },
                { severityRank(it.severity) },
                { it.distanceNm ?: Double.MAX_VALUE },
                { it.title }
            )
        )
    }

    fun filterWithinRadius(
        notices: List<NearbyNotam>,
        radiusStatuteMiles: Int
    ): Pair<List<NearbyNotam>, Int> {
        val radiusNm = OperatingArea.statuteMilesToNauticalMiles(radiusStatuteMiles.toDouble())
        val visible = notices.filter { notice ->
            notice.intersectsPilotBubble ||
                notice.distanceNm == null ||
                notice.distanceNm <= radiusNm
        }
        return visible to (notices.size - visible.size)
    }

    fun effectiveChipSeverity(
        notices: List<NearbyNotam>,
        configured: Boolean,
        hasError: Boolean
    ): NotamChipSeverity {
        return when {
            hasError && notices.isEmpty() -> NotamChipSeverity.Neutral
            notices.any { it.severity == NotamChipSeverity.Danger } -> NotamChipSeverity.Danger
            notices.any { it.intersectsPilotBubble || it.severity == NotamChipSeverity.Caution } -> NotamChipSeverity.Caution
            configured -> NotamChipSeverity.Normal
            else -> NotamChipSeverity.Neutral
        }
    }

    fun chipLabel(
        notices: List<NearbyNotam>,
        configured: Boolean,
        loading: Boolean,
        hasError: Boolean
    ): String {
        if (loading) return "NOTAMs updating..."
        if (!configured) return "NOTAMs not configured"
        if (hasError && notices.isEmpty()) return "NOTAMs unavailable"
        if (hasError) return "NOTAMs stale • review"

        val restrictiveHere = notices.firstOrNull {
            it.intersectsPilotBubble && it.severity == NotamChipSeverity.Danger
        }
        if (restrictiveHere != null) {
            return "NOTAMs: RESTRICTED ${formatDistance(restrictiveHere)}"
        }

        val noticeHere = notices.firstOrNull { it.intersectsPilotBubble }
        if (noticeHere != null) {
            return "NOTAMs: NOTICE ${formatDistance(noticeHere)}"
        }

        if (notices.isNotEmpty()) return "NOTAMs: ${notices.size} nearby"
        return if (configured) "No notices returned" else "NOTAMs not configured"
    }

    private fun formatDistance(notice: NearbyNotam): String =
        notice.distanceNm?.let {
            String.format(Locale.US, "%.1f mi", it / OperatingArea.radiusNm)
        } ?: "pilot area"
}

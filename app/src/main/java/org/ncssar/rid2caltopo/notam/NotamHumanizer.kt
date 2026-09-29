package org.ncssar.rid2caltopo.notam

import org.ncssar.rid2caltopo.airspace.OperatingArea
import java.util.Locale

internal data class HumanizedNotam(
    val title: String,
    val summary: String,
    val details: String
)

internal object NotamHumanizer {
    private val radiusRegex = Regex("""\b([0-9]+(?:\.[0-9]+)?)NM RADIUS\b""")
    private val scheduleRegex = Regex("""\b(DLY|DAILY|MON|TUE|WED|THU|FRI|SAT|SUN|SR-SS)\b.*""")

    fun humanize(
        reference: String,
        notamText: String,
        rawText: String,
        effectiveText: String,
        proximityText: String,
        intersectsPilotBubble: Boolean,
        horizontalIntersectsPilotBubble: Boolean,
        verticallyIntersectsPilotBand: Boolean?,
        classification: String,
        scheduleText: String
    ): HumanizedNotam {
        val sourceText = listOf(notamText, rawText).firstOrNull { it.isNotBlank() }.orEmpty()
        val category = categoryFor(sourceText)
        val title = buildString {
            append(category)
            if (reference.isNotBlank()) {
                append(" (${reference.trim()})")
            }
        }

        val summaryParts = mutableListOf<String>()
        serviceAvailabilitySummary(sourceText)?.let(summaryParts::add)
        when {
            intersectsPilotBubble -> summaryParts += "Intersects the pilot's ${OperatingArea.displayLabel}."
            horizontalIntersectsPilotBubble && verticallyIntersectsPilotBand == false ->
                summaryParts += "Overlaps the pilot's ${OperatingArea.displayLabel} horizontally, but stays above the 400 ft AGL operating ceiling."
            proximityText.isNotBlank() -> summaryParts += "$proximityText from current location."
        }
        areaSummary(sourceText)?.let(summaryParts::add)
        altitudeSummary(sourceText)?.let(summaryParts::add)
        if (summaryParts.isEmpty()) {
            summaryParts += "See details for the full FAA notice."
        }

        val detailParts = mutableListOf<String>()
        areaDetails(sourceText)?.let(detailParts::add)
        altitudeSummary(sourceText)?.let { detailParts += "Altitude: $it" }
        scheduleSummary(scheduleText, sourceText)?.let { detailParts += "Schedule: $it" }
        effectiveText.takeIf { it.isNotBlank() }?.let(detailParts::add)
        classification.takeIf { it.isNotBlank() }?.let { detailParts += "Classification $it" }
        if (detailParts.isEmpty()) {
            detailParts += "FAA notice is available below in the raw text section."
        }

        return HumanizedNotam(
            title = title,
            summary = summaryParts.joinToString(" "),
            details = detailParts.joinToString("\n")
        )
    }

    private fun serviceAvailabilitySummary(text: String): String? {
        val upper = text.uppercase(Locale.US)
        if (!upper.contains("MAY NOT BE AVBL")) return null
        val services = buildList {
            if (upper.contains("ADS-B")) add("ADS-B")
            if (upper.contains("ADS-R")) add("ADS-R rebroadcast")
            if (upper.contains("TIS-B")) add("traffic information (TIS-B)")
            if (upper.contains("FIS-B")) add("flight information (FIS-B)")
        }
        return services.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let {
            "$it services may be unavailable during the effective period."
        }
    }

    private fun categoryFor(text: String): String {
        val haystack = text.uppercase(Locale.US)
        return when {
            haystack.contains("AIRSPACE") && isServiceAvailabilityNotice(haystack) -> "Airspace service notice"
            haystack.contains("AIRSPACE UAS") -> "UAS airspace restriction"
            haystack.contains(" TFR") || haystack.startsWith("TFR") -> "Temporary flight restriction"
            haystack.contains("AIRSPACE") && isRestrictiveAirspaceNotice(haystack) -> "Airspace restriction"
            haystack.contains("AIRSPACE") -> "Airspace notice"
            haystack.contains("RWY") && (haystack.contains("CLSD") || haystack.contains("CLOSED")) -> "Runway closure"
            haystack.contains("RWY") -> "Runway notice"
            haystack.contains("NAV") -> "Navigation notice"
            haystack.contains("OBST") -> "Obstacle notice"
            else -> "Operational notice"
        }
    }

    private fun isServiceAvailabilityNotice(haystack: String): Boolean =
        haystack.contains("UNUSABLE") ||
            haystack.contains("UNAVAILABLE") ||
            haystack.contains("UNAVBL") ||
            haystack.contains("NOT AVBL") ||
            haystack.contains("MAY NOT BE AVBL") ||
            haystack.contains("ADS-B") ||
            haystack.contains("TIS-B") ||
            haystack.contains("FIS-B")

    private fun isRestrictiveAirspaceNotice(haystack: String): Boolean =
        haystack.contains("RESTRICT") ||
            haystack.contains("PROHIBITED") ||
            haystack.contains("AIRSPACE UAS") ||
            haystack.contains("TEMPORARY FLIGHT RESTRICTION")

    private fun areaSummary(text: String): String? {
        val normalized = text.uppercase(Locale.US)
        return when {
            radiusRegex.containsMatchIn(normalized) -> {
                val radius = radiusRegex.find(normalized)?.groupValues?.getOrNull(1) ?: return null
                "Applies inside a $radius NM radius area."
            }
            normalized.contains("WI AN AREA DEFINED AS") || normalized.contains("WI AREA DEFINED AS") ->
                "Applies inside a polygon-defined area."
            normalized.contains("WI") && normalized.contains("AREA") ->
                "Applies inside a defined area."
            else -> null
        }
    }

    private fun areaDetails(text: String): String? {
        val normalized = text.uppercase(Locale.US)
        val firstReference = Regex("""\(([0-9.]+NM [A-Z]{1,3} [A-Z0-9]{2,5})\)""").find(normalized)?.groupValues?.getOrNull(1)
        return when {
            radiusRegex.containsMatchIn(normalized) -> {
                val radius = radiusRegex.find(normalized)?.groupValues?.getOrNull(1) ?: return null
                val nearFix = firstReference?.let { " centered near $it" }.orEmpty()
                "Area: $radius NM radius$nearFix."
            }
            normalized.contains("WI AN AREA DEFINED AS") || normalized.contains("WI AREA DEFINED AS") -> {
                val nearFix = firstReference?.let { " First reference point: $it." }.orEmpty()
                "Area: Polygon boundary defined by FAA coordinates.$nearFix"
            }
            else -> null
        }
    }

    private fun altitudeSummary(text: String): String? {
        val altitudeBand = NotamAltitudeParser.parse(text) ?: return null
        return buildString {
            append(formatAltitudeFloor(altitudeBand.floorLabel))
            append(" to ")
            append(formatAltitudeCeiling(altitudeBand.ceilingLabel))
            altitudeBand.reference?.takeIf { it.isNotBlank() }?.let { units ->
                append(" ")
                append(units)
            }
        }
    }

    private fun scheduleSummary(scheduleText: String, sourceText: String): String? {
        if (scheduleText.isNotBlank()) return scheduleText.trim()
        return scheduleRegex.find(sourceText.uppercase(Locale.US))?.value?.trim()
    }

    private fun formatAltitudeFloor(raw: String): String =
        if (raw == "SFC") "surface" else raw

    private fun formatAltitudeCeiling(raw: String): String =
        if (raw.endsWith("FT")) raw.removeSuffix("FT") + " ft" else raw
}

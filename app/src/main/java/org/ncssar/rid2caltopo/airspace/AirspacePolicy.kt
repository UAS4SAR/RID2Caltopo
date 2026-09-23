package org.ncssar.rid2caltopo.airspace

import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

object AirspacePolicy {
    fun buildUiState(
        records: List<FaaUasFacilityMapRecord>,
        loading: Boolean,
        errorMessage: String?,
        pilotCoordinate: AirspaceCoordinate? = null
    ): AirspaceUiState {
        if (errorMessage != null && records.isNotEmpty()) {
            return buildUiState(records, false, null, pilotCoordinate).copy(
                chipLabel = "Airspace stale • review", errorMessage = errorMessage
            )
        }
        if (loading) {
            return AirspaceUiState(
                loading = true,
                chipSeverity = AirspaceChipSeverity.Neutral,
                chipLabel = "Airspace updating...",
                records = records
            )
        }
        if (errorMessage != null && records.isEmpty()) {
            return AirspaceUiState(
                chipSeverity = AirspaceChipSeverity.Neutral,
                chipLabel = "Airspace unavailable",
                detail = errorMessage,
                errorMessage = errorMessage
            )
        }
        val controlled = records.filter { it.airspaceClasses.isNotEmpty() }
        if (controlled.isNotEmpty()) {
            val containing = pilotCoordinate?.let { coordinate ->
                controlled.filter { contains(it, coordinate) }
            }.orEmpty()
            if (containing.isEmpty()) {
                return nearbyState(
                    records = records,
                    controlled = controlled,
                    pilotCoordinate = pilotCoordinate,
                    errorMessage = errorMessage
                )
            }
            // FAA guidance says an operation spanning multiple UASFM grids must use the
            // lowest published altitude. ArcGIS result order is not deterministic.
            val representative = representative(containing)
            val airport = shortAirportName(representative.primaryAirportName)
            val classText = containing
                .flatMap { it.airspaceClasses }
                .distinct()
                .sorted()
                .joinToString("/") { "Class $it" }
            val gridLimit = containing.mapNotNull { it.ceilingFeet }.minOrNull()
            val gridLimitText = gridLimit?.let { "; FAA grid limit $it ft AGL" }.orEmpty()
            val coordinationText = gridLimit?.let {
                " The displayed $it ft AGL value is the lowest FAA UAS Facility Map limit across the area, not the top of the controlled-airspace class. Requests above it require further FAA coordination."
            }.orEmpty()
            return AirspaceUiState(
                chipSeverity = AirspaceChipSeverity.Danger,
                chipLabel = "Airspace: Authorization required - $airport $classText$gridLimitText",
                summary = "$airport $classText",
                detail = "The current location is inside an FAA UAS Facility Map grid identified as $classText. FAA authorization is required before flight.$coordinationText",
                records = records,
                errorMessage = errorMessage
            )
        }
        return AirspaceUiState(
            chipSeverity = AirspaceChipSeverity.Normal,
            chipLabel = if (records.isEmpty()) "No facility grids returned" else "Facility grids: review",
            summary = "No classified FAA facility-map grids returned for the ${OperatingArea.displayLabel}. This is not a complete airspace assessment.",
            records = records,
            errorMessage = errorMessage
        )
    }

    private fun nearbyState(
        records: List<FaaUasFacilityMapRecord>,
        controlled: List<FaaUasFacilityMapRecord>,
        pilotCoordinate: AirspaceCoordinate?,
        errorMessage: String?
    ): AirspaceUiState {
        val nearest = pilotCoordinate?.let { coordinate ->
            controlled.mapNotNull { record ->
                distanceStatuteMiles(record, coordinate)?.let { distance -> record to distance }
            }.minByOrNull { it.second }
        }
        val representative = nearest?.first ?: representative(controlled)
        val airport = shortAirportName(representative.primaryAirportName)
        val classes = representative.airspaceClasses
            .distinct()
            .sorted()
            .joinToString("/") { "Class $it" }
        val distanceText = nearest?.second?.let {
            String.format(Locale.US, " %.1f mi", it)
        }.orEmpty()
        val proximity = nearest?.second?.let {
            " The nearest $airport $classes facility-map grid is approximately " +
                "${String.format(Locale.US, "%.1f", it)} statute miles away and intersects the " +
                "${OperatingArea.displayLabel}."
        } ?: " A controlled-airspace facility-map grid intersects the ${OperatingArea.displayLabel}, but its boundary could not be compared with the current position."
        return AirspaceUiState(
            chipSeverity = AirspaceChipSeverity.Caution,
            chipLabel = "Airspace nearby - $airport $classes$distanceText",
            summary = "$airport $classes nearby",
            detail = "No FAA UAS Facility Map grid covers the current location.$proximity " +
                "FAA authorization is required only if the planned operation enters controlled airspace. " +
                "Verify the full planned area in an FAA-approved planning source.",
            records = records,
            errorMessage = errorMessage
        )
    }

    private fun representative(records: List<FaaUasFacilityMapRecord>): FaaUasFacilityMapRecord =
        records.minWithOrNull(
            compareBy<FaaUasFacilityMapRecord>(
                { it.ceilingFeet ?: Int.MAX_VALUE },
                { it.primaryAirportName },
                { it.objectId }
            )
        )!!

    private fun contains(
        record: FaaUasFacilityMapRecord,
        coordinate: AirspaceCoordinate
    ): Boolean = record.rings.count { pointInRing(it, coordinate) } % 2 == 1

    private fun pointInRing(
        ring: List<AirspaceCoordinate>,
        point: AirspaceCoordinate
    ): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var previous = ring.last()
        for (current in ring) {
            val crossesLatitude = (current.latitude > point.latitude) !=
                (previous.latitude > point.latitude)
            if (crossesLatitude) {
                val crossingLongitude = (previous.longitude - current.longitude) *
                    (point.latitude - current.latitude) /
                    (previous.latitude - current.latitude) + current.longitude
                if (point.longitude < crossingLongitude) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun distanceStatuteMiles(
        record: FaaUasFacilityMapRecord,
        point: AirspaceCoordinate
    ): Double? {
        val longitudeMiles = 69.172 * cos(Math.toRadians(point.latitude))
        var minimum: Double? = null
        for (ring in record.rings) {
            if (ring.size < 2) continue
            for (index in ring.indices) {
                val start = ring[index]
                val end = ring[(index + 1) % ring.size]
                val ax = (start.longitude - point.longitude) * longitudeMiles
                val ay = (start.latitude - point.latitude) * 69.0
                val bx = (end.longitude - point.longitude) * longitudeMiles
                val by = (end.latitude - point.latitude) * 69.0
                val dx = bx - ax
                val dy = by - ay
                val denominator = dx * dx + dy * dy
                val t = if (denominator == 0.0) 0.0 else
                    (-(ax * dx + ay * dy) / denominator).coerceIn(0.0, 1.0)
                val distance = hypot(ax + t * dx, ay + t * dy)
                minimum = minimum?.coerceAtMost(distance) ?: distance
            }
        }
        return minimum
    }

    private fun shortAirportName(name: String): String =
        name.substringBefore(" (").ifBlank { "Controlled airspace" }
}

package org.ncssar.rid2caltopo.notam

data class NotamLatLng(
    val latitude: Double,
    val longitude: Double
)

sealed interface NotamGeometry {
    data class Point(val coordinate: NotamLatLng) : NotamGeometry
    data class Line(val coordinates: List<NotamLatLng>) : NotamGeometry
    data class Polygon(val rings: List<List<NotamLatLng>>) : NotamGeometry
    data class Collection(val geometries: List<NotamGeometry>) : NotamGeometry
}

enum class NotamChipSeverity {
    Neutral,
    Normal,
    Caution,
    Danger
}

data class NotamAltitudeBand(
    val floorFeetMsl: Double?,
    val ceilingFeetMsl: Double?,
    val floorLabel: String,
    val ceilingLabel: String,
    val reference: String?
)

data class NearbyNotam(
    val id: String,
    val title: String,
    val summary: String,
    val distanceNm: Double?,
    val bearingText: String? = null,
    val proximityText: String = "",
    val intersectsPilotBubble: Boolean = false,
    val horizontalIntersectsPilotBubble: Boolean = false,
    val verticallyIntersectsPilotBand: Boolean? = null,
    val effectiveText: String = "",
    val details: String = "",
    val rawText: String = "",
    val rawTitle: String = "",
    val rawReference: String = "",
    val updateType: String = "",
    val cancelationDate: String = "",
    val lastUpdated: String = "",
    val severity: NotamChipSeverity = NotamChipSeverity.Normal,
    val altitudeBand: NotamAltitudeBand? = null,
    val geometries: List<NotamGeometry> = emptyList()
)

data class NotamUiState(
    val visible: Boolean = false,
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val loading: Boolean = false,
    val stale: Boolean = false,
    val chipSeverity: NotamChipSeverity = NotamChipSeverity.Neutral,
    val chipLabel: String = "NOTAMs unavailable",
    val statusLine: String = "",
    val lastUpdatedText: String? = null,
    val queryLatitude: Double? = null,
    val queryLongitude: Double? = null,
    val radiusStatuteMiles: Int = 1,
    val notices: List<NearbyNotam> = emptyList(),
    val suppressedNoticeCount: Int = 0,
    val nearestHiddenNotice: NearbyNotam? = null,
    val errorMessage: String? = null
)

/** Coordinates that can actually be rendered; never substitute the query location. */
fun NearbyNotam.mapCoordinates(): List<NotamLatLng> {
    fun coordinates(geometry: NotamGeometry): List<NotamLatLng> = when (geometry) {
        is NotamGeometry.Point -> listOf(geometry.coordinate)
        is NotamGeometry.Line -> geometry.coordinates
        is NotamGeometry.Polygon -> geometry.rings.firstOrNull().orEmpty()
        is NotamGeometry.Collection -> geometry.geometries.flatMap(::coordinates)
    }
    return geometries.flatMap(::coordinates).filter {
        it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
    }.distinct()
}

data class NotamMapFocusRequest(
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val coordinates: List<NotamLatLng>
)

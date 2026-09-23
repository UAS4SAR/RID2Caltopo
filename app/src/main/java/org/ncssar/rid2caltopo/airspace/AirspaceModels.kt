package org.ncssar.rid2caltopo.airspace

enum class AirspaceChipSeverity {
    Neutral,
    Normal,
    Caution,
    Danger
}

data class FaaUasFacilityMapRecord(
    val objectId: Long,
    val ceilingFeet: Int?,
    val unit: String,
    val primaryAirportFaaId: String,
    val primaryAirportIcao: String,
    val primaryAirportName: String,
    val laancAvailable: Boolean,
    val airspaceClasses: List<String>,
    val rings: List<List<AirspaceCoordinate>> = emptyList()
)

data class AirspaceCoordinate(
    val latitude: Double,
    val longitude: Double
)

data class AirspaceUiState(
    val visible: Boolean = true,
    val loading: Boolean = false,
    val chipSeverity: AirspaceChipSeverity = AirspaceChipSeverity.Neutral,
    val chipLabel: String = "Airspace pending",
    val summary: String = "",
    val detail: String = "",
    val records: List<FaaUasFacilityMapRecord> = emptyList(),
    val errorMessage: String? = null,
    val lastSuccessfulCheck: String = "No successful check this session",
    val queryCoordinate: AirspaceCoordinate? = null
)

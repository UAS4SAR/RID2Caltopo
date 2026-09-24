package org.ncssar.rid2caltopo.landrestrictions

data class LandCoordinate(
    val latitude: Double,
    val longitude: Double
)

enum class LandAgency(
    val displayName: String,
    val rulesUrl: String
) {
    NationalParkService(
        "National Park Service",
        "https://www.nps.gov/orgs/aviationprogram/uncrewed-aircraft-systems.htm"
    ),
    FishAndWildlifeService(
        "U.S. Fish and Wildlife Service",
        "https://www.fws.gov/law/uncrewed-aircraft-systems"
    ),
    ForestService(
        "U.S. Forest Service",
        "https://www.fs.usda.gov/Internet/FSE_DOCUMENTS/stelprd3847000.pdf"
    ),
    ColoradoParksAndWildlife(
        "Colorado Parks and Wildlife",
        "https://cpw.state.co.us/rules-and-regulations"
    )
}

enum class LandRule(val label: String) {
    LaunchLandOperateRestricted("Launch, landing, or operation restricted"),
    WildlifeDisturbanceRestricted("Launch, landing, and wildlife disturbance restricted"),
    PropertySpecificRules("Property-specific rules—verify authorization")
}

enum class LandRestrictionSeverity {
    Neutral,
    Normal,
    Caution,
    Danger
}

data class LandRestrictionArea(
    val id: String,
    val name: String,
    val agency: LandAgency,
    val rule: LandRule,
    val polygons: List<List<List<LandCoordinate>>>,
    val intersectsOperatingArea: Boolean,
    val containsOperator: Boolean,
    val distanceNm: Double,
    val detailsUrl: String? = null
)

data class LandRestrictionSource(
    val id: String,
    val queryEndpoint: String,
    val agency: LandAgency,
    val rule: LandRule,
    val nameFields: List<String>,
    val identifierFields: List<String>,
    val detailsUrlFields: List<String> = emptyList(),
    val whereClause: String = "1=1"
)

data class LandRestrictionUiState(
    val visible: Boolean = false,
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val stale: Boolean = false,
    val severity: LandRestrictionSeverity = LandRestrictionSeverity.Neutral,
    val chipLabel: String = "Land rules pending",
    val statusLine: String = "Waiting for location",
    val lastUpdatedEpochMs: Long? = null,
    val areas: List<LandRestrictionArea> = emptyList(),
    val sourceErrors: List<String> = emptyList()
)

internal fun disabledLandRestrictionUiState(): LandRestrictionUiState =
    LandRestrictionUiState(
        visible = true,
        enabled = false,
        chipLabel = "Land rules off",
        statusLine = "Protected-land checks are disabled."
    )

object LandRestrictionPolicy {
    fun severity(
        areas: List<LandRestrictionArea>,
        hasError: Boolean,
        waitingForLocation: Boolean = false
    ): LandRestrictionSeverity = when {
        waitingForLocation -> LandRestrictionSeverity.Neutral
        areas.any { it.containsOperator && it.rule != LandRule.PropertySpecificRules } ->
            LandRestrictionSeverity.Danger
        areas.any { it.intersectsOperatingArea } -> LandRestrictionSeverity.Caution
        hasError -> LandRestrictionSeverity.Neutral
        else -> LandRestrictionSeverity.Normal
    }

    fun chipLabel(
        areas: List<LandRestrictionArea>,
        loading: Boolean,
        hasError: Boolean,
        waitingForLocation: Boolean = false
    ): String = when {
        loading -> "Land rules updating…"
        waitingForLocation -> "Land rules pending"
        areas.any { it.containsOperator && it.rule != LandRule.PropertySpecificRules } ->
            "Land rules: RESTRICTED"
        areas.count { it.intersectsOperatingArea } > 0 ->
            "Land rules: ${areas.count { it.intersectsOperatingArea }} nearby"
        hasError -> "Land rules unavailable"
        else -> "No mapped restrictions"
    }
}

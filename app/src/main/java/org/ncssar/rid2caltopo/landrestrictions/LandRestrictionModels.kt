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
    );

    val dataCredit: String get() = when (this) {
        NationalParkService -> "National Park Service, Land Resources Division — park unit boundaries"
        FishAndWildlifeService -> "U.S. Fish and Wildlife Service, National Wildlife Refuge System, Division of Realty"
        ForestService -> "USDA Forest Service, Automated Lands Program (ALP) — National Wilderness Areas"
        ColoradoParksAndWildlife -> "Colorado Parks and Wildlife — administrative boundaries"
    }

    val dataNotice: String get() = when (this) {
        NationalParkService -> "Advisory GIS data, not survey-quality or legal boundary documents. A legislated park boundary does not establish NPS ownership or an interest in every parcel; NPS may also hold interests outside it. Data may change or be incomplete. Verify ownership, current rules and authorization with the responsible agency. NPS provides no accuracy or completeness warranty."
        FishAndWildlifeService -> "Simplified mapping boundaries are not legal land descriptions or proof of public access. Consult the refuge and source metadata for current access and data limitations. No accuracy, reliability or completeness warranty is provided."
        ForestService -> "For general mapping and analysis. These changing geospatial data are not legal documents and must not be used to determine title, ownership, legal descriptions or boundaries, jurisdiction, or restrictions on public or private land. Verify current rules with the responsible agency. No accuracy, reliability, completeness or fitness warranty is provided."
        ColoradoParksAndWildlife -> "CPW provides these data as is without warranty. Interpret them with accompanying documentation and verify property access and current rules with CPW. The Colorado Department of Natural Resources disclaims liability for their use."
    }

    val dataMetadataUrl: String get() = when (this) {
        NationalParkService -> "https://www.arcgis.com/home/item.html?id=a2848257cf5541338bab9b656440bd0c"
        FishAndWildlifeService -> "https://www.arcgis.com/home/item.html?id=745ed874c1394da3a9aae50267c9e049"
        ForestService -> "https://data.fs.usda.gov/geodata/edw/edw_resources/meta/S_USA.Wilderness.xml"
        ColoradoParksAndWildlife -> "https://www.arcgis.com/home/item.html?id=168fccb0583f42f1afe57de6c9ce846d"
    }
}

enum class LandRule(val label: String) {
    LaunchLandOperateRestricted("Mapped protected land—verify current rules"),
    MappedWildernessAdvisory("Mapped wilderness—verify current rules"),
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
    val whereClause: String = "1=1",
    val cacheRevision: Int = 1
) {
    val cacheFileName: String get() = if (cacheRevision == 1) "$id.geojson" else "$id-v$cacheRevision.geojson"
}

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
            "Mapped land: verify rules"
        areas.count { it.intersectsOperatingArea } > 0 ->
            "Land rules: ${areas.count { it.intersectsOperatingArea }} nearby"
        hasError -> "Land rules unavailable"
        else -> "No mapped protected land"
    }
}

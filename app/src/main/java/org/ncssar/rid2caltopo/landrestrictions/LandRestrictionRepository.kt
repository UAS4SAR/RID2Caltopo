package org.ncssar.rid2caltopo.landrestrictions

import android.content.Context
import android.location.Location
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ncssar.rid2caltopo.airspace.OperatingArea

data class LandRestrictionFetchResult(
    val areas: List<LandRestrictionArea>,
    val sourceErrors: List<String>,
    val newestDataEpochMs: Long?,
    val oldestDataEpochMs: Long?
)

class LandRestrictionRepository(
    context: Context,
    private val client: OkHttpClient = defaultClient
) {
    private val cacheDirectory = File(context.applicationContext.filesDir, "land-restrictions")


    suspend fun fetch(location: Location, radiusStatuteMiles: Int): LandRestrictionFetchResult = withContext(Dispatchers.IO) {
        org.ncssar.rid2caltopo.storage.OperationalStorage.requireFor(cacheDirectory)
        cacheDirectory.mkdirs()
        val center = LandCoordinate(location.latitude, location.longitude)
        val areas = mutableListOf<LandRestrictionArea>()
        val errors = mutableListOf<String>()
        val dataTimes = mutableListOf<Long>()
        sources.forEach { source ->
            try {
                val payload = fetchPayload(buildQueryUrl(source, center, radiusStatuteMiles.toDouble()))
                val now = System.currentTimeMillis()
                writeCache(source, payload, now)
                areas += LandRestrictionParser.parse(payload, source, center, OperatingArea.radiusNm)
                dataTimes += now
            } catch (networkError: Exception) {
                val cached = readCache(source)
                if (cached != null) {
                    runCatching {
                        areas += LandRestrictionParser.parse(cached.payload, source, center, OperatingArea.radiusNm)
                        dataTimes += cached.updatedEpochMs
                        errors += "${source.agency.displayName}: using cached boundaries (${networkError.message.orEmpty()})"
                    }.onFailure { parseError ->
                        errors += "${source.agency.displayName}: ${parseError.message ?: networkError.message ?: "unavailable"}"
                    }
                } else {
                    errors += "${source.agency.displayName}: ${networkError.message ?: "unavailable"}"
                }
            }
        }
        LandRestrictionFetchResult(
            areas = areas.distinctBy { it.id }.sortedWith(
                compareByDescending<LandRestrictionArea> { it.containsOperator }
                    .thenBy { it.distanceNm }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ),
            sourceErrors = errors,
            newestDataEpochMs = dataTimes.maxOrNull(),
            oldestDataEpochMs = dataTimes.minOrNull()
        )
    }

    private fun fetchPayload(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RID2Caltopo/Android (contact: kjt@uas4sar.com)")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("boundary lookup failed with HTTP ${response.code}")
                }
                return response.body?.string().orEmpty().takeIf { it.isNotBlank() }
                    ?: throw IOException("boundary lookup returned no data")
            }
        } catch (error: UnknownHostException) {
            throw IOException("host lookup failed", error)
        } catch (error: SocketTimeoutException) {
            throw IOException("lookup timed out", error)
        }
    }

    private data class CachedPayload(val payload: String, val updatedEpochMs: Long)

    private fun cacheFile(source: LandRestrictionSource): File = File(cacheDirectory, source.cacheFileName)

    private fun writeCache(source: LandRestrictionSource, payload: String, updatedEpochMs: Long) {
        val destination = cacheFile(source)
        val temporary = File(cacheDirectory, "${source.cacheFileName}.tmp")
        temporary.writeText(payload)
        if (!temporary.renameTo(destination)) {
            destination.writeText(payload)
            temporary.delete()
        }
        destination.setLastModified(updatedEpochMs)
        org.ncssar.rid2caltopo.storage.OperationalStorage.retain(destination)
    }

    private fun readCache(source: LandRestrictionSource): CachedPayload? {
        val file = cacheFile(source)
        if (!file.isFile) return null
        return runCatching { CachedPayload(file.readText(), file.lastModified()) }.getOrNull()
    }

    companion object {
        val sources: List<LandRestrictionSource> = listOf(
            LandRestrictionSource(
                id = "nps",
                queryEndpoint = "https://services1.arcgis.com/fBc8EJBxQRMcHlei/arcgis/rest/services/NPS_Land_Resources_Division_Boundary_and_Tract_Data_Service/FeatureServer/2/query",
                agency = LandAgency.NationalParkService,
                rule = LandRule.LaunchLandOperateRestricted,
                nameFields = listOf("UNIT_NAME", "PARKNAME"),
                identifierFields = listOf("UNIT_CODE", "GlobalID", "OBJECTID"),
                detailsUrlFields = listOf("METADATA"),
                cacheRevision = 2
            ),
            LandRestrictionSource(
                id = "fws-refuge",
                queryEndpoint = "https://services.arcgis.com/QVENGdaPbd4LUkLV/arcgis/rest/services/National_Wildlife_Refuge_System_Boundaries/FeatureServer/0/query",
                agency = LandAgency.FishAndWildlifeService,
                rule = LandRule.WildlifeDisturbanceRestricted,
                nameFields = listOf("ORGNAME"),
                identifierFields = listOf("ORGCODE", "OBJECTID"),
                whereClause = "RSL_TYPE='NWR'"
            ),
            LandRestrictionSource(
                id = "usfs-wilderness",
                queryEndpoint = "https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_Wilderness_01/MapServer/0/query",
                agency = LandAgency.ForestService,
                rule = LandRule.MappedWildernessAdvisory,
                nameFields = listOf("wildernessname", "WILDERNESSNAME"),
                identifierFields = listOf("areaid", "AREAID", "objectid", "OBJECTID")
            ),
            LandRestrictionSource(
                id = "cpw-properties",
                queryEndpoint = "https://services5.arcgis.com/ttNGmDvKQA7oeDQ3/ArcGIS/rest/services/CPWAdminData/FeatureServer/5/query",
                agency = LandAgency.ColoradoParksAndWildlife,
                rule = LandRule.PropertySpecificRules,
                nameFields = listOf("PropName"),
                identifierFields = listOf("GlobalID", "FID"),
                detailsUrlFields = listOf("CPW_URL"),
                whereClause = "PropType IN ('SP','SWA')"
            )
        )

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()

        fun buildQueryUrl(
            source: LandRestrictionSource,
            center: LandCoordinate,
            radiusStatuteMiles: Double
        ): HttpUrl {
            val radiusNm = OperatingArea.statuteMilesToNauticalMiles(radiusStatuteMiles)
            val latitudeDelta = radiusNm / 60.0
            val longitudeDelta = radiusNm / (60.0 * kotlin.math.cos(Math.toRadians(center.latitude))).coerceAtLeast(1.0)
            val envelope = listOf(
                center.longitude - longitudeDelta,
                center.latitude - latitudeDelta,
                center.longitude + longitudeDelta,
                center.latitude + latitudeDelta
            ).joinToString(",") { "%.7f".format(Locale.US, it) }
            return source.queryEndpoint.toHttpUrl().newBuilder()
                .addQueryParameter("where", source.whereClause)
                .addQueryParameter("geometry", envelope)
                .addQueryParameter("geometryType", "esriGeometryEnvelope")
                .addQueryParameter("inSR", "4326")
                .addQueryParameter("spatialRel", "esriSpatialRelIntersects")
                .addQueryParameter("outFields", "*")
                .addQueryParameter("returnGeometry", "true")
                .addQueryParameter("outSR", "4326")
                .addQueryParameter("f", "geojson")
                .build()
        }
    }
}

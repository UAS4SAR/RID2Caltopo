package org.ncssar.rid2caltopo.airspace

import android.location.Location
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

class AirspaceRepository(
    private val client: OkHttpClient = defaultClient
) {
    suspend fun fetch(location: Location): List<FaaUasFacilityMapRecord> {
        val request = Request.Builder()
            .url(buildFacilityMapQueryUrl(location.latitude, location.longitude))
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw AirspaceServiceFailure("Controlled-airspace lookup failed with HTTP ${response.code}.",
                            response.code == 429, response.header("Retry-After"))
                    }
                    try {
                        FaaUasFacilityMapParser.parse(response.body?.string().orEmpty())
                    } catch (e: AirspaceServiceFailure) {
                        throw AirspaceServiceFailure(e.message.orEmpty(), e.rateLimited, response.header("Retry-After"))
                    }
                }
            } catch (e: UnknownHostException) {
                throw IOException("Controlled-airspace host lookup failed.", e)
            } catch (e: SocketTimeoutException) {
                throw IOException("Controlled-airspace lookup timed out.", e)
            }
        }
    }

    companion object {
        const val OPERATING_RADIUS_NM: Double = OperatingArea.radiusNm
        private const val FAA_FACILITY_MAP_QUERY_URL =
            "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/FAA_UAS_FacilityMap_Data/FeatureServer/0/query"
        private const val OUT_FIELDS =
            "OBJECTID,CEILING,UNIT,APT1_FAAID,APT1_ICAO,APT1_NAME,APT1_LAANC,AIRSPACE_1,AIRSPACE_2,AIRSPACE_3,AIRSPACE_4,AIRSPACE_5"

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun buildFacilityMapQueryUrl(
            latitude: Double,
            longitude: Double
        ): HttpUrl {
            return FAA_FACILITY_MAP_QUERY_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("f", "json")
                .addQueryParameter("where", "1=1")
                .addQueryParameter("outFields", OUT_FIELDS)
                .addQueryParameter("returnGeometry", "true")
                .addQueryParameter(
                    "geometry",
                    "%.6f,%.6f".format(Locale.US, longitude, latitude)
                )
                .addQueryParameter("geometryType", "esriGeometryPoint")
                .addQueryParameter("inSR", "4326")
                .addQueryParameter("outSR", "4326")
                .addQueryParameter("distance", "%.6f".format(Locale.US, OperatingArea.radiusStatuteMiles))
                .addQueryParameter("units", "esriSRUnit_StatuteMile")
                .addQueryParameter("spatialRel", "esriSpatialRelIntersects")
                .build()
        }
    }
}

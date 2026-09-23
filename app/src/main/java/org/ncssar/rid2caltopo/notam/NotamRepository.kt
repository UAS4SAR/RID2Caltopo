package org.ncssar.rid2caltopo.notam

import android.location.Location
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ncssar.rid2caltopo.airspace.OperatingArea
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.app.R2CApplication
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebugEnabled
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.OrgConfigManager
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class NotamRepository {
    private data class FetchResult(
        val notices: List<NearbyNotam>,
        val incremental: Boolean
    )

    private data class GeometryProximity(
        val distanceNm: Double,
        val bearingDeg: Double?
    )

    private companion object {
        const val TAG = "NotamREST"
        const val INCREMENTAL_MOVEMENT_THRESHOLD_FT = 100.0
        const val EARTH_RADIUS_NM = 3440.065
        val RADIUS_REGEX = Regex("""\b([0-9]+(?:\.[0-9]+)?)NM RADIUS\b""", RegexOption.IGNORE_CASE)
        val DMS_COORD_REGEX = Regex("""\b([0-9]{6}[NS])([0-9]{7}[EW])\b""", RegexOption.IGNORE_CASE)
    }

    private data class RadiusArea(
        val centerLat: Double,
        val centerLon: Double,
        val radiusNm: Double
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val demElevationService by lazy {
        R2CApplication.getAppCtxt()?.let { DemElevationService(it) }
    }
    private val _uiState = MutableStateFlow(buildUiState(null, configured = false, loading = false, notices = emptyList()))
    val uiState: StateFlow<NotamUiState> = _uiState.asStateFlow()

    private var lastRefreshLocation: Location? = null
    private var lastRefreshAtMs: Long = 0L
    private var lastFetchedNotices: List<NearbyNotam> = emptyList()
    private var lastErrorMessage: String? = null
    private var lastSuccessfulFetchAtMs: Long = 0L
    private val resetLock = Any()
    private var resetGeneration: Long = 0L

    fun resetRuntimeState() {
        synchronized(resetLock) {
            resetGeneration += 1L
            lastRefreshLocation = null
            lastRefreshAtMs = 0L
            lastFetchedNotices = emptyList()
            lastErrorMessage = null
            lastSuccessfulFetchAtMs = 0L
            _uiState.value = buildUiState(
                location = null,
                configured = NotamAuthManager.isConfigured(),
                loading = false,
                notices = emptyList()
            )
        }
    }

    suspend fun refresh(force: Boolean = false) {
        val refreshGeneration = synchronized(resetLock) { resetGeneration }
        val enabled = CaltopoClient.GetNotamEnabled()
        val configured = NotamAuthManager.isConfigured()
        val location = CaltopoMap.GetMyLocation()
        if (force && CTDebugEnabled(TAG)) {
            CTDebug(
                TAG,
                "refresh(force=$force) enabled=$enabled configured=$configured radiusStatuteMiles=${CaltopoClient.GetNotamRadiusNm()} hasLocation=${location != null} accuracyM=${location?.accuracy ?: Float.NaN}"
            )
        }
        if (!enabled) {
            _uiState.value = NotamUiState(visible = false, enabled = false, configured = configured)
            return
        }

        if (!force && !shouldRefresh(location)) {
            val retainedNotices = if (lastFetchedNotices.isNotEmpty()) lastFetchedNotices else _uiState.value.notices
            _uiState.value = buildUiState(
                location = location,
                configured = configured,
                loading = false,
                notices = retainedNotices
            )
            return
        }

        val retainedNotices = if (lastFetchedNotices.isNotEmpty()) lastFetchedNotices else _uiState.value.notices
        if (!publishIfCurrent(
                refreshGeneration,
                buildUiState(
                    location = location,
                    configured = configured,
                    loading = true,
                    notices = retainedNotices
                )
            )) return
        val now = System.currentTimeMillis()
        val notices = if (configured) {
            try {
                val fetched = fetchNearbyNotams(location, forceFull = force)
                val accepted = synchronized(resetLock) {
                    if (refreshGeneration != resetGeneration) return@synchronized false
                    lastFetchedNotices = fetched.notices
                    lastSuccessfulFetchAtMs = now
                    lastErrorMessage = null
                    true
                }
                if (!accepted) return
                if (CTDebugEnabled(TAG)) {
                    CTDebug(
                        TAG,
                        "Fetched ${fetched.notices.size} nearby NOTAMs mode=${if (fetched.incremental) "delta" else "full"}"
                    )
                }
                fetched.notices
            } catch (e: Exception) {
                val accepted = synchronized(resetLock) {
                    if (refreshGeneration != resetGeneration) return@synchronized false
                    lastErrorMessage = classifyNotamErrorMessage(e)
                    true
                }
                if (!accepted) return
                if (CTDebugEnabled(TAG)) {
                    CTDebug(TAG, "Fetch failed: ${lastErrorMessage}")
                }
                lastFetchedNotices
            }
        } else {
            listOf(
                NearbyNotam(
                    id = "pending_credentials",
                    title = "FAA NOTAM proxy pending",
                    summary = "NOTAM monitoring is enabled, but FAA proxy access has not been configured.",
                    distanceNm = null,
                    effectiveText = "Waiting for FAA proxy configuration",
                    details = "Import an r2c-tracker organization QR code to activate live nearby NOTAM queries.",
                    rawText = "",
                    severity = NotamChipSeverity.Neutral
                )
            )
        }
        synchronized(resetLock) {
            if (refreshGeneration != resetGeneration) return
            if (configured && lastErrorMessage == null) lastRefreshLocation = location?.let { Location(it) }
            lastRefreshAtMs = now
            if (configured && lastErrorMessage == null) {
                CaltopoClient.SetNotamLastUpdatedEpochMs(now)
            }
            _uiState.value = buildUiState(
                location = location,
                configured = configured,
                loading = false,
                notices = notices
            )
        }
    }

    private fun publishIfCurrent(generation: Long, state: NotamUiState): Boolean =
        synchronized(resetLock) {
            if (generation != resetGeneration) return@synchronized false
            _uiState.value = state
            true
        }

    private fun shouldRefresh(location: Location?): Boolean {
        if (lastRefreshAtMs == 0L) return true
        val now = System.currentTimeMillis()
        if (lastErrorMessage != null) {
            return now - lastRefreshAtMs >= 15_000L
        }
        if (!CaltopoClient.GetNotamAutoRefresh()) return false
        val refreshIntervalMs = CaltopoClient.GetNotamRefreshIntervalSeconds().coerceAtLeast(30) * 1_000L
        if (now - lastRefreshAtMs >= refreshIntervalMs) return true
        val previous = lastRefreshLocation ?: return true
        val current = location ?: return false
        val distanceMeters = previous.distanceTo(current)
        val distanceNm = distanceMeters / 1852.0
        return distanceNm >= 0.25
    }

    private suspend fun fetchNearbyNotams(location: Location?, forceFull: Boolean): FetchResult {
        val current = location ?: throw IllegalStateException("Waiting for GPS location")
        val canUseIncremental =
            !forceFull &&
                lastFetchedNotices.isNotEmpty() &&
                lastSuccessfulFetchAtMs > 0L &&
                movedSinceLastFetchFeet(current) <= INCREMENTAL_MOVEMENT_THRESHOLD_FT
        if (canUseIncremental) {
            val deltaNotices = fetchNearbyNotamsDelta(current, lastSuccessfulFetchAtMs)
            if (CTDebugEnabled(TAG)) {
                CTDebug(
                    TAG,
                    "Applying incremental nearby NOTAM update movedFt=${"%.1f".format(Locale.US, movedSinceLastFetchFeet(current))} " +
                        "deltaCount=${deltaNotices.size}"
                )
            }
            return FetchResult(
                notices = mergeIncrementalNotices(lastFetchedNotices, deltaNotices),
                incremental = true
            )
        }
        return FetchResult(
            notices = fetchNearbyNotamsFull(current),
            incremental = false
        )
    }

    private suspend fun fetchNearbyNotamsFull(current: Location): List<NearbyNotam> {
        val url = NotamAuthManager.resolvedNotamUrl()
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("latitude", "%.6f".format(Locale.US, current.latitude))
            .addQueryParameter("longitude", "%.6f".format(Locale.US, current.longitude))
            .addQueryParameter(
                "radius",
                OperatingArea.faaNotamQueryRadiusNm(CaltopoClient.GetNotamRadiusNm()).toString()
            )
            .build()
        if (CTDebugEnabled(TAG)) {
            CTDebug(TAG, "GET $url responseFormat=GEOJSON")
        }
        val request = Request.Builder()
            .url(url)
            .header("X-SAR-Token", NotamAuthManager.proxyToken())
            .header("X-R2C-Functionality-Release", BuildConfig.TRACKER_FUNCTIONALITY_RELEASE.toString())
            .get()
            .build()
        return executeNotamRequest(request, "NOTAM query") { body ->
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: throw IllegalStateException("NOTAM response did not include data")
            val features = data.optJSONArray("geojson") ?: JSONArray()
            if (CTDebugEnabled(TAG)) {
                CTDebug(TAG, "Parsed geojson featureCount=${features.length()} status='${json.optString("status")}' message='${json.optString("message")}'")
            }
            val notices = mutableListOf<NearbyNotam>()
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                notices += featureToNearbyNotam(feature, current)
            }
            NotamPolicy.sort(notices)
        }
    }

    private suspend fun fetchNearbyNotamsDelta(current: Location, sinceEpochMs: Long): List<NearbyNotam> {
        val url = NotamAuthManager.resolvedNotamUrl()
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("latitude", "%.6f".format(Locale.US, current.latitude))
            .addQueryParameter("longitude", "%.6f".format(Locale.US, current.longitude))
            .addQueryParameter(
                "radius",
                OperatingArea.faaNotamQueryRadiusNm(CaltopoClient.GetNotamRadiusNm()).toString()
            )
            .addQueryParameter("lastUpdatedDate", Instant.ofEpochMilli(sinceEpochMs).toString())
            .build()
        if (CTDebugEnabled(TAG)) {
            CTDebug(TAG, "GET $url responseFormat=GEOJSON delta")
        }
        val request = Request.Builder()
            .url(url)
            .header("X-SAR-Token", NotamAuthManager.proxyToken())
            .header("X-R2C-Functionality-Release", BuildConfig.TRACKER_FUNCTIONALITY_RELEASE.toString())
            .get()
            .build()
        return executeNotamRequest(request, "NOTAM delta query") { body ->
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: throw IllegalStateException("NOTAM delta response did not include data")
            val features = data.optJSONArray("geojson") ?: JSONArray()
            if (CTDebugEnabled(TAG)) {
                CTDebug(TAG, "Parsed delta geojson featureCount=${features.length()} status='${json.optString("status")}' message='${json.optString("message")}'")
            }
            val notices = mutableListOf<NearbyNotam>()
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                notices += featureToNearbyNotam(feature, current)
            }
            notices
        }
    }

    private suspend fun <T> executeNotamRequest(
        request: Request,
        requestLabel: String,
        parseBody: suspend (String) -> T
    ): T = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (CTDebugEnabled(TAG)) {
                    CTDebug(TAG, "$requestLabel response http=${response.code}")
                }
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    val errorDetail = runCatching {
                        JSONObject(errorBody).optJSONObject("detail")
                    }.getOrNull()
                    val errorCode = errorDetail?.optString("code")
                    if (errorCode == "reauthentication_required") {
                        val managedCaltopoCleared =
                            CaltopoClient.QuarantineTrackerManagedCaltopoCredentials()
                        if (managedCaltopoCleared) {
                            R2CApplication.getAppCtxt()?.let {
                                OrgConfigManager.invalidateManagedConfigurationVersion(it)
                            }
                        }
                        errorDetail.optString("reauthentication_url")
                            .takeIf { it.isNotBlank() }
                            ?.let { url ->
                                R2CActivity.getR2CActivity()
                                    ?.beginTrackerReauthentication(url)
                            }
                        CaltopoClient.ShowToast(
                            if (managedCaltopoCleared) {
                                "Tracker access is paused. Tracker-managed CalTopo credentials " +
                                    "were cleared; offline RID2Caltopo remains available."
                            } else {
                                "Tracker access is paused. Independent CalTopo credentials and " +
                                    "offline RID2Caltopo operation remain available."
                            }
                        )
                        throw IOException(
                            "Tracker requires reauthentication."
                        )
                    }
                    val message = when (response.code) {
                        401, 403 -> {
                            NotamAuthManager.authorizationFailureMessage(response.code)
                        }
                        else -> "$requestLabel failed with HTTP ${response.code}."
                    }
                    throw IOException(message)
                }
                parseBody(response.body?.string().orEmpty())
            }
        } catch (e: UnknownHostException) {
            throw IOException("NOTAM host lookup failed. Keeping last results.", e)
        } catch (e: SocketTimeoutException) {
            throw IOException("NOTAM request timed out. Keeping last results.", e)
        } catch (e: NotamAuthManager.NotamAuthException) {
            throw e
        } catch (e: IOException) {
            throw IOException(e.message ?: "NOTAM network error. Keeping last results.", e)
        }
    }

    private fun classifyNotamErrorMessage(error: Throwable): String {
        return when (error) {
            is NotamAuthManager.NotamAuthException.Authorization -> error.message ?: "NOTAM authentication failed."
            is NotamAuthManager.NotamAuthException.Network -> error.message ?: "NOTAM network unavailable. Keeping last results."
            is NotamAuthManager.NotamAuthException.Service -> error.message ?: "NOTAM service error."
            is UnknownHostException -> "NOTAM host lookup failed. Keeping last results."
            is SocketTimeoutException -> "NOTAM request timed out. Keeping last results."
            is IOException -> error.message ?: "NOTAM network error. Keeping last results."
            else -> error.message ?: "NOTAM request failed"
        }
    }

    private suspend fun featureToNearbyNotam(feature: JSONObject, current: Location): NearbyNotam {
        val properties = feature.optJSONObject("properties")
        val coreData = properties?.optJSONObject("coreNOTAMData")
        val notam = coreData?.optJSONObject("notam")
        val translations = coreData?.optJSONArray("notamTranslation")
        val geometry = feature.optJSONObject("geometry")

        val title = buildString {
            val locationCode = notam?.optString("icaoLocation").orEmpty().ifBlank { notam?.optString("location").orEmpty() }
            val number = buildNotamNumber(notam)
            if (locationCode.isNotBlank()) append(locationCode)
            if (number.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(number)
            }
            val text = notam?.optString("text").orEmpty()
            if (text.isNotBlank()) {
                if (isNotEmpty()) append(" - ")
                append(text)
            }
        }.ifBlank { "Nearby NOTAM" }

        val reference = buildString {
            val locationCode = notam?.optString("icaoLocation").orEmpty().ifBlank { notam?.optString("location").orEmpty() }
            val number = buildNotamNumber(notam)
            if (locationCode.isNotBlank()) append(locationCode)
            if (number.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(number)
            }
        }
        val notamText = notam?.optString("text").orEmpty()
        val rawText = extractRawText(translations)
        val classification = notam?.optString("classification").orEmpty().ifBlank { "unknown" }
        val fallbackRadiusArea = parseRadiusArea(notamText, rawText)
            ?.takeIf { isRadiusTfrNotice(reference, notamText, rawText, classification) }
            ?.takeIf { shouldUseFallbackRadiusArea(geometry, it) }
        val geometries = fallbackRadiusArea?.let(::buildCircleGeometry)
            ?: geometry?.toNotamGeometries().orEmpty()
        val proximity = fallbackRadiusArea?.let { distanceToRadiusArea(current, it) }
            ?: geometry?.let { distanceToGeometry(current, it) }
        val distanceNm = proximity?.distanceNm
        val horizontalIntersectsPilotBubble = distanceNm != null && distanceNm <= OperatingArea.radiusNm
        val altitudeBand = parseAltitudeBand(notamText, rawText)
        val operatingBand = resolveOperatingAltitudeBand(current)
        val verticallyIntersectsPilotBand = altitudeBand?.overlaps(operatingBand)
        val intersectsPilotBubble = horizontalIntersectsPilotBubble && verticallyIntersectsPilotBand != false
        val severity = inferSeverity(
            title = title,
            rawText = rawText,
            intersectsPilotBubble = intersectsPilotBubble,
            horizontalIntersectsPilotBubble = horizontalIntersectsPilotBubble,
            verticallyIntersectsPilotBand = verticallyIntersectsPilotBand
        )
        val effectiveText = buildEffectiveText(notam)
        val proximityText = proximityText(distanceNm, proximity?.bearingDeg)
        val humanized = NotamHumanizer.humanize(
            reference = reference,
            notamText = notamText,
            rawText = rawText,
            effectiveText = effectiveText,
            proximityText = proximityText,
            intersectsPilotBubble = intersectsPilotBubble,
            horizontalIntersectsPilotBubble = horizontalIntersectsPilotBubble,
            verticallyIntersectsPilotBand = verticallyIntersectsPilotBand,
            classification = classification,
            scheduleText = notam?.optString("schedule").orEmpty()
        )
        return NearbyNotam(
            id = notam?.optString("id").orEmpty().ifBlank { title },
            title = humanized.title,
            summary = humanized.summary,
            distanceNm = distanceNm,
            bearingText = proximity?.bearingDeg?.let(::compassDirection),
            proximityText = proximityText,
            intersectsPilotBubble = intersectsPilotBubble,
            horizontalIntersectsPilotBubble = horizontalIntersectsPilotBubble,
            verticallyIntersectsPilotBand = verticallyIntersectsPilotBand,
            effectiveText = effectiveText,
            details = humanized.details,
            rawText = rawText,
            rawTitle = title,
            rawReference = reference,
            updateType = notam?.optString("type").orEmpty(),
            cancelationDate = notam?.optString("cancelationDate").orEmpty(),
            lastUpdated = notam?.optString("lastUpdated").orEmpty(),
            severity = severity,
            altitudeBand = altitudeBand,
            geometries = geometries
        ).also {
            if (CTDebugEnabled(TAG)) {
                val geometryType = geometry?.optString("type").orEmpty().ifBlank { "none" }
                CTDebug(
                    TAG,
                    "notice id='${it.id}' severity=${it.severity} distanceNm=${it.distanceNm?.let { d -> "%.2f".format(Locale.US, d) } ?: "n/a"} " +
                        "intersects=${it.intersectsPilotBubble} geometryType=$geometryType drawableGeometries=${it.geometries.size} " +
                        "title='${it.title.take(120)}'"
                )
                if (fallbackRadiusArea != null) {
                    CTDebug(
                        TAG,
                        "Using fallback radius geometry id='${it.id}' center=${"%.6f".format(Locale.US, fallbackRadiusArea.centerLat)},${"%.6f".format(Locale.US, fallbackRadiusArea.centerLon)} radiusNm=${"%.1f".format(Locale.US, fallbackRadiusArea.radiusNm)}"
                    )
                }
                if (reference.contains("6/3475") || it.title.contains("6/3475")) {
                    CTDebug(
                        TAG,
                        "TFR geometry debug id='${it.id}' reference='${reference}' geometry=${geometry?.toString() ?: "null"}"
                    )
                }
            }
        }
    }

    private fun buildNotamNumber(notam: JSONObject?): String {
        if (notam == null) return ""
        val series = notam.optString("series")
        val number = notam.optString("number")
        val year = notam.optString("year")
        return listOf(series, number, year).filter { it.isNotBlank() }.joinToString("/")
    }

    private fun extractRawText(translations: JSONArray?): String {
        if (translations == null) return ""
        for (i in 0 until translations.length()) {
            val item = translations.optJSONObject(i) ?: continue
            val domestic = item.optString("domestic_message")
            if (domestic.isNotBlank()) return domestic
            val icao = item.optString("icao_message")
            if (icao.isNotBlank()) return icao.replace('\n', ' ')
        }
        return ""
    }

    private fun buildEffectiveText(notam: JSONObject?): String {
        if (notam == null) return ""
        val start = notam.optString("effectiveStart")
        val end = notam.optString("effectiveEnd")
        return when {
            start.isNotBlank() && end.isNotBlank() -> "Active $start to $end"
            start.isNotBlank() -> "Active from $start"
            end.isNotBlank() -> "Active until $end"
            else -> ""
        }
    }

    private fun inferSeverity(
        title: String,
        rawText: String,
        intersectsPilotBubble: Boolean,
        horizontalIntersectsPilotBubble: Boolean,
        verticallyIntersectsPilotBand: Boolean?
    ): NotamChipSeverity {
        val haystack = "$title $rawText".uppercase(Locale.US)
        if (intersectsPilotBubble && (haystack.contains("TFR") || haystack.contains("RESTRICT") || haystack.contains("UAS") || haystack.contains("PROHIBITED"))) {
            return NotamChipSeverity.Danger
        }
        if (horizontalIntersectsPilotBubble && verticallyIntersectsPilotBand == false &&
            (haystack.contains("TFR") || haystack.contains("RESTRICT") || haystack.contains("UAS") || haystack.contains("PROHIBITED"))) {
            return NotamChipSeverity.Caution
        }
        if (haystack.contains("TFR") || haystack.contains("RESTRICT") || haystack.contains("UAS") || haystack.contains("HAZARD")) {
            return NotamChipSeverity.Caution
        }
        return NotamChipSeverity.Normal
    }

    private fun parseAltitudeBand(notamText: String, rawText: String): NotamAltitudeBand? {
        val sourceText = listOf(rawText, notamText).firstOrNull { it.isNotBlank() }.orEmpty()
        return NotamAltitudeParser.parse(sourceText)
    }

    private fun NotamAltitudeBand.overlaps(operatingBand: OperatingAltitudeBand?): Boolean? {
        if (operatingBand == null) return null
        val effectiveFloor = floorFeetMsl ?: Double.NEGATIVE_INFINITY
        val effectiveCeiling = ceilingFeetMsl ?: Double.POSITIVE_INFINITY
        return effectiveFloor <= operatingBand.ceilingFeetMsl &&
            effectiveCeiling >= operatingBand.floorFeetMsl
    }

    private suspend fun resolveOperatingAltitudeBand(location: Location): OperatingAltitudeBand? {
        val demElevationMeters = if (location.hasAltitude()) {
            null
        } else {
            demElevationService?.sampleElevationMeters(location.latitude, location.longitude)?.elevationMeters
        }
        return NotamOperatingAltitudeResolver.resolve(location, demElevationMeters)
    }

    private fun distanceToGeometry(origin: Location, geometry: JSONObject): GeometryProximity? {
        return when (geometry.optString("type")) {
            "Point" -> geometry.optJSONArray("coordinates")?.let { coords ->
                if (coords.length() >= 2) proximityToPoint(origin, coords.optDouble(1), coords.optDouble(0)) else null
            }
            "LineString" -> minDistanceToLine(origin, geometry.optJSONArray("coordinates"))
            "Polygon" -> distanceToPolygon(origin, geometry.optJSONArray("coordinates")?.optJSONArray(0))
            "MultiPolygon" -> {
                val groups = geometry.optJSONArray("coordinates") ?: return null
                var minDistance: GeometryProximity? = null
                for (i in 0 until groups.length()) {
                    val ring = groups.optJSONArray(i)?.optJSONArray(0)
                    val candidate = distanceToPolygon(origin, ring)
                    if (candidate != null) minDistance = minOfNullable(minDistance, candidate)
                }
                minDistance
            }
            "GeometryCollection" -> {
                val geometries = geometry.optJSONArray("geometries") ?: return null
                var minDistance: GeometryProximity? = null
                for (i in 0 until geometries.length()) {
                    val candidate = geometries.optJSONObject(i)?.let { distanceToGeometry(origin, it) }
                    if (candidate != null) minDistance = minOfNullable(minDistance, candidate)
                }
                minDistance
            }
            else -> null
        }
    }

    private fun minDistanceToLine(origin: Location, coordinates: JSONArray?): GeometryProximity? {
        if (coordinates == null) return null
        var minDistance: GeometryProximity? = null
        for (i in 0 until coordinates.length()) {
            val coords = coordinates.optJSONArray(i) ?: continue
            if (coords.length() < 2) continue
            val candidate = proximityToPoint(origin, coords.optDouble(1), coords.optDouble(0))
            minDistance = minOfNullable(minDistance, candidate)
        }
        return minDistance
    }

    private fun distanceToPolygon(origin: Location, ring: JSONArray?): GeometryProximity? {
        if (ring == null || ring.length() < 3) return null
        if (pointInPolygon(origin.latitude, origin.longitude, ring)) return GeometryProximity(0.0, null)
        return minDistanceToLine(origin, ring)
    }

    private fun pointInPolygon(lat: Double, lon: Double, ring: JSONArray): Boolean {
        var inside = false
        var j = ring.length() - 1
        for (i in 0 until ring.length()) {
            val pi = ring.optJSONArray(i) ?: continue
            val pj = ring.optJSONArray(j) ?: continue
            val xi = pi.optDouble(0)
            val yi = pi.optDouble(1)
            val xj = pj.optDouble(0)
            val yj = pj.optDouble(1)
            val intersects = ((yi > lat) != (yj > lat)) &&
                (lon < (xj - xi) * (lat - yi) / ((yj - yi).takeIf { it != 0.0 } ?: 1e-9) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    private fun proximityToPoint(origin: Location, lat: Double, lon: Double): GeometryProximity {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat - origin.latitude)
        val dLon = Math.toRadians(lon - origin.longitude)
        val lat1 = Math.toRadians(origin.latitude)
        val lat2 = Math.toRadians(lat)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return GeometryProximity((earthRadiusMeters * c) / 1852.0, bearing)
    }

    private fun distanceToRadiusArea(origin: Location, area: RadiusArea): GeometryProximity {
        val centerProximity = proximityToPoint(origin, area.centerLat, area.centerLon)
        return centerProximity.copy(
            distanceNm = maxOf(0.0, centerProximity.distanceNm - area.radiusNm)
        )
    }

    private fun parseRadiusArea(notamText: String, rawText: String): RadiusArea? {
        val sourceText = listOf(rawText, notamText).firstOrNull { it.isNotBlank() }.orEmpty()
        if (sourceText.isBlank()) return null
        val radiusNm = RADIUS_REGEX.find(sourceText)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return null
        val coordMatch = DMS_COORD_REGEX.find(sourceText.uppercase(Locale.US)) ?: return null
        val lat = parseCompactDmsLat(coordMatch.groupValues[1]) ?: return null
        val lon = parseCompactDmsLon(coordMatch.groupValues[2]) ?: return null
        return RadiusArea(centerLat = lat, centerLon = lon, radiusNm = radiusNm)
    }

    private fun isRadiusTfrNotice(
        reference: String,
        notamText: String,
        rawText: String,
        classification: String
    ): Boolean {
        val haystack = "$reference $notamText $rawText".uppercase(Locale.US)
        if (classification.equals("FDC", ignoreCase = true)) return true
        if (haystack.contains("TEMPORARY FLIGHT RESTRICTION")) return true
        if (haystack.contains(" TFR") || haystack.startsWith("TFR")) return true
        if (haystack.contains("NTL DEFENSE AIRSPACE")) return true
        return false
    }

    private fun shouldUseFallbackRadiusArea(geometry: JSONObject?, area: RadiusArea): Boolean {
        if (geometry == null) return true
        return when (geometry.optString("type")) {
            "Point" -> geometry.optJSONArray("coordinates")?.let { pointIsFarFromRadiusCenter(it, area) } ?: true
            "GeometryCollection" -> {
                val geometries = geometry.optJSONArray("geometries") ?: return true
                if (geometries.length() != 1) return false
                val point = geometries.optJSONObject(0) ?: return true
                if (point.optString("type") != "Point") return false
                point.optJSONArray("coordinates")?.let { pointIsFarFromRadiusCenter(it, area) } ?: true
            }
            else -> false
        }
    }

    private fun pointIsFarFromRadiusCenter(coords: JSONArray, area: RadiusArea): Boolean {
        if (coords.length() < 2) return true
        val lon = coords.optDouble(0)
        val lat = coords.optDouble(1)
        val centerDistanceNm = proximityToPoint(
            origin = Location("fallback-center").apply {
                latitude = area.centerLat
                longitude = area.centerLon
            },
            lat = lat,
            lon = lon
        ).distanceNm
        return centerDistanceNm > maxOf(2.0, area.radiusNm * 0.25)
    }

    private fun buildCircleGeometry(area: RadiusArea, steps: Int = 48): List<NotamGeometry> {
        val latRad = Math.toRadians(area.centerLat)
        val lonRad = Math.toRadians(area.centerLon)
        val angularDistance = area.radiusNm / EARTH_RADIUS_NM
        val ring = buildList {
            for (i in 0..steps) {
                val bearing = (Math.PI * 2.0 * i) / steps.toDouble()
                val lat2 = kotlin.math.asin(
                    sin(latRad) * cos(angularDistance) +
                        cos(latRad) * sin(angularDistance) * cos(bearing)
                )
                val lon2 = lonRad + atan2(
                    sin(bearing) * sin(angularDistance) * cos(latRad),
                    cos(angularDistance) - sin(latRad) * sin(lat2)
                )
                add(
                    NotamLatLng(
                        latitude = Math.toDegrees(lat2),
                        longitude = Math.toDegrees(lon2)
                    )
                )
            }
        }
        return listOf(NotamGeometry.Polygon(rings = listOf(ring)))
    }

    private fun parseCompactDmsLat(value: String): Double? {
        if (value.length != 7) return null
        val deg = value.substring(0, 2).toDoubleOrNull() ?: return null
        val min = value.substring(2, 4).toDoubleOrNull() ?: return null
        val sec = value.substring(4, 6).toDoubleOrNull() ?: return null
        val hemi = value.last().uppercaseChar()
        val decimal = deg + (min / 60.0) + (sec / 3600.0)
        return if (hemi == 'S') -decimal else if (hemi == 'N') decimal else null
    }

    private fun parseCompactDmsLon(value: String): Double? {
        if (value.length != 8) return null
        val deg = value.substring(0, 3).toDoubleOrNull() ?: return null
        val min = value.substring(3, 5).toDoubleOrNull() ?: return null
        val sec = value.substring(5, 7).toDoubleOrNull() ?: return null
        val hemi = value.last().uppercaseChar()
        val decimal = deg + (min / 60.0) + (sec / 3600.0)
        return if (hemi == 'W') -decimal else if (hemi == 'E') decimal else null
    }

    private fun JSONObject.toNotamGeometries(): List<NotamGeometry> {
        return when (optString("type")) {
            "Point" -> optJSONArray("coordinates")?.toPointGeometry()?.let(::listOf).orEmpty()
            "LineString" -> optJSONArray("coordinates")?.toCoordinateList()
                ?.takeIf { it.size >= 2 }
                ?.let { listOf(NotamGeometry.Line(it)) }
                .orEmpty()
            "Polygon" -> optJSONArray("coordinates")?.toPolygonGeometry()?.let(::listOf).orEmpty()
            "MultiPolygon" -> {
                val polygons = optJSONArray("coordinates") ?: return emptyList()
                buildList {
                    for (i in 0 until polygons.length()) {
                        polygons.optJSONArray(i)?.toPolygonGeometry()?.let(::add)
                    }
                }
            }
            "GeometryCollection" -> {
                val geometries = optJSONArray("geometries") ?: return emptyList()
                buildList {
                    for (i in 0 until geometries.length()) {
                        geometries.optJSONObject(i)?.toNotamGeometries()?.forEach(::add)
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun JSONArray.toPointGeometry(): NotamGeometry.Point? {
        if (length() < 2) return null
        return NotamGeometry.Point(
            NotamLatLng(
                latitude = optDouble(1),
                longitude = optDouble(0)
            )
        )
    }

    private fun JSONArray.toPolygonGeometry(): NotamGeometry.Polygon? {
        val rings = buildList {
            for (i in 0 until length()) {
                optJSONArray(i)?.toCoordinateList()?.takeIf { it.size >= 3 }?.let(::add)
            }
        }
        return rings.takeIf { it.isNotEmpty() }?.let { NotamGeometry.Polygon(it) }
    }

    private fun JSONArray.toCoordinateList(): List<NotamLatLng> {
        return buildList {
            for (i in 0 until length()) {
                val item = optJSONArray(i) ?: continue
                if (item.length() < 2) continue
                add(
                    NotamLatLng(
                        latitude = item.optDouble(1),
                        longitude = item.optDouble(0)
                    )
                )
            }
        }
    }

    private fun minOfNullable(a: GeometryProximity?, b: GeometryProximity): GeometryProximity =
        if (a == null || b.distanceNm < a.distanceNm) b else a

    private fun buildUiState(
        location: Location?,
        configured: Boolean,
        loading: Boolean,
        notices: List<NearbyNotam>
    ): NotamUiState {
        val enabled = CaltopoClient.GetNotamEnabled()
        if (!enabled) {
            return NotamUiState(
                visible = true,
                enabled = false,
                configured = configured,
                chipLabel = "NOTAMs disabled",
                statusLine = "Nearby NOTAM monitoring is disabled."
            )
        }

        val sorted = NotamPolicy.sort(notices)
        val (visibleNotices, suppressedNoticeCount) = NotamPolicy.filterWithinRadius(sorted, CaltopoClient.GetNotamRadiusNm())
        val nearestHiddenNotice = sorted.firstOrNull { notice ->
            notice !in visibleNotices
        }
        val lastUpdatedMs = lastSuccessfulFetchAtMs
        val stale = NotamPolicy.isStale(lastUpdatedMs, System.currentTimeMillis(), CaltopoClient.GetNotamRefreshIntervalSeconds())
        val hasError = lastErrorMessage != null || stale || lastUpdatedMs == 0L
        val chipSeverity = NotamPolicy.effectiveChipSeverity(
            notices = visibleNotices,
            configured = configured,
            hasError = hasError
        )
        val chipLabel = NotamPolicy.chipLabel(
            notices = visibleNotices,
            configured = configured,
            loading = loading,
            hasError = hasError
        )
        val statusLine = buildString {
            if (lastErrorMessage != null) {
                append(lastErrorMessage)
            } else if (location != null && location.hasAccuracy()) {
                append("GPS accuracy ")
                append("%.0f ft".format(Locale.US, location.accuracy * 3.28084))
            } else {
                append("Waiting for reliable GPS fix")
            }
        }
        return NotamUiState(
            visible = true,
            enabled = enabled,
            configured = configured,
            loading = loading,
            stale = stale,
            chipSeverity = chipSeverity,
            chipLabel = chipLabel,
            statusLine = statusLine,
            lastUpdatedText = formatLastUpdated(lastUpdatedMs, stale),
            queryLatitude = lastRefreshLocation?.latitude,
            queryLongitude = lastRefreshLocation?.longitude,
            radiusStatuteMiles = CaltopoClient.GetNotamRadiusNm(),
            notices = visibleNotices,
            suppressedNoticeCount = suppressedNoticeCount,
            nearestHiddenNotice = nearestHiddenNotice,
            errorMessage = lastErrorMessage
        )
    }

    private fun proximityText(distanceNm: Double?, bearingDeg: Double?): String {
        if (distanceNm == null) return "Distance unavailable"
        if (distanceNm < 0.05) return "HERE"
        val distanceText = String.format(Locale.US, "%.1f mi", distanceNm / OperatingArea.radiusNm)
        val bearingText = bearingDeg?.let(::compassDirection)
        return if (bearingText.isNullOrBlank()) distanceText else "$bearingText $distanceText"
    }

    private fun compassDirection(bearingDeg: Double): String {
        val directions = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
        val index = (((normalized + 11.25) / 22.5).toInt()) % directions.size
        return directions[index]
    }

    private fun formatLastUpdated(epochMs: Long, stale: Boolean): String? {
        if (epochMs <= 0L) return null
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
            .withZone(ZoneId.systemDefault())
        val prefix = if (stale) "Stale • last successful check " else "Last successful check "
        return prefix + formatter.format(Instant.ofEpochMilli(epochMs))
    }

    private fun mergeIncrementalNotices(
        existing: List<NearbyNotam>,
        delta: List<NearbyNotam>
    ): List<NearbyNotam> {
        val merged = LinkedHashMap<String, NearbyNotam>()
        existing.forEach { notice -> merged[notice.id] = notice }
        delta.forEach { notice ->
            if (shouldRemoveFromCache(notice)) {
                merged.remove(notice.id)
            } else {
                merged[notice.id] = notice
            }
        }
        return NotamPolicy.sort(merged.values.toList())
    }

    private fun shouldRemoveFromCache(notice: NearbyNotam): Boolean {
        val updateType = notice.updateType.uppercase(Locale.US)
        if (updateType == "D") return true
        parseInstant(notice.cancelationDate)?.let { canceledAt ->
            if (!canceledAt.isAfter(Instant.now())) return true
        }
        parseEffectiveEndInstant(notice.effectiveText)?.let { effectiveEnd ->
            if (!effectiveEnd.isAfter(Instant.now())) return true
        }
        return false
    }

    private fun movedSinceLastFetchFeet(current: Location): Double {
        val previous = lastRefreshLocation ?: return Double.MAX_VALUE
        return previous.distanceTo(current) * 3.28084
    }

    private fun parseInstant(value: String): Instant? =
        value.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }

    private fun parseEffectiveEndInstant(effectiveText: String): Instant? {
        val marker = " to "
        val end = effectiveText.substringAfter(marker, missingDelimiterValue = "").trim()
        return parseInstant(end)
    }
}

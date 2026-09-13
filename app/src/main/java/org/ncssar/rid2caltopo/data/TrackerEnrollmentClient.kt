package org.ncssar.rid2caltopo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.app.R2CApplication
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class TrackerEnrollmentResult(
    val organization: String,
    val trackerBaseUrl: String,
    val deviceToken: String,
    val faaProxyUrl: String,
    val enrollmentUrl: String,
    val reauthenticationUrl: String? = null
)

data class TrackerDeviceReplacementCandidate(
    val credentialId: String,
    val deviceName: String,
    val deviceModel: String,
)

object TrackerEnrollmentClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    const val APP_LINK_SCHEME = "r2cenroll"
    private const val IDENTITY_PREFS = "tracker_device_identity"
    private const val RECONCILIATION_PENDING = "replacement_check_pending"

    fun normalizedEnrollmentUrl(value: String): String? {
        val trimmed = value.trim()
        if (isEnrollmentUrl(trimmed)) return trimmed
        val wrapper = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!wrapper.scheme.equals(APP_LINK_SCHEME, ignoreCase = true)) return null
        val nested = wrapper.rawQuery.orEmpty()
            .split('&')
            .mapNotNull { field ->
                val pieces = field.split('=', limit = 2)
                if (pieces.firstOrNull() != "url" || pieces.size != 2) null
                else runCatching {
                    URLDecoder.decode(pieces[1], StandardCharsets.UTF_8.name())
                }.getOrNull()
            }
            .firstOrNull()
            ?.trim()
            .orEmpty()
        return nested.takeIf(::isEnrollmentUrl)
    }

    fun isEnrollmentUrl(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull() ?: return false
        return url.isHttps &&
            trustedHost(url.host) &&
            url.encodedPath.endsWith("/enroll") &&
            !url.queryParameter("token").isNullOrBlank()
    }

    fun enrollmentOrganization(value: String): String? {
        val url = value.trim().toHttpUrlOrNull()?.takeIf { isEnrollmentUrl(value) } ?: return null
        val segments = url.pathSegments.filter { it.isNotBlank() }
        return segments.takeIf { it.size >= 2 }?.get(segments.lastIndex - 1)
    }

    suspend fun redeem(context: Context, value: String): TrackerEnrollmentResult = withContext(Dispatchers.IO) {
        redeemBlocking(context, value)
    }

    fun redeemBlocking(context: Context, value: String): TrackerEnrollmentResult {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        CaltopoClient.CTDebug("OrgConfig", "Starting managed tracker enrollment request")
        val enrollmentUrl = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enrollment QR is not a valid URL.")
        if (!isEnrollmentUrl(value)) {
            throw IllegalArgumentException("Enrollment QR is not an r2c-tracker.com enrollment URL.")
        }
        val token = enrollmentUrl.queryParameter("token")
            ?: throw IllegalArgumentException("Enrollment QR has no token.")
        val redeemUrl = enrollmentUrl.newBuilder()
            .encodedPath("/api/v1/device-enrollment/redeem")
            .query(null)
            .fragment(null)
            .build()
        val payload = JSONObject()
            .put("token", token)
            .put("device_name", AndroidDeviceIdentity.localDisplayName(context))
            .put("device_model", AndroidDeviceIdentity.modelName())
            .put("platform", "android")
            // Use the same persistent device GUID that identifies this tablet to
            // CalTopo and peer coordination. Enrollment must not invent a second
            // identity or duplicate the GUID derivation algorithm.
            .put("installation_id", CaltopoMap.GetMyUUID())
            .put("functionality_release", BuildConfig.TRACKER_FUNCTIONALITY_RELEASE)
            .toString()
        val request = Request.Builder()
            .url(redeemUrl)
            .header("Accept", "application/json")
            .header(
                "X-R2C-Functionality-Release",
                BuildConfig.TRACKER_FUNCTIONALITY_RELEASE.toString()
            )
            .post(payload.toRequestBody(jsonMediaType))
            .apply {
                CaltopoClient.GetHomeTrackerApiKey().trim().takeIf { it.isNotEmpty() }?.let {
                    header("X-R2C-Previous-Device-Token", it)
                }
            }
            .build()
        return CaltopoSession.MyOkHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            CaltopoClient.CTDebug(
                "OrgConfig",
                "Managed tracker enrollment response status=${response.code} " +
                    "durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
            )
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                throw IllegalStateException(detail?.ifBlank { null } ?: "Enrollment failed (${response.code}).")
            }
            parseEnrollmentResult(body, value.trim())
        }
    }

    internal fun parseEnrollmentResult(body: String, enrollmentUrl: String): TrackerEnrollmentResult {
        val root = JSONObject(body)
        val organization = root.getJSONObject("organization")
        val tracker = root.getJSONObject("tracker")
        val credential = root.optJSONObject("credential")
        val reauthenticationUrl = credential
            ?.takeIf { it.optString("state") == "reauth_required" }
            ?.optString("reauthentication_url")
            ?.takeIf { isTrustedReauthenticationUrl(it) }
        return TrackerEnrollmentResult(
            organization = organization.optString("designator")
                .ifBlank { organization.getString("name") },
            trackerBaseUrl = tracker.getString("base_url"),
            deviceToken = tracker.getString("api_key"),
            faaProxyUrl = tracker.getString("faa_proxy_url"),
            enrollmentUrl = enrollmentUrl,
            reauthenticationUrl = reauthenticationUrl
        )
    }

    fun apply(result: TrackerEnrollmentResult) {
        CaltopoClient.SetHomeOrgName(result.organization)
        CaltopoClient.SetHomeTrackerCredentials(
            result.trackerBaseUrl,
            result.deviceToken
        )
        CaltopoClient.SetUsePeers(true)
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)
        CaltopoClient.SetTrackerFaaProxyUrl(result.faaProxyUrl)
        CaltopoClient.SetTrackerEnrollmentUrl(result.enrollmentUrl)
        CaltopoClient.SetNotamEnabled(true)
        if (result.reauthenticationUrl == null) {
            R2CApplication.getAppCtxt()?.let { context ->
                OrgConfigManager.syncManagedConfigurationAfterEnrollment(
                    context,
                    result.trackerBaseUrl,
                    result.deviceToken
                )
            }
        }
    }

    fun retryManagedConfigurationBootstrap(context: Context) {
        val trackerBaseUrl = CaltopoClient.GetHomeTrackerUrlPfx().trim()
        val deviceToken = CaltopoClient.GetHomeTrackerApiKey().trim()
        if (trackerBaseUrl.isBlank() || deviceToken.isBlank()) return
        OrgConfigManager.syncManagedConfigurationAfterEnrollment(
            context.applicationContext,
            trackerBaseUrl,
            deviceToken
        )
    }

    fun markDeviceReconciliationPending(context: Context) {
        context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(RECONCILIATION_PENDING, true)
            .apply()
    }

    fun isDeviceReconciliationPending(context: Context): Boolean =
        context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
            .getBoolean(RECONCILIATION_PENDING, false)

    fun clearDeviceReconciliationPending(context: Context) {
        context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(RECONCILIATION_PENDING)
            .apply()
    }

    suspend fun replacementCandidates(): List<TrackerDeviceReplacementCandidate> =
        withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(deviceAuthorizationUrl("replacement-candidates"))
            .header("Accept", "application/json")
            .header("X-SAR-Token", CaltopoClient.GetHomeTrackerApiKey().trim())
            .header(
                "X-R2C-Functionality-Release",
                BuildConfig.TRACKER_FUNCTIONALITY_RELEASE.toString()
            )
            .get()
            .build()
        CaltopoSession.MyOkHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(responseError(body, response.code))
            }
            parseReplacementCandidates(body)
        }
    }

    suspend fun replaceDeviceAuthorization(
        replacementCredentialId: String,
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("replacement_credential_id", replacementCredentialId)
            .toString()
        val request = Request.Builder()
            .url(deviceAuthorizationUrl("replace"))
            .header("Accept", "application/json")
            .header("X-SAR-Token", CaltopoClient.GetHomeTrackerApiKey().trim())
            .header(
                "X-R2C-Functionality-Release",
                BuildConfig.TRACKER_FUNCTIONALITY_RELEASE.toString()
            )
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        CaltopoSession.MyOkHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(responseError(body, response.code))
            }
            JSONObject(body).getString("canonical_device_name")
        }
    }

    internal fun parseReplacementCandidates(
        body: String,
    ): List<TrackerDeviceReplacementCandidate> {
        val candidates = JSONObject(body).optJSONArray("candidates") ?: return emptyList()
        return buildList {
            for (index in 0 until candidates.length()) {
                val candidate = candidates.optJSONObject(index) ?: continue
                val credentialId = candidate.optString("credential_id").trim()
                val deviceName = candidate.optString("device_name").trim()
                val deviceModel = candidate.optString("device_model").trim()
                if (credentialId.isNotEmpty() && deviceName.isNotEmpty()) {
                    add(TrackerDeviceReplacementCandidate(
                        credentialId = credentialId,
                        deviceName = deviceName,
                        deviceModel = deviceModel,
                    ))
                }
            }
        }
    }

    private fun deviceAuthorizationUrl(operation: String) =
        CaltopoClient.GetHomeTrackerUrlPfx().trim().toHttpUrlOrNull()
            ?.newBuilder()
            ?.encodedPath("/api/v1/device-authorization/$operation")
            ?.query(null)
            ?.fragment(null)
            ?.build()
            ?: throw IllegalStateException("Tracker address is not configured.")

    private fun responseError(body: String, code: Int): String {
        val detail = runCatching { JSONObject(body).opt("detail") }.getOrNull()
        return when (detail) {
            is String -> detail.ifBlank { "Tracker request failed ($code)." }
            is JSONObject -> detail.optString("message").ifBlank {
                "Tracker request failed ($code)."
            }
            else -> "Tracker request failed ($code)."
        }
    }

    private fun trustedHost(host: String): Boolean =
        host.equals("r2c-tracker.com", ignoreCase = true) ||
            host.endsWith(".r2c-tracker.com", ignoreCase = true)

    private fun isTrustedReauthenticationUrl(value: String): Boolean {
        val url = value.trim().toHttpUrlOrNull() ?: return false
        return url.isHttps && trustedHost(url.host)
    }
}

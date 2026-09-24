/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import org.ncssar.rid2caltopo.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Manages the org-config bundle workflow:
 *   - Admin: build a bundle from current app state, encrypt credentials,
 *     upload to Drive, generate join token
 *   - Member: decode a join token, download bundle from Drive, decrypt
 *     credentials, apply to app state
 *
 * Security model:
 *   The Drive file is publicly readable (anyone with the file ID can download
 *   it).  The ct_ridmap block is stored in plaintext — it contains no secrets.
 *   The credential-bearing blocks are XOR-obfuscated with an app-specific key
 *   before upload and decrypted locally after download, so credentials are
 *   never stored in plaintext on Drive.  The file ID is embedded in the QR
 *   token using the same obfuscation layer, so casual scanners see nothing useful.
 *
 * Tokens are stored in SharedPreferences so the app remembers which org it has
 * joined (useful for display in the UI and future re-apply).
 */
object OrgConfigManager {

    private const val TAG = "OrgConfigManager"
    private const val PREFS = "org_config_join"
    private const val KEY_TOKEN = "join_token"
    private const val KEY_ORG_NAME = "org_name"
    private const val KEY_MANAGED_VERSION_MS = "managed_version_ms"

    // Type tags used in the bundle JSON to mark encrypted credential blocks.
    private const val TYPE_CREDENTIALS_ENC = "ct_credentials_enc"
    private const val TYPE_CREDENTIALS     = "ct_credentials"

    internal fun trackerEnrollmentUrl(bundleJson: String): String? = runCatching {
        val root = JSONObject(bundleJson)
        if (root.optString("format") != "rid2caltopo_org_config" || root.optInt("version") != 2) {
            return@runCatching null
        }
        val configs = root.optJSONArray("configs") ?: return@runCatching null
        var enrollmentUrl: String? = null
        for (index in 0 until configs.length()) {
            val config = configs.optJSONObject(index) ?: continue
            val type = config.optString("type").trim().lowercase()
            if (type.startsWith("ct_faa_")) return@runCatching null
            if (type == TYPE_CREDENTIALS) {
                val forbiddenFields = listOf(
                    "tracker_api_key",
                    "tracker_url_pfx",
                    "tracker_url_prefix",
                    "tracker_faa_proxy_url",
                    "notam_client_id",
                    "notam_client_secret"
                )
                if (forbiddenFields.any { config.optString(it).isNotBlank() }) {
                    return@runCatching null
                }
                enrollmentUrl = config.optString("tracker_enrollment_url")
                    .takeIf(TrackerEnrollmentClient::isEnrollmentUrl)
            }
        }
        enrollmentUrl
    }.getOrNull()
    private const val TYPE_MUTUAL_AID_CREDENTIALS = "ct_mutual_aid_credentials"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Token / org-name persistence ──────────────────────────────────────────

    @JvmStatic
    fun storeToken(context: Context, token: String) {
        val orgName = OrgConfigToken.decode(token)?.orgName
            ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_ORG_NAME, orgName)
            .apply()
    }

    @JvmStatic
    fun getStoredToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)

    @JvmStatic
    fun getStoredOrgName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORG_NAME, "") ?: ""

    @JvmStatic
    fun hasJoinedOrg(context: Context): Boolean = !getStoredToken(context).isNullOrBlank()

    @JvmStatic
    fun getManagedVersionMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_MANAGED_VERSION_MS, 0L)

    /** Persist just the org name (e.g. to survive a Drive auth round-trip). */
    @JvmStatic
    fun storeOrgName(context: Context, orgName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ORG_NAME, orgName)
            .apply()
    }

    // ── Credential encryption helpers ─────────────────────────────────────────

    /**
     * Walk the "configs" array in [bundleJson] and replace any credential-bearing
     * object with an encrypted surrogate containing only
     * { "type": "ct_credentials_enc", "enc": "<XOR-base64>" }.
     */
    private fun encryptCredentialsInBundle(bundleJson: String): String {
        val bundle = JSONObject(bundleJson)
        val configs = bundle.optJSONArray("configs") ?: return bundleJson
        val secured = JSONArray()
        for (i in 0 until configs.length()) {
            val item = configs.getJSONObject(i)
            val type = item.optString("type")
            if (type == TYPE_CREDENTIALS || type == TYPE_MUTUAL_AID_CREDENTIALS) {
                val enc = OrgConfigToken.encryptPayload(item.toString())
                secured.put(JSONObject().put("type", TYPE_CREDENTIALS_ENC).put("enc", enc))
            } else {
                secured.put(item)
            }
        }
        bundle.put("configs", secured)
        return bundle.toString()
    }

    /**
     * Walk the "configs" array in [bundleJson] and replace any
     * "ct_credentials_enc" surrogate with the decrypted original object.
     */
    private fun decryptCredentialsInBundle(bundleJson: String): String {
        val bundle = JSONObject(bundleJson)
        val configs = bundle.optJSONArray("configs") ?: return bundleJson
        val restored = JSONArray()
        for (i in 0 until configs.length()) {
            val item = configs.getJSONObject(i)
            if (item.optString("type") == TYPE_CREDENTIALS_ENC) {
                val plaintext = OrgConfigToken.decryptPayload(item.getString("enc"))
                restored.put(JSONObject(plaintext))
            } else {
                restored.put(item)
            }
        }
        bundle.put("configs", restored)
        return bundle.toString()
    }

    /** Build the narrow tracker-managed snapshot. Device enrollment and app policy are excluded. */
    @JvmStatic
    fun buildManagedSnapshot(): JSONObject {
        val raw = CaltopoClient.BuildOrgConfigBundle(CaltopoClient.GetHomeOrgName())
            ?: throw IllegalStateException("Failed to build organization configuration.")
        val configs = JSONObject(raw).getJSONArray("configs")
        var orgCredentials: Map<String, String>? = null
        var mutualAidCredentials: Map<String, String>? = null
        var drones = JSONArray()
        for (index in 0 until configs.length()) {
            val item = configs.getJSONObject(index)
            when (item.optString("type")) {
                "ct_credentials" -> orgCredentials = credentialValues(
                    item,
                    listOf("type", "file_version", "team_id", "credential_id",
                        "credential_secret", "domain_and_port", "connect_key", "track_folder")
                )
                TYPE_MUTUAL_AID_CREDENTIALS -> mutualAidCredentials = credentialValues(
                    item,
                    listOf("type", "file_version", "team_id", "credential_id",
                        "credential_secret", "domain_and_port", "connect_key",
                        "source_label", "target_folder_hint")
                )
                "ct_ridmap" -> drones = item.optJSONArray("map") ?: JSONArray()
            }
        }
        val credentials = orgCredentials
            ?: throw IllegalStateException("Organization CalTopo credentials are not configured.")
        val droneSpecs = JSONArray()
        for (index in 0 until drones.length()) {
            droneSpecs.put(filteredObject(
                drones.getJSONObject(index),
                listOf("remoteId", "mappedId", "org", "model", "owner", "ownerName", "ownerCallsign", "readiness")
            ))
        }
        return JSONObject()
            .put("configSchemaVersion", 1)
            .put("aircraftSchemaVersion", 1)
            .put("sourcePlatform", "android")
            .put("sourceAppVersion", BuildConfig.VERSION_NAME)
            .put("sourceAppBuild", BuildConfig.VERSION_CODE)
            .put("organizationCaltopoEnc", OrgConfigToken.encryptPayload(
                OrgConfigToken.canonicalCredentialPayload(credentials)
            ))
            .put("mutualAidCaltopoEnc", mutualAidCredentials?.let {
                OrgConfigToken.encryptPayload(OrgConfigToken.canonicalCredentialPayload(it))
            } ?: "")
            .put("droneSpecs", droneSpecs)
    }

    private fun filteredObject(source: JSONObject, names: List<String>): JSONObject {
        val result = JSONObject()
        names.forEach { name -> if (source.has(name)) result.put(name, source.get(name)) }
        return result
    }

    private fun credentialValues(source: JSONObject, names: List<String>): Map<String, String> =
        names.associateWith { source.optString(it, "") }

    @JvmStatic
    fun applyManagedSnapshot(context: Context, snapshot: JSONObject, versionMs: Long): Boolean {
        if (snapshot.optInt("configSchemaVersion") != 1 || versionMs <= 0L) return false
        val currentRaw = CaltopoClient.BuildOrgConfigBundle(CaltopoClient.GetHomeOrgName())
            ?: return false
        val current = JSONObject(currentRaw)
        val currentConfigs = current.getJSONArray("configs")
        val rebuilt = JSONArray()
        val managedCredentials = JSONObject(
            OrgConfigToken.decryptPayload(snapshot.getString("organizationCaltopoEnc"))
        )
        for (index in 0 until currentConfigs.length()) {
            val item = currentConfigs.getJSONObject(index)
            when (item.optString("type")) {
                "ct_ridmap", TYPE_MUTUAL_AID_CREDENTIALS -> Unit
                "ct_credentials" -> {
                    val keys = managedCredentials.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        item.put(key, managedCredentials.get(key))
                    }
                    rebuilt.put(item)
                }
                else -> rebuilt.put(item)
            }
        }
        val ridmap = JSONObject().put("type", "ct_ridmap").put("file_version", "1.0")
            .put("load_type", "replace").put("map", snapshot.getJSONArray("droneSpecs"))
        rebuilt.put(ridmap)
        val mutualEnc = snapshot.optString("mutualAidCaltopoEnc")
        if (mutualEnc.isNotBlank()) {
            rebuilt.put(JSONObject(OrgConfigToken.decryptPayload(mutualEnc)))
        } else {
            CaltopoClient.SetMutualAidTemplateFields("", "", "", "", "", "")
        }
        current.put("configs", rebuilt)
        val applied = AircraftOrganizationAccess.applyingManagedDownload { CaltopoClient.ApplyOrgConfigBundle(current.toString()) }
        if (applied) {
            CaltopoClient.SetTrackerManagedCaltopoCredentials(
                CaltopoClient.GetCaltopoCredentials()
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_MANAGED_VERSION_MS, versionMs)
                .putInt("managed_readiness_schema_version", snapshot.optInt("aircraftSchemaVersion", 0))
                .apply()
        }
        return applied
    }

    @JvmStatic
    fun invalidateManagedConfigurationVersion(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_MANAGED_VERSION_MS)
            .apply()
    }

    @JvmStatic
    fun syncManagedConfiguration(
        context: Context,
        trackerOrigin: String,
        organization: String,
        deviceToken: String,
        advertisedVersionMs: Long
    ) {
        syncManagedConfiguration(
            context,
            trackerOrigin,
            organization,
            deviceToken,
            advertisedVersionMs,
            null
        )
    }

    @JvmStatic
    fun syncManagedConfiguration(
        context: Context,
        trackerOrigin: String,
        organization: String,
        deviceToken: String,
        advertisedVersionMs: Long,
        completion: Runnable?
    ) {
        if (advertisedVersionMs == 0L || advertisedVersionMs == getManagedVersionMs(context)) {
            completion?.run()
            return
        }
        enqueueManagedConfigurationSync(
            context,
            "${trackerOrigin.trimEnd('/')}/${organization.lowercase()}/api/v1/organization-config/current",
            deviceToken,
            completion
        )
    }

    /** Bootstrap a newly enrolled device before it has a CalTopo map or coordination socket. */
    @JvmStatic
    fun syncManagedConfigurationAfterEnrollment(
        context: Context,
        trackerBaseUrl: String,
        deviceToken: String
    ) {
        enqueueManagedConfigurationSync(
            context,
            "${trackerBaseUrl.trimEnd('/')}/api/v1/organization-config/current",
            deviceToken,
            null
        )
    }

    private fun enqueueManagedConfigurationSync(
        context: Context,
        endpoint: String,
        deviceToken: String,
        completion: Runnable?
    ) {
        executor.execute {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("X-SAR-Token", deviceToken)
                    .header(
                        "X-R2C-Functionality-Release",
                        BuildConfig.TRACKER_FUNCTIONALITY_RELEASE.toString()
                    )
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                CaltopoSession.MyOkHttpClient.newCall(request).execute().use { response ->
                    if (response.code == 204) {
                        CaltopoClient.CTInfo(TAG, "No managed organization configuration is published.")
                        return@use
                    }
                    if (!response.isSuccessful) {
                        TrackerEnrollmentClient.handleRejectedAuthorization(
                            response.code, response.body?.string().orEmpty(), deviceToken
                        )
                        throw IllegalStateException(
                            "Tracker returned HTTP ${response.code} for organization configuration."
                        )
                    }
                    val root = JSONObject(response.body?.string().orEmpty())
                    val versionMs = root.getLong("versionMs")
                    val needsApply = versionMs != getManagedVersionMs(context) ||
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("managed_readiness_schema_version", 0) < 1 ||
                        !CaltopoCredentials.sniffTest(CaltopoClient.GetCaltopoCredentials())
                    if (needsApply &&
                        !applyManagedSnapshot(context, root.getJSONObject("config"), versionMs)) {
                        throw IllegalStateException("Organization configuration could not be applied.")
                    }
                    CaltopoClient.CTInfo(TAG, "Applied managed organization configuration $versionMs")
                }
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "Managed organization configuration sync failed.", e)
            } finally {
                completion?.run()
            }
        }
    }

    // ── Admin: upload org config and return join token ─────────────────────────

    /**
     * Export the current app's ridmap + credentials to Drive, make the file
     * publicly readable, and encode the file ID into a join token.
     *
     * Credentials are XOR-encrypted before upload so the Drive file is never
     * plaintext credentials even though it is publicly readable.
     *
     * Runs on a background thread.  [callback] is called on the main thread with
     * (success, statusMessage, tokenOrNull).
     */
    @JvmStatic
    fun uploadOrgConfig(
        context: Context,
        account: GoogleSignInAccount,
        callback: (Boolean, String, String?) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = try {
                val orgName = CaltopoClient.GetHomeOrgName().ifBlank {
                    throw IllegalStateException("Set the organization designator in Settings before exporting organization config.")
                }
                val enrollmentUrl = CaltopoClient.GetTrackerEnrollmentUrl()
                if (!CaltopoClient.GetHomeTrackerApiKey().startsWith("r2c_dev_") ||
                    !TrackerEnrollmentClient.enrollmentOrganization(enrollmentUrl)
                        .equals(orgName, ignoreCase = true)
                ) {
                    throw IllegalStateException(
                        "Scan a current r2c-tracker device-enrollment QR before exporting R2C2."
                    )
                }
                val rawBundle = CaltopoClient.BuildOrgConfigBundle(orgName)
                    ?: throw IllegalStateException("Failed to build org config bundle.")
                val securedBundle = encryptCredentialsInBundle(rawBundle)
                val fileId = GoogleDriveConfigSync.uploadOrgConfigFile(
                    appContext, account, securedBundle, orgName
                )
                val token = OrgConfigToken.encode(
                    OrgConfigToken.OrgConfig(
                        orgName     = orgName,
                        driveFileId = fileId,
                        isPublic    = true,
                        version     = 2
                    )
                )
                storeToken(appContext, token)
                CaltopoClient.CTDebug(TAG, "uploadOrgConfig(): upload complete, org='$orgName'")
                Triple(true, "Org config uploaded to Drive.", token)
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "uploadOrgConfig() failed.", e)
                Triple(false, e.message ?: "Upload failed.", null)
            }
            mainHandler.post { callback(result.first, result.second, result.third) }
        }
    }

    // ── Member: download and apply org config ──────────────────────────────────

    /**
     * Decode [token], obtain the org config bundle from its public locator,
     * decrypt the credentials block,
     * and apply the bundle to the current app state.
     *
     * Runs on a background thread.  [callback] is called on the main thread with
     * (success, statusMessage).
     */
    @JvmStatic
    fun joinFromToken(
        context: Context,
        token: String,
        callback: (Boolean, String) -> Unit
    ) {
        val trimmed = token.trim()
        val config = OrgConfigToken.decode(trimmed)
        if (config == null) {
            callback(false, "Invalid join token.")
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val result = try {
                val encJson = GoogleDriveConfigSync.downloadOrgConfigPublic(config.driveFileId)
                val plainJson = decryptCredentialsInBundle(encJson)
                val orgName = config.orgName.ifBlank {
                    JSONObject(plainJson).optString("org_name")
                }.ifBlank { throw IllegalStateException("R2C2 bundle has no organization name.") }
                val enrollmentUrl = trackerEnrollmentUrl(plainJson)
                    ?: throw IllegalStateException("R2C2 bundle has no valid tracker enrollment locator.")
                if (!TrackerEnrollmentClient.enrollmentOrganization(enrollmentUrl)
                        .equals(orgName, ignoreCase = true)
                ) {
                    throw IllegalStateException("R2C2 tracker enrollment does not belong to $orgName.")
                }
                val success = CaltopoClient.ApplyOrgConfigBundle(plainJson)
                if (success) {
                    val enrollment = TrackerEnrollmentClient.redeemBlocking(appContext, enrollmentUrl)
                    if (!enrollment.organization.equals(orgName, ignoreCase = true)) {
                        throw IllegalStateException("Tracker enrolled a different organization than the R2C2 bundle.")
                    }
                    TrackerEnrollmentClient.apply(enrollment)
                    storeToken(appContext, trimmed)
                    CaltopoClient.CTDebug(TAG, "joinFromToken(): joined R2C2 org='$orgName' with per-device enrollment")
                    true to "Joined '$orgName'. Team config applied and device enrolled with r2c-tracker."
                } else {
                    false to "Org config downloaded but could not be applied."
                }
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "joinFromToken() failed.", e)
                false to (e.message ?: "Failed to download org config.")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }
}

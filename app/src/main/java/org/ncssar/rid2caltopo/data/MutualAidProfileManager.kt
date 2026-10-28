package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors

object MutualAidProfileManager {
    private const val TAG = "MutualAidProfileMgr"
    private const val PREFS = "mutual_aid_profile"
    private const val KEY_TOKEN = "join_token"

    private const val FORMAT = "rid2caltopo_mutual_aid_profile"
    private const val TYPE_PROFILE_ENC = "caltopo_profile_enc"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun storeToken(context: Context, token: String) {
        val config = MutualAidToken.decode(token) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    @JvmStatic
    fun getStoredToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    @JvmStatic
    fun clearStoredToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @JvmStatic
    fun defaultExpiryAtNextMidnight(): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }



    @JvmStatic
    fun applyBundle(context: Context, json: String): Pair<Boolean, String> {
        return false to "Legacy credential transfers are no longer supported. Scan a tracker enrollment QR."
    }

    @JvmStatic
    fun buildEncryptedActiveProfilePayload(): String? {
        val profile = CaltopoClient.GetActiveCaltopoProfile() ?: return null
        val profileJson = JSONObject()
            .put("profile_id", profile.profileId)
            .put("display_name", profile.displayName)
            .put("team_id", profile.credentials.teamId ?: "")
            .put("credential_id", profile.credentials.credentialId ?: "")
            .put("credential_secret", profile.credentials.credentialSecret ?: "")
            .put("domain_and_port", profile.domainAndPort)
            .put("connect_key", profile.connectKey)
            .put("track_folder", profile.trackFolder)
            .put("incident", profile.incident)
            .put("op_period", profile.opPeriod)
            .put("tracker_api_key", profile.trackerApiKey)
            .put("tracker_url_prefix", profile.trackerUrlPfx)
            .put("auto_connect", profile.autoConnect)
            .put("expires_at_epoch_ms", profile.expiresAtEpochMs)
            .put("quiet_remove_on_expiry", profile.quietRemoveOnExpiry)
            .put("source_label", profile.sourceLabel)
            .put("target_map_id", profile.targetMapId)
            .put("target_map_title", profile.targetMapTitle)
            .put("target_folder_hint", profile.targetFolderHint)
            .put("imported_at_epoch_ms", profile.importedAtEpochMs)
            .put("import_dedupe_key", profile.importDedupeKey)
        return MutualAidToken.encryptPayload(profileJson.toString())
    }



    @JvmStatic
    fun installEncryptedProfilePayload(encryptedPayload: String): Pair<Boolean, String> {
        return false to "Legacy credential transfers are no longer supported. Scan a tracker enrollment QR."
    }

    @JvmStatic
    fun joinFromToken(
        context: Context,
        token: String,
        callback: (Boolean, String) -> Unit
    ) {
        mainHandler.post { callback(false, "Legacy credential transfers are no longer supported. Scan a tracker enrollment QR.") }
    }

    private fun parseProfile(json: JSONObject): CaltopoProfileRecord {
        return CaltopoProfileRecord(
            json.optString("profile_id"),
            json.optString("display_name").ifBlank { "Organization" },
            "MUTUAL_AID",
            CaltopoCredentials(
                json.optString("team_id"),
                json.optString("credential_id"),
                json.optString("credential_secret")
            ),
            json.optString("domain_and_port", "caltopo.com"),
            json.optString("track_folder", "Drone Tracks"),
            json.optString("incident", "Training"),
            json.optString("op_period", "1"),
            json.optString("tracker_api_key"),
            json.optString("tracker_url_prefix"),
            json.optBoolean("auto_connect", true),
            json.optLong("expires_at_epoch_ms", 0L),
            json.optBoolean("quiet_remove_on_expiry", true),
            json.optString("source_label"),
            json.optString("target_map_id"),
            json.optString("target_map_title"),
            json.optString("target_folder_hint"),
            json.optLong("imported_at_epoch_ms", System.currentTimeMillis()),
            json.optString("import_dedupe_key")
        ).also {
            it.connectKey = json.optString("connect_key")
        }
    }

    private fun buildBundle(sourceOrg: String, profile: CaltopoProfileRecord): String {
        val bundle = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("generated", CaltopoClient.TimeDatestampString(System.currentTimeMillis()))
            .put("source_org", sourceOrg)
        val profileJson = JSONObject()
            .put("profile_id", profile.profileId)
            .put("display_name", profile.displayName)
            .put("team_id", profile.credentials.teamId ?: "")
            .put("credential_id", profile.credentials.credentialId ?: "")
            .put("credential_secret", profile.credentials.credentialSecret ?: "")
            .put("domain_and_port", profile.domainAndPort)
            .put("connect_key", profile.connectKey)
            .put("track_folder", profile.trackFolder)
            .put("incident", profile.incident)
            .put("op_period", profile.opPeriod)
            .put("tracker_api_key", profile.trackerApiKey)
            .put("tracker_url_prefix", profile.trackerUrlPfx)
            .put("auto_connect", profile.autoConnect)
            .put("expires_at_epoch_ms", profile.expiresAtEpochMs)
            .put("quiet_remove_on_expiry", profile.quietRemoveOnExpiry)
            .put("source_label", profile.sourceLabel)
            .put("target_map_id", profile.targetMapId)
            .put("target_map_title", profile.targetMapTitle)
            .put("target_folder_hint", profile.targetFolderHint)
            .put("imported_at_epoch_ms", profile.importedAtEpochMs)
            .put("import_dedupe_key", profile.importDedupeKey)
        val encryptedProfile = JSONObject()
            .put("type", TYPE_PROFILE_ENC)
            .put("enc", MutualAidToken.encryptPayload(profileJson.toString()))
        bundle.put("profile", encryptedProfile)
        return bundle.toString()
    }

    private fun buildProfile(
        sourceOrg: String,
        displayName: String,
        incident: String,
        opPeriod: String,
        targetMapId: String,
        targetMapTitle: String,
        expiresAtEpochMs: Long
    ): CaltopoProfileRecord {
        val template = CaltopoClient.GetMutualAidTemplate()
        require(CaltopoClient.HasMutualAidTemplate()) { "Configure the organization account in Settings before preparing managed access." }
        val templateCreds = CaltopoCredentials(template.teamId, template.credentialId, template.credentialSecret)
        val homeCreds = CaltopoClient.GetCaltopoProfiles()
            .firstOrNull { it.profileType == "HOME" }
            ?.credentials
        require(homeCreds == null || !CaltopoCredentials.credentialsAreEqual(templateCreds, homeCreds)) {
            "MA credentials match home org credentials; refusing export."
        }
        val active = CaltopoClient.GetActiveCaltopoProfile()
        val normalizedIncident = incident.ifBlank { CaltopoClient.GetIncident() }
        val normalizedOpPeriod = opPeriod.ifBlank { CaltopoClient.GetOpPeriod() }
        val normalizedMapId = targetMapId.ifBlank { CaltopoMap.GetMapId() }
        val normalizedMapTitle = targetMapTitle.ifBlank { CaltopoMap.GetMapName() }
        val finalDisplayName = displayName.ifBlank {
            listOf(sourceOrg, normalizedIncident, "OP$normalizedOpPeriod").joinToString(" ").trim()
        }
        val trackerApiKey = CaltopoClient.GetTrackerApiKey()
        val trackerUrlPfx = CaltopoClient.GetTrackerUrlPfx()
        val importedAt = System.currentTimeMillis()
        val dedupeKey = listOf(
            sourceOrg.trim(),
            normalizedIncident.trim(),
            normalizedOpPeriod.trim()
        ).joinToString("|")
        return CaltopoProfileRecord(
            "mai-${sanitizeBundleName(sourceOrg)}-${sanitizeBundleName(normalizedIncident)}-op${sanitizeBundleName(normalizedOpPeriod)}",
            finalDisplayName,
            "MUTUAL_AID",
            templateCreds,
            template.domainAndPort.ifBlank { active?.domainAndPort ?: CaltopoClient.GetCaltopoDomainAndPort() },
            active?.trackFolder ?: CaltopoClient.GetTrackFolderName(),
            normalizedIncident,
            normalizedOpPeriod,
            trackerApiKey,
            trackerUrlPfx,
            true,
            expiresAtEpochMs,
            true,
            sourceOrg.ifBlank { template.sourceLabel },
            normalizedMapId,
            normalizedMapTitle,
            template.targetFolderHint.ifBlank { active?.targetFolderHint ?: "MAI" },
            importedAt,
            dedupeKey
        ).also { it.connectKey = template.connectKey.orEmpty() }
    }

    private fun sanitizeBundleName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return if (cleaned.isBlank()) "mutual_aid" else cleaned
    }

    private fun maskId(value: String?): String {
        val normalized = value.orEmpty().trim()
        if (normalized.isBlank()) return ""
        if (normalized.length <= 4) return normalized
        return "..." + normalized.takeLast(4)
    }
}

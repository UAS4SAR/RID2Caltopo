package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import org.json.JSONObject
import org.ncssar.rid2caltopo.notam.NotamAuthManager
import java.util.concurrent.Executors

object FaaConfigManager {
    private const val TAG = "FaaConfigManager"
    const val TYPE_PLAINTEXT = "ct_faa_credentials"
    const val TYPE_ENCRYPTED = "ct_faa_credentials_enc"
    const val TYPE_REMOTE = "ct_faa_remote_config"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isFaaTokenText(text: String): Boolean =
        FaaConfigToken.isValidToken(text.trim())

    @JvmStatic
    fun tryHandleRawConfigText(context: Context?, text: String): Boolean {
        return false
    }

    @JvmStatic
    fun readConfigObject(json: JSONObject): Boolean {
        return false
    }

    @JvmStatic
    fun importPlaintextConfig(json: JSONObject) {
        throw IllegalArgumentException("Legacy credential transfers are no longer supported. Scan a tracker enrollment QR.")
    }

    @JvmStatic
    fun importLegacyCredentialsFromJson(json: JSONObject) {
        // Legacy credential files are not accepted.
    }

    @JvmStatic
    fun encryptedPayloadForPlaintext(json: JSONObject): String {
        val normalized = normalizePlaintextConfig(json)
        val enc = FaaConfigToken.encryptPayload(normalized.toString())
        return JSONObject()
            .put("type", TYPE_ENCRYPTED)
            .put("file_version", "1.0")
            .put("enc", enc)
            .toString()
    }

    @JvmStatic
    fun readRemoteConfigObject(json: JSONObject) {
        throw IllegalArgumentException("Legacy credential transfers are no longer supported. Scan a tracker enrollment QR.")
    }

    @JvmStatic
    fun buildRemoteConfigObject(): JSONObject? {
        val token = CaltopoClient.GetFaaRemoteToken()
        val payload = CaltopoClient.GetFaaPayloadEnc()
        if (token.isBlank() && payload.isBlank()) return null
        return JSONObject()
            .put("type", TYPE_REMOTE)
            .put("file_version", "1.0")
            .put("faa_token", token)
            .put("label", CaltopoClient.GetFaaConfigLabel())
            .put("faa_payload_enc", payload)
            .put("last_validated_epoch_ms", CaltopoClient.GetFaaLastValidatedEpochMs())
            .put("stale", CaltopoClient.GetFaaConfigStale())
            .put("last_failure_reason", CaltopoClient.GetFaaLastFailureReason())
    }

    internal fun applyCachedPayloadToState(state: ClientClassState) {
        val payload = state.faaPayloadEnc
        if (payload.isNullOrBlank()) return
        val json = decryptEncryptedPayload(payload) ?: return
        state.notamApiBaseUrl = json.optString("notam_api_base_url")
        state.notamTokenUrl = json.optString("notam_token_url")
        state.notamClientId = json.optString("notam_client_id")
        state.notamClientSecret = json.optString("notam_client_secret")
        state.notamScope = json.optString("notam_scope")
    }

    @JvmStatic
    fun refreshIfNeededOnStartup(context: Context) {
        // Tracker provisioning replaces legacy Drive refresh.
    }

    @JvmStatic
    fun importToken(
        context: Context?,
        token: String,
        callback: (Boolean, String) -> Unit
    ) {
        mainHandler.post { callback(false, "Legacy credential transfers are no longer supported. Scan a tracker enrollment QR.") }
    }



    @JvmStatic
    fun markAuthorizationFailure(reason: String) {
        if (CaltopoClient.GetFaaRemoteToken().isBlank() && CaltopoClient.GetFaaPayloadEnc().isBlank()) return
        CaltopoClient.MarkFaaConfigStale(reason)
    }

    private fun applyEncryptedPayload(encryptedJson: String, remoteToken: String, validate: Boolean) {
        val json = decryptEncryptedPayload(encryptedJson)
            ?: throw IllegalArgumentException("FAA config payload could not be decoded.")
        applyPlaintextConfig(json)
        if (validate) {
            validateCurrentCredentials()
        }
        val token = remoteToken.ifBlank { CaltopoClient.GetFaaRemoteToken() }
        CaltopoClient.StoreFaaRemoteConfig(
            token,
            json.optString("source_label", json.optString("label", CaltopoClient.GetFaaConfigLabel())),
            encryptedJson,
            if (validate) System.currentTimeMillis() else CaltopoClient.GetFaaLastValidatedEpochMs(),
            false,
            ""
        )
    }

    private fun decryptEncryptedPayload(encryptedJson: String): JSONObject? {
        return try {
            val wrapper = JSONObject(encryptedJson)
            if (wrapper.optString("type") != TYPE_ENCRYPTED) return null
            JSONObject(FaaConfigToken.decryptPayload(wrapper.getString("enc")))
        } catch (e: Exception) {
            CaltopoClient.CTWarn(TAG, "decryptEncryptedPayload() failed.", e)
            null
        }
    }

    private fun normalizePlaintextConfig(json: JSONObject): JSONObject =
        JSONObject()
            .put("type", TYPE_PLAINTEXT)
            .put("file_version", json.optString("file_version", "1.0"))
            .put("updated", json.optString("updated"))
            .put("source_label", json.optString("source_label", json.optString("label", "RID2Caltopo FAA NOTAM credentials")))
            .put("notam_api_base_url", json.optString("notam_api_base_url"))
            .put("notam_token_url", json.optString("notam_token_url"))
            .put("notam_client_id", json.optString("notam_client_id"))
            .put("notam_client_secret", json.optString("notam_client_secret"))
            .put("notam_scope", json.optString("notam_scope"))

    private fun applyPlaintextConfig(json: JSONObject) {
        val clientId = json.optString("notam_client_id")
        val clientSecret = json.optString("notam_client_secret")
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) {
            "FAA config is missing NOTAM client credentials."
        }
        json.optString("notam_api_base_url").takeIf { it.isNotBlank() }?.let(CaltopoClient::SetNotamApiBaseUrl)
        json.optString("notam_token_url").takeIf { it.isNotBlank() }?.let(CaltopoClient::SetNotamTokenUrl)
        CaltopoClient.SetNotamClientId(clientId)
        CaltopoClient.SetNotamClientSecret(clientSecret)
        CaltopoClient.SetNotamScope(json.optString("notam_scope"))
        CaltopoClient.SetNotamAutoRefresh(true)
    }

    private fun buildPlaintextFromCurrent(): JSONObject? {
        val clientId = CaltopoClient.GetNotamClientId()
        val clientSecret = CaltopoClient.GetNotamClientSecret()
        if (clientId.isBlank() || clientSecret.isBlank()) return null
        return JSONObject()
            .put("type", TYPE_PLAINTEXT)
            .put("file_version", "1.0")
            .put("updated", CaltopoClient.TimeDatestampString(System.currentTimeMillis()))
            .put("source_label", CaltopoClient.GetFaaConfigLabel().ifBlank { "RID2Caltopo FAA NOTAM credentials" })
            .put("notam_api_base_url", CaltopoClient.GetNotamApiBaseUrl())
            .put("notam_token_url", CaltopoClient.GetNotamTokenUrl())
            .put("notam_client_id", clientId)
            .put("notam_client_secret", clientSecret)
            .put("notam_scope", CaltopoClient.GetNotamScope())
    }

    private fun validateCurrentCredentials() {
        if (!NotamAuthManager.isConfigured()) {
            throw NotamAuthManager.NotamAuthException.Service(
                "FAA NOTAM proxy access requires an r2c-tracker organization QR code."
            )
        }
    }
}

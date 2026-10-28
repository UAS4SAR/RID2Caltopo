package org.ncssar.rid2caltopo.data

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class DriveSyncAction {
    RESTORE,
    BACKUP
}

object GoogleDriveConfigSync {
    private const val TAG = "DriveConfigSync"
    private const val PREFS = "google_drive_config_sync"
    private const val KEY_ACCOUNT_EMAIL = "account_email"
    private const val KEY_LAST_UPLOAD_MS = "last_upload_ms"
    private const val KEY_LAST_RESTORE_MS = "last_restore_ms"
    private const val KEY_CANONICAL_FILE_ID = "canonical_file_id"
    private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    private const val DRIVE_FILE_NAME = "RID2Caltopo Config.pb"
    private const val LEGACY_DRIVE_FILE_NAME = "rid2caltopo_app_config.pb"
    private const val APP_PROPERTY_KEY = "rid2caltopo_kind"
    private const val APP_PROPERTY_VALUE = "config_v2"
    private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

    // Org-config folder and files — separate from the personal backup protobuf.
    // Files are named <orgName>_Config.json and kept in a shared folder for easy
    // administration when one person manages configs for multiple SAR orgs.
    private const val ORG_CONFIG_FOLDER_NAME = "RID2Caltopo_Configs"
    private const val ORG_APP_PROPERTY_VALUE = "org_config_v1"
    private const val KEY_ORG_CONFIG_FOLDER_ID = "org_config_folder_id"
    private const val MUTUAL_AID_FOLDER_NAME = "RID2Caltopo_MutualAid"
    private const val MUTUAL_AID_APP_PROPERTY_VALUE = "mutual_aid_profile_v1"
    private const val KEY_MUTUAL_AID_FOLDER_ID = "mutual_aid_folder_id"
    private const val FAA_CONFIG_FILE_NAME = "RID2Caltopo_FAA_Config.json"
    private const val FAA_CONFIG_APP_PROPERTY_VALUE = "faa_config_v1"
    private const val DRIVE_PERMISSIONS_URL = "https://www.googleapis.com/drive/v3/files/%s/permissions"
    // Public (unauthenticated) download URL for files shared with "anyone with link".
    private const val PUBLIC_DOWNLOAD_URL = "https://drive.google.com/uc?export=download&id=%s"
    private const val HTTP_CONNECT_TIMEOUT_SECONDS = 20L
    private const val HTTP_READ_TIMEOUT_SECONDS = 45L
    private const val HTTP_CALL_TIMEOUT_SECONDS = 90L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(HTTP_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class DriveConfigFile(
        val id: String,
        val canEdit: Boolean
    )

    @JvmStatic
    fun getDriveScopes(): Array<Scope> = arrayOf(
        Scope(DRIVE_APPDATA_SCOPE),
        Scope(DRIVE_SCOPE)
    )

    @JvmStatic
    fun createSignInIntent(context: Context): Intent =
        GoogleSignIn.getClient(context, buildSignInOptions()).signInIntent

    @JvmStatic
    fun getAuthorizedAccount(context: Context): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return if (GoogleSignIn.hasPermissions(account, *getDriveScopes())) account else null
    }

    @JvmStatic
    fun getLinkedAccountEmail(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACCOUNT_EMAIL, "") ?: ""

    @JvmStatic
    fun hasLinkedAccount(context: Context): Boolean = getLinkedAccountEmail(context).isNotBlank()

    @JvmStatic
    fun scheduleUpload(context: Context, reason: String) {
        val appContext = context.applicationContext
        val account = getAuthorizedAccount(appContext) ?: return
        if (!AppConfigStore.hasMeaningfulConfig(appContext)) return
        executor.execute {
            try {
                val message = uploadConfig(appContext, account)
                CaltopoClient.CTDebug(TAG, "scheduleUpload($reason): $message")
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "scheduleUpload($reason) failed.", e)
            }
        }
    }

    @JvmStatic
    fun performAction(
        context: Context,
        account: GoogleSignInAccount,
        action: DriveSyncAction,
        callback: (Boolean, String) -> Unit
    ) {
        val appContext = context.applicationContext
        saveLinkedAccount(appContext, account)
        executor.execute {
            val result = try {
                val message = when (action) {
                    DriveSyncAction.RESTORE -> restoreConfig(appContext, account)
                    DriveSyncAction.BACKUP -> uploadConfig(appContext, account)
                }
                true to message
            } catch (e: UserRecoverableAuthException) {
                CaltopoClient.CTWarn(TAG, "performAction($action): additional auth required.", e)
                false to "Google needs you to re-authorize Drive access for RID2Caltopo."
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "performAction($action) failed.", e)
                false to (e.message ?: "Google Drive sync failed.")
            }
            mainHandler.post {
                callback(result.first, result.second)
            }
        }
    }

    @JvmStatic
    fun disconnect(
        context: Context,
        callback: (Boolean, String) -> Unit
    ) {
        val appContext = context.applicationContext
        GoogleSignIn.getClient(appContext, buildSignInOptions())
            .signOut()
            .addOnCompleteListener { task ->
                clearLocalLinkState(appContext)
                val success = task.isSuccessful
                callback(
                    success,
                    if (success) {
                        "Google Drive disconnected."
                    } else {
                        "Google Drive sign-out finished locally."
                    }
                )
            }
    }

    private fun restoreConfig(context: Context, account: GoogleSignInAccount): String {
        val token = requireAccessToken(context, account)
        val fileId = findReadableConfigFileId(context, token)
            ?: return "No saved RID2Caltopo configuration was found in Google Drive."
        val bytes = downloadFile(token, fileId)
        if (!AppConfigStore.importConfigBytes(context, bytes)) {
            throw IOException("Google Drive returned a config file that could not be imported.")
        }
        rememberCanonicalFileId(context, fileId)
        CaltopoClient.ReloadStateFromStore()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_RESTORE_MS, System.currentTimeMillis())
            .apply()
        return "Configuration restored from Google Drive."
    }

    private fun uploadConfig(context: Context, account: GoogleSignInAccount): String {
        val token = requireAccessToken(context, account)
        val bytes = AppConfigStore.exportConfigBytes(context)
        val existingId = findWritableConfigFileId(context, token)
        val fileId = uploadFile(token, existingId, bytes)
        rememberCanonicalFileId(context, fileId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_UPLOAD_MS, System.currentTimeMillis())
            .apply()
        return if (existingId == null) {
            "Configuration backed up to Google Drive."
        } else {
            "Google Drive backup updated."
        }
    }

    private fun uploadFile(token: String, existingId: String?, bytes: ByteArray): String {
        val metadataJson = JSONObject()
            .put("name", DRIVE_FILE_NAME)
            .put("appProperties", JSONObject().put(APP_PROPERTY_KEY, APP_PROPERTY_VALUE))
        val metadata = metadataJson.toString()
        val boundary = "RID2CaltopoConfigSyncBoundary"
        val bodyBytes = buildMultipartBody(boundary, metadata, bytes)
        val body = bodyBytes.toRequestBody("multipart/related; boundary=$boundary".toMediaType())
        val url = if (existingId == null) {
            "$DRIVE_UPLOAD_URL?uploadType=multipart"
        } else {
            "$DRIVE_UPLOAD_URL/$existingId?uploadType=multipart"
        }
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
        val request = if (existingId == null) builder.post(body).build() else builder.patch(body).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val details = response.body?.string().orEmpty()
                throw IOException(formatDriveError("Drive upload failed", response.code, details))
            }
            val payload = response.body?.string().orEmpty()
            val id = JSONObject(payload).optString("id")
            return id.ifBlank {
                existingId ?: throw IOException("Drive upload succeeded but returned no file id.")
            }
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        metadata: String,
        bytes: ByteArray,
        contentType: String = "application/octet-stream"
    ): ByteArray {
        val header = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        return ByteArray(header.size + bytes.size + footer.size).also { combined ->
            System.arraycopy(header, 0, combined, 0, header.size)
            System.arraycopy(bytes, 0, combined, header.size, bytes.size)
            System.arraycopy(footer, 0, combined, header.size + bytes.size, footer.size)
        }
    }

    private fun downloadFile(token: String, fileId: String): ByteArray {
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val details = response.body?.string().orEmpty()
                throw IOException(formatDriveError("Drive download failed", response.code, details))
            }
            return response.body?.bytes()
                ?: throw IOException("Drive download returned an empty body.")
        }
    }

    private fun findReadableConfigFileId(context: Context, token: String): String? {
        val canonical = resolveCanonicalFile(context, token)
        if (canonical != null) return canonical.id
        return findLegacyConfigFiles(token).firstOrNull()?.id
    }

    private fun findWritableConfigFileId(context: Context, token: String): String? {
        val canonical = resolveCanonicalFile(context, token)
        return if (canonical?.canEdit == true) canonical.id else null
    }

    private fun resolveCanonicalFile(context: Context, token: String): DriveConfigFile? {
        val preferredId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CANONICAL_FILE_ID, "")
            .orEmpty()
        if (preferredId.isNotBlank()) {
            val direct = getFileById(token, preferredId)
            if (direct != null) {
                return direct
            }
        }
        val discovered = findCanonicalConfigFiles(token).firstOrNull()
        return discovered
    }

    private fun findCanonicalConfigFiles(token: String): List<DriveConfigFile> {
        return findConfigFilesByName(token, DRIVE_FILE_NAME, appDataOnly = false, requireAppProperty = true)
    }

    private fun findLegacyConfigFiles(token: String): List<DriveConfigFile> {
        return findConfigFilesByName(token, LEGACY_DRIVE_FILE_NAME, appDataOnly = true, requireAppProperty = false)
    }

    private fun findConfigFilesByName(
        token: String,
        fileName: String,
        appDataOnly: Boolean,
        requireAppProperty: Boolean
    ): List<DriveConfigFile> {
        val q = "name='$fileName' and trashed=false"
        val encodedQ = URLEncoder.encode(q, StandardCharsets.UTF_8.name())
        val spaces = if (appDataOnly) "appDataFolder" else "drive"
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL?spaces=$spaces&fields=files(id,name,modifiedTime,capabilities(canEdit),appProperties)&orderBy=modifiedTime desc&q=$encodedQ")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val details = response.body?.string().orEmpty()
                throw IOException(formatDriveError("Drive file lookup failed", response.code, details))
            }
            val payload = response.body?.string().orEmpty()
            val files = JSONObject(payload).optJSONArray("files") ?: return emptyList()
            val results = ArrayList<DriveConfigFile>(files.length())
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val id = file.optString("id")
                if (id.isBlank()) continue
                if (requireAppProperty) {
                    val appProperties = file.optJSONObject("appProperties")
                    val kind = appProperties?.optString(APP_PROPERTY_KEY).orEmpty()
                    if (kind != APP_PROPERTY_VALUE) continue
                }
                val canEdit = file.optJSONObject("capabilities")?.optBoolean("canEdit", false) == true
                results.add(DriveConfigFile(id = id, canEdit = canEdit))
            }
            return results
        }
    }

    private fun getFileById(token: String, fileId: String): DriveConfigFile? {
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL/$fileId?fields=id,name,capabilities(canEdit)")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                val details = response.body?.string().orEmpty()
                throw IOException(formatDriveError("Drive file lookup failed", response.code, details))
            }
            val payload = response.body?.string().orEmpty()
            val file = JSONObject(payload)
            val id = file.optString("id")
            if (id.isBlank()) return null
            val canEdit = file.optJSONObject("capabilities")?.optBoolean("canEdit", false) == true
            return DriveConfigFile(id = id, canEdit = canEdit)
        }
    }

    private fun rememberCanonicalFileId(context: Context, fileId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CANONICAL_FILE_ID, fileId)
            .apply()
    }

    private fun formatDriveError(prefix: String, code: Int, details: String): String {
        if (details.isBlank()) {
            return "$prefix with HTTP $code"
        }
        return try {
            val error = JSONObject(details).optJSONObject("error")
            val reason = error?.optJSONArray("errors")
                ?.optJSONObject(0)
                ?.optString("reason")
                .orEmpty()
            val message = error?.optString("message").orEmpty()
            buildString {
                append(prefix).append(" with HTTP ").append(code)
                if (reason.isNotBlank()) append(" (").append(reason).append(")")
                if (message.isNotBlank()) append(": ").append(message)
            }
        } catch (_: Exception) {
            "$prefix with HTTP $code: $details"
        }
    }

    private fun requireAccessToken(context: Context, account: GoogleSignInAccount): String {
        val androidAccount: Account = account.account
            ?: throw IOException("Google Sign-In did not return an Android account.")
        val tokenScope = "oauth2:$DRIVE_APPDATA_SCOPE $DRIVE_SCOPE"
        try {
            return GoogleAuthUtil.getToken(context, androidAccount, tokenScope)
        } catch (e: UserRecoverableAuthException) {
            throw e
        } catch (e: GoogleAuthException) {
            throw IOException("Google authentication failed.", e)
        }
    }

    private fun saveLinkedAccount(context: Context, account: GoogleSignInAccount) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNT_EMAIL, account.email ?: "")
            .apply()
    }

    private fun clearLocalLinkState(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_CANONICAL_FILE_ID)
            .remove(KEY_ORG_CONFIG_FOLDER_ID)
            .apply()
    }

    private fun buildSignInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(getDriveScopes().first(), *getDriveScopes().drop(1).toTypedArray())
            .build()

    // ── Org-config file: upload (admin) and download (member) ─────────────────

    /**
     * Upload [orgConfigJson] to Drive as [orgName]_Config.json inside the
     * RID2Caltopo_Configs folder (created at Drive root if absent), make it
     * publicly readable, and return the Drive file ID.
     *
     * The ct_credentials block inside [orgConfigJson] is already XOR-encrypted
     * by [OrgConfigManager] before this call, so credentials are never uploaded
     * in plaintext.
     *
     * If a file for [orgName] already exists it is updated in-place; otherwise
     * a new file is created inside the folder.  Must be called from a background
     * thread.
     */






    /**
     * Find or create the RID2Caltopo_Configs folder at the root of the admin's
     * Drive and return its file ID.  The ID is cached in SharedPreferences so
     * repeated uploads don't require a search round-trip.
     */
    private fun findOrCreateOrgConfigFolder(context: Context, token: String): String {
        return findOrCreateNamedFolder(context, token, ORG_CONFIG_FOLDER_NAME, KEY_ORG_CONFIG_FOLDER_ID)
    }

    private fun findOrCreateNamedFolder(context: Context, token: String, folderName: String, prefKey: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedId = prefs.getString(prefKey, "").orEmpty()
        if (cachedId.isNotBlank() && getFileById(token, cachedId) != null) {
            return cachedId
        }
        // Search for an existing folder with our name.
        val q = URLEncoder.encode(
            "name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false",
            StandardCharsets.UTF_8.name()
        )
        val searchRequest = Request.Builder()
            .url("$DRIVE_FILES_URL?spaces=drive&fields=files(id)&q=$q")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val existingFolderId = httpClient.newCall(searchRequest).execute().use { response ->
            if (!response.isSuccessful) null
            else JSONObject(response.body?.string().orEmpty())
                .optJSONArray("files")?.optJSONObject(0)?.optString("id")?.ifBlank { null }
        }
        val folderId = existingFolderId ?: run {
            // Create the folder.
            val body = JSONObject()
                .put("name", folderName)
                .put("mimeType", "application/vnd.google-apps.folder")
                .toString()
                .toRequestBody("application/json".toMediaType())
            val createRequest = Request.Builder()
                .url(DRIVE_FILES_URL)
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            httpClient.newCall(createRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val details = response.body?.string().orEmpty()
                    throw IOException(formatDriveError("Config folder creation failed", response.code, details))
                }
                JSONObject(response.body?.string().orEmpty()).optString("id")
                    .ifBlank { throw IOException("Config folder creation returned no ID.") }
            }
        }
        prefs.edit().putString(prefKey, folderId).apply()
        return folderId
    }

    /**
     * Download the org-config JSON for [fileId] using Google Drive's public
     * export URL — no authentication required for files shared with "anyone".
     * Must be called from a background thread.
     */
    @JvmStatic
    fun downloadOrgConfigPublic(fileId: String): String {
        val url = PUBLIC_DOWNLOAD_URL.format(fileId)
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Org config download failed: HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("Org config download returned an empty body.")
            if (!body.contains("rid2caltopo_org_config")) {
                CaltopoClient.CTWarn(TAG, "downloadOrgConfigPublic(): invalid bundle for fileId=$fileId; first 200 chars: ${body.take(200)}")
                throw IOException("Downloaded file does not appear to be a valid org config bundle.")
            }
            return body
        }
    }

    /**
     * Download the mutual-aid JSON bundle for [fileId] using Google Drive's
     * public export URL — no authentication required for files shared with
     * "anyone". Must be called from a background thread.
     */
    @JvmStatic
    fun downloadMutualAidBundlePublic(fileId: String): String {
        val url = PUBLIC_DOWNLOAD_URL.format(fileId)
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Organization config download failed: HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("Organization config download returned an empty body.")
            if (!body.contains("rid2caltopo_mutual_aid_profile")) {
                CaltopoClient.CTWarn(TAG, "downloadMutualAidBundlePublic(): invalid bundle for fileId=$fileId; first 200 chars: ${body.take(200)}")
                throw IOException("Downloaded file does not appear to be a valid organization config bundle.")
            }
            return body
        }
    }

    @JvmStatic
    fun downloadFaaConfigPublic(fileId: String): String {
        val url = PUBLIC_DOWNLOAD_URL.format(fileId)
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("FAA config download failed: HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("FAA config download returned an empty body.")
            if (!body.contains(FaaConfigManager.TYPE_ENCRYPTED)) {
                CaltopoClient.CTWarn(TAG, "downloadFaaConfigPublic(): invalid FAA bundle for fileId=$fileId; first 200 chars: ${body.take(200)}")
                throw IOException("Downloaded file does not appear to be a valid FAA config bundle.")
            }
            return body
        }
    }

    /** Grant "anyone with the link → reader" permission on [fileId]. Non-fatal on failure. */
    private fun makeFilePublic(token: String, fileId: String) {
        val body = JSONObject()
            .put("type", "anyone")
            .put("role", "reader")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(DRIVE_PERMISSIONS_URL.format(fileId))
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val details = response.body?.string().orEmpty()
                // Non-fatal: upload still succeeded even if the permission grant fails.
                CaltopoClient.CTWarn(
                    TAG,
                    "makeFilePublic: ${formatDriveError("Permission grant failed", response.code, details)}"
                )
            }
        }
    }

    /** Find the Drive file ID of a previously uploaded org-config file for [orgName]. */
    private fun findOrgConfigFileId(token: String, orgName: String): String? {
        val orgFileName = "${orgName}_Config.json"
        return findScopedPublicJsonFileId(token, orgFileName, ORG_APP_PROPERTY_VALUE)
    }

    private fun findScopedPublicJsonFileId(token: String, fileName: String, kindValue: String): String? {
        val q = URLEncoder.encode("name='$fileName' and trashed=false", StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL?spaces=drive&fields=files(id,appProperties,capabilities(canEdit))&orderBy=modifiedTime desc&q=$q")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val payload = response.body?.string().orEmpty()
            val files = JSONObject(payload).optJSONArray("files") ?: return null
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val kind = file.optJSONObject("appProperties")?.optString(APP_PROPERTY_KEY).orEmpty()
                if (kind != kindValue) continue
                val canEdit = file.optJSONObject("capabilities")?.optBoolean("canEdit", false) == true
                if (canEdit) return file.optString("id")
            }
            return null
        }
    }
}

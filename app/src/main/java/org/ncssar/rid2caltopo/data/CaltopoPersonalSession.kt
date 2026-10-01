package org.ncssar.rid2caltopo.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

/** Opaque grant tokens cross activity results; scoped cookies cross the app-private provider for native HTTPS requests. */
class PersonalCaltopoLoginRequired : IllegalStateException("Sign in to CalTopo to load personal maps.")

object CaltopoPersonalSession {
    var accountID = ""
        private set
    private var mediaOwners = emptyMap<String, String>()
    var username by mutableStateOf("")
    var browsingPersonal by mutableStateOf(false)
    var maps by mutableStateOf<List<CaltopoNode>>(emptyList())
    var catalogReady by mutableStateOf(false)
    fun readCatalog(): org.json.JSONObject {
        val result = context?.contentResolver?.call(Uri.parse("content://org.ncssar.rid2caltopo.personal-session"), "catalog", null, null)
        if (result?.getBoolean("loginRequired") == true) throw PersonalCaltopoLoginRequired()
        return org.json.JSONObject(result?.getString("catalog") ?: error("Personal maps could not load. Check your connection and retry."))
    }
    fun acceptCatalog(json: org.json.JSONObject) {
        val grants = json.getJSONObject("grants")
        fun convert(node: CaltopoNode): CaltopoNode = when (node) {
            is CaltopoNode.MapNode -> node.copy(personalSessionID = grants.getString(node.id))
            is CaltopoNode.Directory -> node.copy(children = node.children.map(::convert).toMutableList())
        }
        val accountID = json.getString("accountID")
        val decoded = parseMapHierarchy(json).map(::convert).sortedBy {
            when (it.id) { "virtual_recents" -> 0; accountID -> 1; else -> 2 }
        }
        this.accountID = accountID
        val owners = json.getJSONObject("mediaOwners")
        mediaOwners = owners.keys().asSequence().associateWith { owners.getString(it) }
        username = json.getString("username")
        maps = decoded
        catalogReady = true
    }
    data class Authorization(val token: String, val mapID: String, val accountID: String = "", val mediaOwnerID: String = "") {
        val credentialKey: String get() = "personal:$accountID"
    }
    @Volatile private var selected: Authorization? = null
    private var context: Context? = null
    @JvmStatic fun initialize(context: Context) { this.context = context.applicationContext }
    @JvmStatic fun activate(token: String?, mapID: String) {
        selected = token?.let { Authorization(it, mapID, accountID, mediaOwners[mapID] ?: accountID) }
    }
    @JvmStatic fun capture(path: String): Authorization? =
        if (path.startsWith("/api/v1/acct/")) null else selected
    @JvmStatic fun mediaID(path: String): String? = Regex("^/api/v1/media/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/(?:data|original))?$").matchEntire(path)?.groupValues?.get(1)?.lowercase()
    @JvmStatic fun authorizeMedia(auth: Authorization, mediaID: String) {
        val result = context?.contentResolver?.call(Uri.parse("content://org.ncssar.rid2caltopo.personal-session"), "authorizeMedia", auth.token,
            Bundle().apply { putString("mediaID", mediaID) })
        check(result?.getBoolean("authorized") == true) { "Personal login expired; photo remains queued." }
    }
    @JvmStatic fun requireValid(auth: Authorization, path: String) {
        val result = context?.contentResolver?.call(Uri.parse("content://org.ncssar.rid2caltopo.personal-session"), "valid", auth.token,
            Bundle().apply { putString("url", "https://caltopo.com$path") })
        check(result?.getBoolean("valid") == true) { "Personal login revoked. Team credentials were not used." }
    }
    @JvmStatic fun cookie(auth: Authorization, path: String): String {
        require(path.startsWith("/api/v1/map/${auth.mapID}/") || mediaID(path) != null) { "Personal session is restricted to its selected map and clue media" }
        val result = context?.contentResolver?.call(
            Uri.parse("content://org.ncssar.rid2caltopo.personal-session"), "cookie", auth.token,
            Bundle().apply { putString("url", "https://caltopo.com$path") })
        return result?.getString("cookie")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Personal login expired. Reopen Personal CalTopo login; Team credentials were not used.")
    }
}

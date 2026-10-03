package org.ncssar.rid2caltopo.data

import java.net.URI
import org.json.JSONObject

/** Read-only experiment. No service credentials or operational state are involved. */
object CaltopoPersonalProbe {
    const val ORIGIN = "https://caltopo.com"
    private val mapID = Regex("[A-Za-z0-9]{3,16}")
    private val invitation = Regex("/group/[A-Za-z0-9]{6}/signup/[A-Za-z0-9]+/?")

    fun browserURL(input: String): String? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "caltopo.com" || uri.port != -1 || uri.userInfo != null) return null
        return if (invitation.matches(uri.path.orEmpty()) || Regex("/m/[A-Za-z0-9]{3,16}/?").matches(uri.path.orEmpty())) uri.toString() else null
    }

    /** Accept only the CalTopo HTTPS origin; paths, queries, and fragments are preserved. */
    fun caltopoLinkURL(input: String): String? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("caltopo.com", ignoreCase = true) ||
            uri.port !in listOf(-1, 443) || uri.userInfo != null) return null
        return uri.toString()
    }

    fun mapID(input: String): String? {
        val value = input.trim()
        if (mapID.matches(value)) return value
        val url = browserURL(value) ?: return null
        val path = URI(url).path
        return if (path.startsWith("/m/")) path.removePrefix("/m/").trimEnd('/') else null
    }

    fun endpoint(id: String): String {
        require(mapID.matches(id))
        return "$ORIGIN/api/v1/map/$id/since/0"
    }

    data class Result(val code: Int, val featureCount: Int?) {
        val readable: Boolean get() = code in 200..299 && featureCount != null
        val summary: String get() = when {
            readable -> "HTTP $code, ${featureCount} map objects"
            code == 401 || code == 403 -> "HTTP $code, access denied or login expired"
            code in 300..399 -> "HTTP $code, redirect refused (login may be required)"
            else -> "HTTP $code, no recognized map response"
        }
    }

    fun result(code: Int, body: String): Result {
        val count = if (code in 200..299) runCatching {
            val json = JSONObject(body)
            if (json.optString("status") != "ok") return@runCatching null
            json.optJSONObject("result")?.optJSONObject("state")?.optJSONArray("features")?.length()
        }.getOrNull() else null
        return Result(code, count)
    }

    fun comparison(personal: Result, anonymous: Result): String = when {
        personal.readable && (anonymous.code == 401 || anonymous.code == 403) ->
            "Personal session read succeeded; anonymous access was denied. Publishing is not tested."
        personal.readable && anonymous.readable ->
            "Both requests can read this map. Use a private map to establish that login grants access."
        personal.readable -> "Personal request read succeeded, but the anonymous result is inconclusive."
        else -> "Personal session API access is not established. Sign in and check this account's map access."
    }
}

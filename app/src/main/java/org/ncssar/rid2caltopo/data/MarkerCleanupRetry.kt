package org.ncssar.rid2caltopo.data

import org.json.JSONObject

/**
 * Device-marker DELETE bookkeeping.
 *
 * The journal clears only after CalTopo confirms the feature is gone (2xx with a
 * successful delete, or 404). A 400 or any other failure keeps the row and waits
 * out a persisted backoff before the next launch or foreground retry.
 */
object MarkerCleanupRetry {
    private val delaysMs = longArrayOf(30_000L, 120_000L, 600_000L, 3_600_000L, 21_600_000L)

    /** Test clock. [Long.MIN_VALUE] uses the wall clock. */
    @JvmField
    @Volatile
    var clockMs: Long = Long.MIN_VALUE

    @JvmStatic
    fun now(): Long = if (clockMs == Long.MIN_VALUE) System.currentTimeMillis() else clockMs

    @JvmStatic
    fun delayMs(attempts: Int): Long {
        val index = (attempts - 1).coerceIn(0, delaysMs.lastIndex)
        return delaysMs[index]
    }

    /** True only for a confirmed delete. */
    @JvmStatic
    fun clearJournal(httpStatus: Int, verifiedSuccess: Boolean): Boolean {
        if (httpStatus == 404) return true
        return httpStatus in 200..299 && verifiedSuccess
    }

    @JvmStatic
    fun idPrefix(id: String): String = if (id.length <= 8) id else id.substring(0, 8)

    @JvmStatic
    fun resetClockForTest() {
        clockMs = Long.MIN_VALUE
    }
}

/**
 * Picks the CalTopo class and id for a device-marker DELETE.
 *
 * A write often echoes the feature under `state.features` with a server id that is
 * not the client UUID. DELETE of the client UUID against `/Marker/` is a 400 while
 * the real feature remains. Shape features use `/Shape/`; folders are never deleted.
 */
object DeviceMarkerDeletePlan {
    data class Target(val featureClass: String, val id: String) {
        /** Marker ids stay bare so existing Marker deletes are unchanged. */
        fun wire(): String = if (featureClass == "Shape") "Shape/$id" else id
    }

    @JvmStatic
    fun resolve(journalMarkerId: String, deviceGuid: String, features: List<JSONObject>): Target {
        val wanted = journalMarkerId.trim()
        val guid = deviceGuid.trim()
        var fallback: Target? = null
        for (feature in features) {
            val id = feature.optString("id").trim()
            val props = feature.optJSONObject("properties") ?: continue
            val rawClass = props.optString("class", "Marker").ifBlank { "Marker" }
            if (rawClass == "Folder") continue
            val featureGuid = props.optString("r2c-guid").trim()
            val idMatch = id.equals(wanted, ignoreCase = true)
            val guidMatch = guid.isNotEmpty() &&
                (featureGuid.equals(guid, ignoreCase = true) || featureGuid.equals(wanted, ignoreCase = true))
            if (!idMatch && !guidMatch) continue
            val featureClass = if (rawClass == "Shape") "Shape" else "Marker"
            val target = Target(featureClass, id.ifEmpty { wanted })
            if (idMatch && featureClass == "Marker") return target
            if (fallback == null) fallback = target
        }
        return fallback ?: Target("Marker", wanted)
    }

    @JvmStatic
    fun parseWire(spec: String): Target {
        if (spec.startsWith("Shape/")) return Target("Shape", spec.substring("Shape/".length))
        if (spec.startsWith("Marker/")) return Target("Marker", spec.substring("Marker/".length))
        return Target("Marker", spec)
    }

    /** Server id from a write body, including a feature nested under `state.features`. */
    @JvmStatic
    fun ownedId(result: JSONObject?, deviceId: String): String {
        if (result == null || deviceId.isEmpty()) return ""
        val direct = match(result, deviceId)
        if (direct.isNotEmpty()) return direct
        val features = result.optJSONObject("state")?.optJSONArray("features") ?: return ""
        for (i in 0 until features.length()) {
            val id = match(features.optJSONObject(i), deviceId)
            if (id.isNotEmpty()) return id
        }
        return ""
    }

    private fun match(feature: JSONObject?, deviceId: String): String {
        if (feature == null) return ""
        val returned = feature.optString("id", "")
        val properties = feature.optJSONObject("properties")
        val guid = properties?.optString("r2c-guid", "").orEmpty()
        if (guid.isNotEmpty() && !deviceId.equals(guid, ignoreCase = true)) return ""
        if (deviceId.equals(returned, ignoreCase = true)) return returned
        if (deviceId.equals(guid, ignoreCase = true) && returned.isNotEmpty() &&
            returned.matches(Regex("[A-Za-z0-9_-]+"))
        ) {
            return returned
        }
        return ""
    }
}

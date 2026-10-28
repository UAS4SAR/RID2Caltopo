package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject

/** Empty incremental responses are not proof of deletion. Keep the journal unless explicit evidence exists. */
object CaltopoMarkerVerification {
    @JvmStatic fun confirmsAbsent(result: JSONObject?, markerID: String): Boolean {
        val state = result?.optJSONObject("state") ?: return false
        val features = state.optJSONArray("features") ?: return false
        var deleted = false
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            if (!markerID.equals(feature.optString("id"), ignoreCase = true)) continue
            val properties = feature.optJSONObject("properties")
            val action = properties?.optString("action").orEmpty()
            val tombstone = feature.optBoolean("deleted") || properties?.optBoolean("deleted") == true ||
                action.equals("delete", true) || action.equals("removed", true) ||
                (!feature.has("geometry") && !feature.has("properties"))
            if (!tombstone) return false
            deleted = true
        }
        val allIDs = result.optJSONObject("ids") ?: state.optJSONObject("ids")
        if (allIDs?.has("Marker") != true) return deleted
        val ids = allIDs.optJSONArray("Marker") ?: return false
        for (i in 0 until ids.length()) {
            val id = ids.opt(i) as? String ?: return false
            if (id.equals(markerID, true)) return false
        }
        return true
    }
}

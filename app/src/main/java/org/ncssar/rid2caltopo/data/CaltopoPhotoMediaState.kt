package org.ncssar.rid2caltopo.data

import org.json.JSONObject

/** Only reuse a media object belonging to this clue's intended owner. */
object CaltopoPhotoMediaState {
    @JvmStatic fun isReady(result: JSONObject, mediaID: String, ownerID: String): Boolean {
        require(result.optString("id").equals(mediaID, ignoreCase = true)) { "Photo media identity does not match the queued clue" }
        val properties = result.getJSONObject("properties")
        require(properties.optString("creator") == ownerID) { "Photo media belongs to a different account" }
        return properties.optBoolean("mediaIsReady") && (result.optJSONObject("metadata")?.optLong("filesize") ?: 0) > 0
    }
}

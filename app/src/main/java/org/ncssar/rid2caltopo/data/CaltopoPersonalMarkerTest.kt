package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Narrow write qualification: only the user's designated disposable test map. */
object CaltopoPersonalMarkerTest {
    const val MAP = "G00CPSS"
    data class Reply(val code: Int, val body: String)
    enum class Presence { ABSENT, OWN, CONFLICT }
    private fun validID(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    fun title(id: String): String { require(validID(id)); return "RID2Caltopo session test $id" }
    fun markerPath(id: String): String { require(validID(id)); return "/api/v1/map/$MAP/Marker/$id" }
    val readPath = "/api/v1/map/$MAP/since/0"
    fun payload(id: String): String = JSONObject().put("id", id).put("type", "Feature")
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(0).put(0)))
        .put("properties", JSONObject().put("class", "Marker").put("title", title(id))
            .put("description", "Temporary personal-session test at 0,0; safe to remove."))
        .toString()

    fun presence(reply: Reply, id: String): Presence {
        check(CaltopoPersonalProbe.result(reply.code, reply.body).readable) { "Map read failed (HTTP ${reply.code})." }
        val features = JSONObject(reply.body).getJSONObject("result").getJSONObject("state").getJSONArray("features")
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            if (feature.optString("id") == id) {
                val properties = feature.optJSONObject("properties")
                return if (properties?.optString("class") == "Marker" && properties.optString("title") == title(id)) Presence.OWN else Presence.CONFLICT
            }
        }
        return Presence.ABSENT
    }

    /** send must not retry mutations or follow redirects. Persist ID BEFORE attempting POST. */
    fun run(id: String, cleanupOnly: Boolean, send: (String, String, String?) -> Reply,
            savePending: (String) -> Unit, clearPending: () -> Unit): String {
        val path = markerPath(id)
        val before = presence(send("GET", readPath, null), id)
        check(before != Presence.CONFLICT) { "Marker identity does not match. Nothing was removed." }
        if (!cleanupOnly) {
            check(before == Presence.ABSENT) { "Test marker already exists. Use cleanup." }
            savePending(id)
            val created = send("POST", path, payload(id))
            check(created.code in 200..299) { "Create returned HTTP ${created.code}. Use cleanup to check for a possible leftover." }
            check(presence(send("GET", readPath, null), id) == Presence.OWN) { "Created marker was not verified. Use cleanup." }
        } else if (before == Presence.ABSENT) {
            clearPending()
            return "Cleanup verified: the pending test marker is absent."
        }
        val deleted = send("DELETE", path, null)
        check(deleted.code in 200..299) { "Remove returned HTTP ${deleted.code}. Test marker cleanup is still pending." }
        check(presence(send("GET", readPath, null), id) == Presence.ABSENT) { "Test marker is still present. Cleanup remains pending." }
        clearPending()
        return if (cleanupOnly) "Cleanup verified: test marker removed." else "Publishing succeeded: created, read back, removed, and verified absent on $MAP."
    }
}

package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.function.LongFunction

/** Worker-confined, bounded in-memory startup snapshots. Never stores bytes on disk. */
class CaltopoMapSnapshots(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    data class Scope(val origin: String, val map: String, val team: String?, val credential: String?,
        val secret: String?, val personalToken: String?, val personalGeneration: Long)
    private data class Entry(val json: String, val cursor: Long, val checkedAt: Long)
    private val entries = LinkedHashMap<Scope, Entry>(8, 0.75f, true)

    fun read(scope: Scope, validate: Runnable, load: LongFunction<JSONObject>): JSONObject {
        validate.run()
        entries.entries.removeAll { now() - it.value.checkedAt >= 120_000 }
        val previous = entries[scope]
        // Reopening a cached map needs only its deltas. Refresh before startup
        // recovery considers old LiveTracks, so stale cached timestamps cannot
        // make it archive a track another operator has just updated.
        val cursor = previous?.cursor ?: 0L
        try {
            val started = wallClock()
            val result = load.apply(cursor)
            validate.run()
            val state = result.getJSONObject("state")
            val features = linkedMapOf<String, JSONObject>()
            if (previous != null) {
                val old = JSONObject(previous.json).getJSONObject("state").getJSONArray("features")
                for (i in 0 until old.length()) old.getJSONObject(i).let { features[it.getString("id")] = it }
            }
            val changes = state.getJSONArray("features")
            for (i in 0 until changes.length()) {
                val feature = changes.getJSONObject(i)
                val id = feature.optString("id")
                if (id.isEmpty()) continue
                val props = feature.optJSONObject("properties")
                val deleted = feature.optBoolean("deleted") || props?.optBoolean("deleted") == true ||
                    props?.optString("action")?.lowercase() in listOf("delete", "removed") ||
                    (props == null && !feature.has("geometry"))
                if (deleted) features.remove(id) else features[id] = feature
            }
            val ids = result.optJSONObject("ids") ?: state.optJSONObject("ids")
            if (ids != null) {
                val live = ids.keys().asSequence().mapNotNull { kind -> ids.optJSONArray(kind)?.let { rows ->
                    kind to (0 until rows.length()).map { rows.getString(it) }.toSet()
                } }.toMap()
                features.entries.removeAll { (id, feature) ->
                    val kind = feature.optJSONObject("properties")?.optString("class")
                    live[kind]?.contains(id) == false
                }
            }
            val nextCursor = maxOf(cursor, result.optLong("timestamp", started).takeIf { it > 500 } ?: started)
            val snapshot = JSONObject().put("timestamp", nextCursor).put("state",
                JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features.values.toList())))
            val serialized = snapshot.toString()
            entries.remove(scope)
            if (serialized.length <= 4 * 1024 * 1024) {
                entries[scope] = Entry(serialized, nextCursor, now())
                while (entries.size > 8 || entries.values.sumOf { it.json.length.toLong() * 2 } > 16 * 1024 * 1024) {
                    entries.remove(entries.keys.first())
                }
            }
            return snapshot
        } catch (error: Exception) {
            entries.remove(scope)
            throw error
        }
    }
}

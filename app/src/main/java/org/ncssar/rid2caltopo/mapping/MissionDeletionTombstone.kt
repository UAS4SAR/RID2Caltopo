package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent mission-id tombstones so intentional Local Maps deletes cannot reappear from a
 * stale library-index / Archive Dir hydrate while folders are still being removed.
 */
internal object MissionDeletionTombstone {
    const val SCHEMA = 1

    /** Shared with library-index and verify-stamp writers; never hold across folder deletes. */
    val lock = Any()

    fun file(filesDir: File) = File(filesDir, "mapping-storage-stats/mission-deleted.json")

    fun load(filesDir: File): Set<String> {
        val text = runCatching { file(filesDir).readText() }.getOrNull() ?: return emptySet()
        return parse(text)
    }

    fun parse(text: String): Set<String> = runCatching {
        val value = JSONObject(text)
        if (value.optInt("schema") != SCHEMA) return emptySet()
        val ids = value.optJSONArray("ids") ?: return emptySet()
        (0 until ids.length()).mapNotNull { ids.optString(it).takeIf { id -> id.isNotBlank() } }.toSet()
    }.getOrDefault(emptySet())

    fun write(filesDir: File, ids: Set<String>) {
        val destination = file(filesDir)
        destination.parentFile?.mkdirs()
        val body = JSONObject().put("schema", SCHEMA).put("ids", JSONArray(ids.sorted()))
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(body.toString())
        check(temporary.renameTo(destination) || (destination.delete() && temporary.renameTo(destination))) {
            "Could not save mission deletion tombstones."
        }
    }

    fun contains(filesDir: File, id: String): Boolean = id in load(filesDir)

    /** Returns true the first time [id] is recorded. Caller must hold [lock]. */
    fun rememberLocked(filesDir: File, id: String): Boolean {
        val prior = load(filesDir)
        if (id in prior) return false
        write(filesDir, prior + id)
        return true
    }

    fun filter(ids: Collection<String>, filesDir: File): List<String> {
        val tombstoned = load(filesDir)
        if (tombstoned.isEmpty()) return ids.toList()
        return ids.filterNot { it in tombstoned }
    }
}

package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Remembers empty mapping-layer dirs already removed so hydrate cannot re-log forever. */
internal object OrphanEmptyRegistry {
    const val SCHEMA = 1

    fun file(filesDir: File) = File(filesDir, "mapping-storage-stats/orphan-empty-removed.json")

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
            "Could not save orphan-empty registry."
        }
    }

    fun remember(filesDir: File, id: String): Boolean {
        val prior = load(filesDir)
        if (id in prior) return false
        write(filesDir, prior + id)
        return true
    }
}

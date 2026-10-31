package org.ncssar.rid2caltopo.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Union-merge for the clues operational projection. Hydrate / prepare / retry must never
 * discard working clue images or index rows; only an explicit user delete may remove a clue.
 */
internal object ClueProjectionMerge {
    data class IndexEntry(val id: String, val json: JSONObject, val createdAtMs: Long)

    /** Newest [createdAtMs] wins per id; missing ids from either side are kept. */
    fun mergeIndexJson(working: String?, archive: String?): String {
        val byId = linkedMapOf<String, IndexEntry>()
        fun ingest(raw: String?) {
            if (raw.isNullOrBlank()) return
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val id = json.optString("id").trim()
                if (id.isEmpty()) continue
                val created = json.optLong("createdAtMs", 0L)
                val prior = byId[id]
                if (prior == null || created >= prior.createdAtMs) {
                    byId[id] = IndexEntry(id, JSONObject(json.toString()), created)
                }
            }
        }
        // Archive first, then working so equal timestamps prefer the working copy.
        ingest(archive)
        ingest(working)
        val out = JSONArray()
        byId.values.sortedByDescending { it.createdAtMs }.forEach { out.put(it.json) }
        return out.toString(2)
    }

    /**
     * Copy files from [from] into [into]. Existing files are kept when [preferExisting] is true,
     * or when both exist and hashes differ (caller logs). Never deletes anything in [into].
     */
    fun mergeTree(from: File, into: File, preferExisting: Boolean, onConflict: (relative: String) -> Unit = {}) {
        if (!from.isDirectory) return
        into.mkdirs()
        from.walkTopDown().forEach { source ->
            if (!source.isFile) return@forEach
            val relative = source.relativeTo(from).invariantSeparatorsPath
            if (relative.endsWith(".r2c-info") || relative.contains(".r2c-")) return@forEach
            val target = File(into, relative)
            if (relative == "clues.json" || relative.endsWith("/clues.json")) {
                val merged = mergeIndexJson(
                    working = if (target.isFile) target.readText() else null,
                    archive = source.readText(),
                )
                target.parentFile!!.mkdirs()
                org.ncssar.rid2caltopo.data.PlainFileSafeWriter.replace(
                    target,
                    merged.toByteArray(Charsets.UTF_8),
                ) { it.isNotEmpty() }
                return@forEach
            }
            if (target.isFile) {
                val same = runCatching {
                    source.inputStream().use { VerifiedArchiveTree.digest(it) } ==
                        target.inputStream().use { VerifiedArchiveTree.digest(it) }
                }.getOrDefault(false)
                if (same) return@forEach
                onConflict(relative)
                if (preferExisting) return@forEach
                // Newest wins by mtime when not preferring the existing copy.
                if (source.lastModified() < target.lastModified()) return@forEach
            }
            target.parentFile!!.mkdirs()
            source.inputStream().use { input ->
                java.io.FileOutputStream(target).use { out ->
                    input.copyTo(out, 256 * 1024)
                    out.fd.sync()
                }
            }
            target.setLastModified(source.lastModified())
        }
    }
}

package org.ncssar.rid2caltopo.video.mapcache

import java.io.File

/**
 * Durable tile-index stamp. The SQLite index used to live under a per-launch
 * temporary directory, so every cold Map Cache open rebuilt ~12k tile rows.
 * A matching count and byte total means that index is still valid.
 */
internal object TileCacheIndexStamp {
    data class Value(val count: Long, val bytes: Long)

    fun databaseFile(filesDir: File): File = File(filesDir, "map-cache-index/saf_cache_index.db")

    fun stampFile(filesDir: File): File = File(filesDir, "map-cache-index/saf_cache_index.stamp")

    fun matches(saved: Value?, live: Value): Boolean = saved != null && saved == live

    fun parse(text: String): Map<String, Value> {
        val out = linkedMapOf<String, Value>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split(' ')
            if (parts.size != 3) continue
            val count = parts[1].toLongOrNull() ?: continue
            val bytes = parts[2].toLongOrNull() ?: continue
            out[parts[0]] = Value(count, bytes)
        }
        return out
    }

    fun format(values: Map<String, Value>): String =
        values.entries.sortedBy { it.key }.joinToString("\n") { (name, value) ->
            "$name ${value.count} ${value.bytes}"
        }

    fun read(file: File): Map<String, Value> =
        runCatching { parse(file.readText()) }.getOrDefault(emptyMap())

    fun update(file: File, namespace: String, value: Value) {
        val next = read(file).toMutableMap()
        next[namespace] = value
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(format(next))
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file))
        }
    }
}

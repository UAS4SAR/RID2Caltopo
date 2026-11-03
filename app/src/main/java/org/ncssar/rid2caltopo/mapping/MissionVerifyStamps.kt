package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import java.io.File

/**
 * Persisted mission-folder fingerprints so Archive Dir migrate can skip unchanged
 * missions without opening inventories or originals.
 */
internal object MissionVerifyStamps {
    const val SCHEMA = 1

    fun file(filesDir: File) = File(filesDir, "mapping-storage-stats/mission-verify-stamps.json")

    fun load(filesDir: File): Map<String, MappingLibraryIndex.MissionFingerprint> {
        val text = runCatching { file(filesDir).readText() }.getOrNull() ?: return emptyMap()
        return parse(text)
    }

    fun parse(text: String): Map<String, MappingLibraryIndex.MissionFingerprint> = runCatching {
        val value = JSONObject(text)
        if (value.optInt("schema") != SCHEMA) return emptyMap()
        val missions = value.optJSONObject("missions") ?: return emptyMap()
        missions.keys().asSequence().mapNotNull { id ->
            val fp = missions.optJSONObject(id) ?: return@mapNotNull null
            id to MappingLibraryIndex.MissionFingerprint.parse(fp)
        }.toMap()
    }.getOrDefault(emptyMap())

    fun write(filesDir: File, stamps: Map<String, MappingLibraryIndex.MissionFingerprint>) {
        val destination = file(filesDir)
        destination.parentFile?.mkdirs()
        val body = JSONObject().put("schema", SCHEMA).put("missions", JSONObject().also { out ->
            stamps.forEach { (id, fp) -> out.put(id, fp.json()) }
        })
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(body.toString())
        check(temporary.renameTo(destination) || (destination.delete() && temporary.renameTo(destination))) {
            "Could not save mission verify stamps."
        }
    }

    /** True when cheap folder/manifest fingerprints match the last successful archive verify. */
    fun unchanged(
        missionDir: File,
        stamps: Map<String, MappingLibraryIndex.MissionFingerprint>,
    ): Boolean {
        val id = missionDir.name
        val prior = stamps[id] ?: return false
        return MappingLibraryIndex.MissionFingerprint.of(missionDir) == prior
    }

    /**
     * Partition mission directories into skip vs verify. Non-UUID children always verify.
     * Order is stable by name for (k/N) progress.
     */
    fun plan(
        missionDirs: List<File>,
        stamps: Map<String, MappingLibraryIndex.MissionFingerprint>,
    ): Plan {
        val ordered = missionDirs.sortedBy { it.name }
        val skip = mutableListOf<File>()
        val verify = mutableListOf<File>()
        for (dir in ordered) {
            val uuid = runCatching { java.util.UUID.fromString(dir.name).toString() == dir.name }.getOrDefault(false)
            if (uuid && unchanged(dir, stamps)) skip += dir else verify += dir
        }
        return Plan(ordered, skip, verify)
    }

    data class Plan(val all: List<File>, val skip: List<File>, val verify: List<File>) {
        val total: Int get() = all.size
        fun message(done: Int): String {
            val n = total.coerceAtLeast(1)
            val k = done.coerceIn(0, n)
            return "Checking mapping storage ($k/$n)…"
        }
    }
}

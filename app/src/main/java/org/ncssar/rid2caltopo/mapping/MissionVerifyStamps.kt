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

    data class Saved(
        val missions: Map<String, MappingLibraryIndex.MissionFingerprint>,
        val originalsModified: Map<String, Long>,
    )

    fun load(filesDir: File): Map<String, MappingLibraryIndex.MissionFingerprint> = loadSaved(filesDir).missions

    fun loadSaved(filesDir: File): Saved {
        val text = runCatching { file(filesDir).readText() }.getOrNull() ?: return Saved(emptyMap(), emptyMap())
        return parseSaved(text)
    }

    fun parse(text: String): Map<String, MappingLibraryIndex.MissionFingerprint> = parseSaved(text).missions

    fun parseSaved(text: String): Saved = runCatching {
        val value = JSONObject(text)
        if (value.optInt("schema") != SCHEMA) return Saved(emptyMap(), emptyMap())
        val missions = value.optJSONObject("missions") ?: return Saved(emptyMap(), emptyMap())
        val parsed = missions.keys().asSequence().mapNotNull { id ->
            val fp = missions.optJSONObject(id) ?: return@mapNotNull null
            id to MappingLibraryIndex.MissionFingerprint.parse(fp)
        }.toMap()
        val originals = mutableMapOf<String, Long>()
        value.optJSONObject("originals")?.let { obj ->
            for (id in obj.keys()) originals[id] = obj.optLong(id)
        }
        Saved(parsed, originals)
    }.getOrDefault(Saved(emptyMap(), emptyMap()))

    fun write(
        filesDir: File,
        stamps: Map<String, MappingLibraryIndex.MissionFingerprint>,
        originalsModified: Map<String, Long>? = null,
    ) {
        val originals = originalsModified ?: loadSaved(filesDir).originalsModified
        val destination = file(filesDir)
        destination.parentFile?.mkdirs()
        val body = JSONObject().put("schema", SCHEMA).put("missions", JSONObject().also { out ->
            stamps.forEach { (id, fp) -> out.put(id, fp.json()) }
        }).put("originals", JSONObject().also { out ->
            originals.forEach { (id, modified) -> out.put(id, modified) }
        })
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(body.toString())
        check(temporary.renameTo(destination) || (destination.delete() && temporary.renameTo(destination))) {
            "Could not save mission verify stamps."
        }
    }

    /**
     * True when folder and manifest mtimes match the last verify.
     * Does not list inventory or originals — a directory mtime change is enough to re-check.
     */
    fun unchanged(
        missionDir: File,
        stamps: Map<String, MappingLibraryIndex.MissionFingerprint>,
    ): Boolean {
        val prior = stamps[missionDir.name] ?: return false
        if (missionDir.lastModified() != prior.dirModified) return false
        if (File(missionDir, "inventory").lastModified() != prior.inventoryModified) return false
        if (MappingLibraryIndex.FileStamp.of(File(missionDir, "mission.json")) != prior.mission) return false
        if (MappingLibraryIndex.FileStamp.of(File(missionDir, "processing-status.json")) != prior.status) return false
        if (MappingLibraryIndex.FileStamp.of(File(missionDir, "received-batch.json")) != prior.batch) return false
        if (MappingLibraryIndex.FileStamp.of(File(missionDir, "route-summary.json")) != prior.route) return false
        return true
    }

    /** Unchanged originals directory mtime: do not walk the photo tree. */
    fun skipOriginalsWalk(storedModified: Long?, currentModified: Long): Boolean =
        storedModified != null && currentModified > 0L && storedModified == currentModified

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

package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Persisted Manage Storage → Mapping pane sizes. Survives restarts. Pane shows the
 * cache immediately; a full rescan runs only when top-level mapping artifacts are
 * newer than the stamp (cheap mtime/count check) or after [invalidate].
 *
 * Totals include every retained byte under the mapping storage roots. Lines are
 * per-project sizes plus Shared layers, Stats, Processing, and Other so they sum
 * to the reported total. Worker-only — never call from Main.
 */
internal object MappingStorageSummary {
    private const val VERSION = 1
    private const val FILE_NAME = "pane-summary.json"
    private val memory = AtomicReference<Report?>(null)

    data class DirStamp(val modified: Long, val count: Int, val signature: String) {
        fun json() = JSONObject().put("m", modified).put("n", count).put("s", signature)
        companion object {
            fun parse(value: JSONObject?) = value?.let {
                DirStamp(it.optLong("m"), it.optInt("n"), it.optString("s"))
            }
            fun of(root: File, skip: (String) -> Boolean = { false }): DirStamp {
                if (!root.exists()) return DirStamp(0L, 0, "")
                // Ignore pane-summary cache writes: use filtered children, not the directory mtime.
                val children = root.listFiles()?.filter { !skip(it.name) }?.sortedBy { it.name }.orEmpty()
                val signature = children.joinToString("|") { "${it.name}:${it.lastModified()}" }
                val modified = children.maxOfOrNull { it.lastModified() } ?: 0L
                return DirStamp(modified, children.size, signature)
            }
        }
    }

    data class Stamp(
        val missions: DirStamp,
        val layers: DirStamp,
        val processing: DirStamp,
        val stats: DirStamp,
        val other: DirStamp,
    ) {
        fun json() = JSONObject()
            .put("missions", missions.json())
            .put("layers", layers.json())
            .put("processing", processing.json())
            .put("stats", stats.json())
            .put("other", other.json())

        companion object {
            fun parse(value: JSONObject?): Stamp? {
                value ?: return null
                return Stamp(
                    DirStamp.parse(value.optJSONObject("missions")) ?: return null,
                    DirStamp.parse(value.optJSONObject("layers")) ?: return null,
                    DirStamp.parse(value.optJSONObject("processing")) ?: return null,
                    DirStamp.parse(value.optJSONObject("stats")) ?: return null,
                    DirStamp.parse(value.optJSONObject("other")) ?: DirStamp(0, 0, ""),
                )
            }
        }
    }

    data class ProjectLine(
        val id: String,
        val name: String,
        val timeLabel: String,
        val photoCount: Int,
        val bytes: Long,
        val layersBytes: Long,
        val workingBytes: Long,
        val complete: Boolean,
        val flightId: String? = null,
        val layerId: String? = null,
    ) {
        fun json() = JSONObject()
            .put("id", id).put("name", name).put("time", timeLabel).put("photos", photoCount)
            .put("bytes", bytes).put("layers", layersBytes).put("working", workingBytes)
            .put("complete", complete)
            .put("flightId", flightId ?: JSONObject.NULL)
            .put("layerId", layerId ?: JSONObject.NULL)

        companion object {
            fun parse(value: JSONObject): ProjectLine? {
                val id = value.optString("id").ifEmpty { return null }
                return ProjectLine(
                    id = id,
                    name = value.optString("name"),
                    timeLabel = value.optString("time"),
                    photoCount = value.optInt("photos"),
                    bytes = value.optLong("bytes"),
                    layersBytes = value.optLong("layers"),
                    workingBytes = value.optLong("working"),
                    complete = value.optBoolean("complete", true),
                    flightId = value.opt("flightId").takeUnless { it == JSONObject.NULL }?.toString(),
                    layerId = value.opt("layerId").takeUnless { it == JSONObject.NULL }?.toString(),
                )
            }
        }
    }

    data class Report(
        val projects: List<ProjectLine>,
        val sharedLayersBytes: Long,
        val statsBytes: Long,
        val processingBytes: Long,
        val otherBytes: Long,
        val totalBytes: Long,
        val complete: Boolean,
        val stamp: Stamp,
    ) {
        /** Project + category lines; equals [totalBytes] when accounting is consistent. */
        fun lineSum(): Long =
            projects.sumOf { it.bytes } + sharedLayersBytes + statsBytes + processingBytes + otherBytes

        fun json() = JSONObject()
            .put("version", VERSION)
            .put("projects", JSONArray().also { arr -> projects.forEach { arr.put(it.json()) } })
            .put("sharedLayers", sharedLayersBytes)
            .put("stats", statsBytes)
            .put("processing", processingBytes)
            .put("other", otherBytes)
            .put("total", totalBytes)
            .put("complete", complete)
            .put("stamp", stamp.json())

        companion object {
            fun parse(text: String): Report? = runCatching {
                val value = JSONObject(text)
                if (value.optInt("version") != VERSION) return null
                val stamp = Stamp.parse(value.optJSONObject("stamp")) ?: return null
                val projects = value.optJSONArray("projects")?.let { arr ->
                    (0 until arr.length()).mapNotNull { ProjectLine.parse(arr.getJSONObject(it)) }
                }.orEmpty()
                Report(
                    projects = projects,
                    sharedLayersBytes = value.optLong("sharedLayers"),
                    statsBytes = value.optLong("stats"),
                    processingBytes = value.optLong("processing"),
                    otherBytes = value.optLong("other"),
                    totalBytes = value.optLong("total"),
                    complete = value.optBoolean("complete", true),
                    stamp = stamp,
                )
            }.getOrNull()
        }
    }

    data class Roots(
        val missions: File,
        val layers: File,
        val processing: File,
        val stats: File,
        val other: List<File> = emptyList(),
    ) {
        fun stamp() = Stamp(
            DirStamp.of(missions),
            DirStamp.of(layers),
            DirStamp.of(processing),
            DirStamp.of(stats) { name ->
                name == FILE_NAME || name.startsWith("$FILE_NAME.") || name.endsWith(".tmp")
            },
            DirStamp(
                other.maxOfOrNull { it.lastModified() } ?: 0L,
                other.size,
                other.sortedBy { it.absolutePath }.joinToString("|") {
                    "${it.name}:${it.lastModified()}:${if (it.isDirectory) (it.list()?.size ?: 0) else it.length()}"
                },
            ),
        )
    }

    fun cacheFile(filesDir: File) = File(filesDir, "mapping-storage-stats/$FILE_NAME")

    fun rootsFor(filesDir: File): Roots = Roots(
        missions = File(filesDir, "mapping-missions"),
        layers = File(filesDir, "mapping-layers"),
        processing = File(filesDir, "mapping-processing"),
        stats = File(filesDir, "mapping-storage-stats"),
    )

    fun offMain() {
        val main = Looper.getMainLooper()
        check(main == null || Looper.myLooper() !== main) { "Mapping storage summary must run off Main" }
    }

    /** Cheap staleness probe: top-level directory mtimes, child counts, and name:mtime signatures. */
    fun isFresh(cached: Report, current: Stamp): Boolean = cached.stamp == current

    fun loadCached(filesDir: File): Report? {
        offMain()
        memory.get()?.let { return it }
        val loaded = runCatching { cacheFile(filesDir).readText() }.getOrNull()?.let(Report::parse) ?: return null
        memory.set(loaded)
        return loaded
    }

    fun invalidate(filesDir: File? = null) {
        memory.set(null)
        filesDir?.let { runCatching { cacheFile(it).delete() } }
    }

    fun save(filesDir: File, report: Report) {
        offMain()
        memory.set(report)
        val target = cacheFile(filesDir)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(report.json().toString())
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target))
            }
        } finally {
            temporary.delete()
        }
        runCatching { org.ncssar.rid2caltopo.storage.OperationalStorage.retain(target) }
    }

    /**
     * Full measure of mapping storage. Project line bytes are the mission folder only so
     * category lines (layers / processing / stats / other) do not double-count. Layer and
     * checkpoint subtitles still come from [MappingMissionStorage] when available.
     * [Report.totalBytes] walks every mapping root; project + category lines sum to it.
     */
    fun measure(filesDir: File, live: MappingProcessingState? = null, context: Context? = null): Report {
        offMain()
        val roots = rootsFor(filesDir)
        val stamp = roots.stamp()
        var complete = true
        fun walk(root: File): Long {
            if (!root.exists()) return 0L
            if (root.isFile) {
                if (!root.canRead()) complete = false
                return root.length()
            }
            val children = root.listFiles() ?: run { complete = false; return 0L }
            return children.sumOf(::walk)
        }

        val physicalLayers = walk(roots.layers)
        val physicalProcessing = walk(roots.processing)
        // Stats exclude the pane-summary cache itself so writing never changes the measured total.
        val physicalStats = roots.stats.listFiles().orEmpty()
            .filterNot { it.name == FILE_NAME || it.name.startsWith("$FILE_NAME.") }
            .sumOf(::walk)
        val physicalOtherRoots = roots.other.sumOf(::walk)

        val library = if (context != null) {
            runCatching { MappingLibraryIndex.refresh(context, live) }.getOrNull()
        } else null
        val storage = MappingMissionStorage.index(filesDir, live)
        val projects = mutableListOf<ProjectLine>()
        val missionDirs = roots.missions.listFiles().orEmpty().filter { it.isDirectory }
        val missionBytes = HashMap<String, Long>()
        for (dir in missionDirs) {
            missionBytes[dir.name] = walk(dir)
        }
        // Loose files directly under mapping-missions count as Other.
        val looseMissionBytes = roots.missions.listFiles().orEmpty().filter { it.isFile }.sumOf(::walk)

        if (library != null) {
            for (entry in MappingLibraryEntry.sorted(library.entries, newestFirst = true)) {
                val flightId = entry.flight?.id
                val usage = flightId?.let { storage.cachedMission(it) ?: storage.mission(it) }
                    ?: entry.layer?.let { MappingMissionStorage.cachedLayer(filesDir, it.id) ?: MappingMissionStorage.layer(filesDir, it.id) }
                    ?: MappingStorageUsage(complete = true)
                if (!usage.complete) complete = false
                val bytes = when {
                    flightId != null -> missionBytes[flightId] ?: 0L
                    entry.layer != null -> walk(File(roots.layers, entry.layer.id))
                    else -> 0L
                }
                projects += ProjectLine(
                    id = entry.id,
                    name = entry.name,
                    timeLabel = entry.timeLabel,
                    photoCount = entry.photoCount,
                    bytes = bytes,
                    layersBytes = usage.maps2D + usage.models3D,
                    workingBytes = usage.working,
                    complete = usage.complete,
                    flightId = flightId,
                    layerId = entry.layer?.id,
                )
            }
            if (library.stale) complete = false
        } else {
            for (dir in missionDirs.sortedBy { it.name }) {
                val id = dir.name
                val usage = storage.cachedMission(id) ?: runCatching { storage.mission(id) }.getOrNull()
                    ?: MappingStorageUsage(complete = complete)
                if (!usage.complete) complete = false
                projects += ProjectLine(
                    id = "flight:$id",
                    name = id,
                    timeLabel = "",
                    photoCount = 0,
                    bytes = missionBytes[id] ?: 0L,
                    layersBytes = usage.maps2D + usage.models3D,
                    workingBytes = usage.working,
                    complete = usage.complete,
                    flightId = id,
                )
            }
        }

        // Imported layers listed as projects are charged on the project line; keep them out of Shared layers.
        val importedLayerBytes = projects.mapNotNull { line ->
            line.layerId?.takeIf { line.flightId == null }?.let { walk(File(roots.layers, it)) }
        }.sum()
        val sharedLayersBytes = (physicalLayers - importedLayerBytes).coerceAtLeast(0L)
        val processingBytes = physicalProcessing
        val statsBytes = physicalStats
        val projectSum = projects.sumOf { it.bytes }
        val accounted = projectSum + sharedLayersBytes + processingBytes + statsBytes
        val physicalTotal = projectSum + sharedLayersBytes + processingBytes + statsBytes +
            looseMissionBytes + physicalOtherRoots
        // Mission dirs not represented in the library still count toward Other.
        val listedMissionIds = projects.mapNotNull { it.flightId }.toSet()
        val orphanMissionBytes = missionBytes.filterKeys { it !in listedMissionIds }.values.sum()
        val otherBytes = looseMissionBytes + physicalOtherRoots + orphanMissionBytes
        val totalBytes = accounted + otherBytes

        return Report(
            projects = projects,
            sharedLayersBytes = sharedLayersBytes,
            statsBytes = statsBytes,
            processingBytes = processingBytes,
            otherBytes = otherBytes,
            totalBytes = totalBytes,
            complete = complete,
            stamp = stamp,
        )
    }

    /**
     * Returns a cached report when the cheap stamp still matches; otherwise measures and
     * persists. Always off-main.
     */
    fun cachedOrRefresh(filesDir: File, live: MappingProcessingState? = null, context: Context? = null, force: Boolean = false): Report {
        offMain()
        val current = rootsFor(filesDir).stamp()
        if (!force) {
            loadCached(filesDir)?.takeIf { isFresh(it, current) }?.let { return it }
        }
        val report = measure(filesDir, live, context)
        save(filesDir, report)
        return report
    }

    /** Instant cache read for UI; null when nothing persisted yet. */
    fun peekMemory(): Report? = memory.get()
}

package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.storage.ArchiveTree
import org.ncssar.rid2caltopo.storage.DocumentArchiveTree
import org.ncssar.rid2caltopo.storage.TemporaryStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Persisted Manage Storage → Local Maps pane sizes. Survives restarts.
 *
 * Change detection stamps real artifacts only (per-mission / per-layer child
 * mtime+count, plus processing). Stats/cache writes under mapping-storage-stats/
 * never stale the stamp. Archive sizes are on-card allocated bytes (block-rounded).
 * The operational-work projection is a separate "Working copy" line and is never
 * part of the archive total. Worker-only — never call from Main.
 */
internal object MappingStorageSummary {
    private const val VERSION = 2
    private const val FILE_NAME = "pane-summary.json"
    const val DEFAULT_BLOCK_SIZE = 4096L
    private val memory = AtomicReference<Report?>(null)
    private val flights = ConcurrentHashMap<String, CompletableFuture<Report>>()

    /** Test hook. Production logs through CTDebug. */
    @Volatile
    var scanLogSink: ((String) -> Unit)? = null

    /** Test hook. Runs on the leader of a cold/stale scan, before the walk. */
    @Volatile
    var beforeScan: (() -> Unit)? = null

    data class DirStamp(val modified: Long, val count: Int, val signature: String) {
        fun json() = JSONObject().put("m", modified).put("n", count).put("s", signature)
        companion object {
            fun parse(value: JSONObject?) = value?.let {
                DirStamp(it.optLong("m"), it.optInt("n"), it.optString("s"))
            }
            fun of(root: File, skip: (String) -> Boolean = { false }): DirStamp {
                if (!root.exists()) return DirStamp(0L, 0, "")
                val children = root.listFiles()?.filter { !skip(it.name) }?.sortedBy { it.name }.orEmpty()
                val signature = children.joinToString("|") { "${it.name}:${it.lastModified()}" }
                val modified = children.maxOfOrNull { it.lastModified() } ?: 0L
                return DirStamp(modified, children.size, signature)
            }
        }
    }

    data class Stamp(
        val projects: Map<String, DirStamp>,
        val processing: DirStamp,
    ) {
        fun json() = JSONObject()
            .put("projects", JSONObject().also { obj ->
                projects.toSortedMap().forEach { (id, stamp) -> obj.put(id, stamp.json()) }
            })
            .put("processing", processing.json())

        companion object {
            fun parse(value: JSONObject?): Stamp? {
                value ?: return null
                if (value.has("missions") && !value.has("projects")) return null
                val projectsObj = value.optJSONObject("projects") ?: return null
                val projects = mutableMapOf<String, DirStamp>()
                for (key in projectsObj.keys()) {
                    projects[key] = DirStamp.parse(projectsObj.optJSONObject(key)) ?: return null
                }
                return Stamp(
                    projects = projects,
                    processing = DirStamp.parse(value.optJSONObject("processing")) ?: return null,
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
        val sized: Boolean = true,
        val flightId: String? = null,
        val layerId: String? = null,
    ) {
        fun json() = JSONObject()
            .put("id", id).put("name", name).put("time", timeLabel).put("photos", photoCount)
            .put("bytes", bytes).put("layers", layersBytes).put("working", workingBytes)
            .put("complete", complete).put("sized", sized)
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
                    sized = value.optBoolean("sized", true),
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
        val logicalBytes: Long,
        val workingCopyBytes: Long,
        val complete: Boolean,
        val stamp: Stamp,
        val scanReason: String = "",
        val scanElapsedMs: Long = 0L,
        val rescannedProjects: Int = 0,
    ) {
        /** Archive lines only — working copy is reported separately. */
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
            .put("logical", logicalBytes)
            .put("workingCopy", workingCopyBytes)
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
                    logicalBytes = value.optLong("logical", value.optLong("total")),
                    workingCopyBytes = value.optLong("workingCopy"),
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
        /** Artifact stamp only — never includes mapping-storage-stats/. */
        fun stamp(): Stamp {
            val projects = linkedMapOf<String, DirStamp>()
            missions.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach {
                projects["mission:${it.name}"] = DirStamp.of(it)
            }
            layers.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach {
                projects["layer:${it.name}"] = DirStamp.of(it)
            }
            return Stamp(projects = projects, processing = DirStamp.of(processing))
        }
    }

    private data class WalkTotals(val allocated: Long, val logical: Long, val complete: Boolean)

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

    fun isFresh(cached: Report, current: Stamp): Boolean = cached.stamp == current

    fun allocatedBytes(logical: Long, blockSize: Long = DEFAULT_BLOCK_SIZE): Long {
        val block = blockSize.coerceAtLeast(1L)
        if (logical <= 0L) return 0L
        return ((logical + block - 1L) / block) * block
    }

    fun blockSizeFor(path: File): Long {
        offMain()
        val probe = generateSequence(path.absoluteFile) { it.parentFile }
            .firstOrNull { it.exists() } ?: return DEFAULT_BLOCK_SIZE
        return runCatching {
            val size = StatFs(probe.absolutePath).blockSizeLong
            if (size > 0L) size else DEFAULT_BLOCK_SIZE
        }.getOrDefault(DEFAULT_BLOCK_SIZE)
    }

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

    fun peekMemory(): Report? = memory.get()

    /** Cheap project rows for first paint before sizes are known. */
    fun projectPlaceholders(filesDir: File, context: Context? = null): Report {
        offMain()
        val roots = rootsFor(filesDir)
        val stamp = roots.stamp()
        val projects = mutableListOf<ProjectLine>()
        val index = runCatching { MappingLibraryIndex.load(filesDir) }.getOrNull()
        if (index != null && context != null) {
            for (entry in MappingLibraryEntry.sorted(
                MappingLibraryIndex.entries(index, context, null, stale = true),
                newestFirst = true,
            )) {
                projects += ProjectLine(
                    id = entry.id,
                    name = entry.name,
                    timeLabel = entry.timeLabel,
                    photoCount = entry.photoCount,
                    bytes = 0L,
                    layersBytes = 0L,
                    workingBytes = 0L,
                    complete = false,
                    sized = false,
                    flightId = entry.flight?.id,
                    layerId = entry.layer?.id,
                )
            }
        }
        if (projects.isEmpty()) {
            for (dir in roots.missions.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
                projects += ProjectLine(
                    id = "flight:${dir.name}",
                    name = dir.name,
                    timeLabel = "",
                    photoCount = 0,
                    bytes = 0L,
                    layersBytes = 0L,
                    workingBytes = 0L,
                    complete = false,
                    sized = false,
                    flightId = dir.name,
                )
            }
        }
        return Report(
            projects = projects,
            sharedLayersBytes = 0L,
            statsBytes = 0L,
            processingBytes = 0L,
            otherBytes = 0L,
            totalBytes = 0L,
            logicalBytes = 0L,
            workingCopyBytes = 0L,
            complete = false,
            stamp = stamp,
            scanReason = "placeholders",
        )
    }

    fun measure(
        filesDir: File,
        live: MappingProcessingState? = null,
        context: Context? = null,
        previous: Report? = null,
        blockSize: Long = blockSizeFor(filesDir),
        archiveRoots: Roots? = null,
        workingCopyRoot: File? = null,
        reason: String = "full",
    ): Report {
        offMain()
        val startedNs = System.nanoTime()
        val localRoots = rootsFor(filesDir)
        val stamp = localRoots.stamp()
        val measureRoots = archiveRoots ?: localRoots
        val previousById = previous?.projects?.associateBy { it.id }.orEmpty()
        val previousStamp = previous?.stamp
        var rescanned = 0
        var complete = true
        var logicalAcc = 0L
        val tally = Any()

        fun fileAlloc(file: File): Long {
            if (isSymlink(file)) return 0L
            val logical = file.length()
            synchronized(tally) { logicalAcc += logical }
            return allocatedBytes(logical, blockSize)
        }

        fun walkFileTree(root: File): WalkTotals {
            val walked = walkAllocated(root, blockSize)
            synchronized(tally) { logicalAcc += walked.logical }
            return walked
        }

        fun measureDir(dir: File, stampKey: String, lineId: String?): Long {
            val current = stamp.projects[stampKey]
            val priorStamp = previousStamp?.projects?.get(stampKey)
            val cached = lineId?.let { previousById[it] }
            if (current != null && priorStamp == current && cached != null && cached.sized) {
                return cached.bytes
            }
            synchronized(tally) { rescanned++ }
            val walked = walkFileTree(dir)
            if (!walked.complete) synchronized(tally) { complete = false }
            return walked.allocated
        }

        val library = libraryForScan(filesDir, context, live)

        val missionBytes = linkedMapOf<String, Long>()
        val missionDirs = measureRoots.missions.listFiles().orEmpty().filter { it.isDirectory && !isSymlink(it) }
            .sortedBy { it.name }
        if (missionDirs.size <= 1) {
            for (dir in missionDirs) {
                missionBytes[dir.name] = measureDir(dir, "mission:${dir.name}", "flight:${dir.name}")
            }
        } else {
            val pool = Executors.newFixedThreadPool(minOf(4, missionDirs.size))
            try {
                val futures = missionDirs.map { dir ->
                    pool.submit<Pair<String, Long>> {
                        dir.name to measureDir(dir, "mission:${dir.name}", "flight:${dir.name}")
                    }
                }
                for (future in futures) {
                    val (name, bytes) = future.get()
                    missionBytes[name] = bytes
                }
            } finally {
                pool.shutdown()
            }
        }
        val looseMission = measureRoots.missions.listFiles().orEmpty().filter { it.isFile }.sumOf { fileAlloc(it) }

        val projects = mutableListOf<ProjectLine>()
        if (library != null) {
            for (entry in MappingLibraryEntry.sorted(library.entries, newestFirst = true)) {
                val flightId = entry.flight?.id
                val layerId = entry.layer?.id
                val bytes = when {
                    flightId != null -> missionBytes[flightId] ?: 0L
                    layerId != null -> measureDir(
                        File(measureRoots.layers, layerId),
                        "layer:$layerId",
                        entry.id,
                    )
                    else -> 0L
                }
                val prior = previousById[entry.id]
                projects += ProjectLine(
                    id = entry.id,
                    name = entry.name,
                    timeLabel = entry.timeLabel,
                    photoCount = entry.photoCount,
                    bytes = bytes,
                    layersBytes = prior?.layersBytes ?: 0L,
                    workingBytes = prior?.workingBytes ?: 0L,
                    complete = true,
                    sized = true,
                    flightId = flightId,
                    layerId = layerId,
                )
            }
            if (library.stale) complete = false
        } else {
            for ((id, bytes) in missionBytes) {
                projects += ProjectLine(
                    id = "flight:$id",
                    name = id,
                    timeLabel = "",
                    photoCount = 0,
                    bytes = bytes,
                    layersBytes = 0L,
                    workingBytes = 0L,
                    complete = true,
                    sized = true,
                    flightId = id,
                )
            }
        }

        val importedLayerIds = projects.mapNotNull { line ->
            line.layerId?.takeIf { line.flightId == null }
        }.toSet()
        var sharedLayersBytes = 0L
        for (child in measureRoots.layers.listFiles().orEmpty()) {
            when {
                child.isFile -> sharedLayersBytes += fileAlloc(child)
                child.isDirectory && child.name !in importedLayerIds -> {
                    val walked = walkFileTree(child)
                    if (!walked.complete) complete = false
                    sharedLayersBytes += walked.allocated
                }
            }
        }

        val processingWalk = walkFileTree(measureRoots.processing)
        if (!processingWalk.complete) complete = false
        val processingBytes = processingWalk.allocated

        var statsBytes = 0L
        for (child in measureRoots.stats.listFiles().orEmpty()) {
            if (child.name == FILE_NAME || child.name.startsWith("$FILE_NAME.") || child.name.endsWith(".tmp")) continue
            val walked = walkFileTree(child)
            if (!walked.complete) complete = false
            statsBytes += walked.allocated
        }

        val listedMissionIds = projects.mapNotNull { it.flightId }.toSet()
        val orphanMissionBytes = missionBytes.filterKeys { it !in listedMissionIds }.values.sum()
        val otherRoots = measureRoots.other.sumOf {
            val walked = walkFileTree(it)
            if (!walked.complete) complete = false
            walked.allocated
        }
        val otherBytes = looseMission + otherRoots + orphanMissionBytes
        val projectSum = projects.sumOf { it.bytes }
        val totalBytes = projectSum + sharedLayersBytes + processingBytes + statsBytes + otherBytes

        val workingRoot = workingCopyRoot ?: resolveWorkingCopy(filesDir, context)
        // When archiveRoots was not supplied and local roots are the working projection, the
        // totals above already describe the working copy. Still report workingCopyBytes from the
        // operational-work root when distinct; never mutate that tree.
        val workingCopyBytes = workingRoot?.let { walkAllocated(it, blockSize).allocated } ?: 0L

        val logicalBytes = if (logicalAcc > 0L) logicalAcc else totalBytes
        val elapsed = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)
        val report = Report(
            projects = projects,
            sharedLayersBytes = sharedLayersBytes,
            statsBytes = statsBytes,
            processingBytes = processingBytes,
            otherBytes = otherBytes,
            totalBytes = totalBytes,
            logicalBytes = logicalBytes,
            workingCopyBytes = workingCopyBytes,
            complete = complete,
            stamp = stamp,
            scanReason = reason,
            scanElapsedMs = elapsed,
            rescannedProjects = rescanned,
        )
        logScan(report)
        return report
    }

    fun cachedOrRefresh(
        filesDir: File,
        live: MappingProcessingState? = null,
        context: Context? = null,
        force: Boolean = false,
        blockSize: Long? = null,
        archiveRoots: Roots? = null,
        workingCopyRoot: File? = null,
    ): Report {
        offMain()
        val current = rootsFor(filesDir).stamp()
        val cached = loadCached(filesDir)
        if (!force && cached != null && isFresh(cached, current)) {
            logScan(cached.copy(scanReason = "cache", scanElapsedMs = 0L))
            return cached
        }
        val flightKey = filesDir.absolutePath
        val created = CompletableFuture<Report>()
        val existing = flights.putIfAbsent(flightKey, created)
        if (existing != null) return existing.get(15, TimeUnit.MINUTES)
        try {
            val report = refreshUnlocked(
                filesDir, live, context, force, blockSize, archiveRoots, workingCopyRoot, cached,
            )
            created.complete(report)
            return report
        } catch (error: Throwable) {
            created.completeExceptionally(error)
            throw error
        } finally {
            flights.remove(flightKey, created)
        }
    }

    private fun refreshUnlocked(
        filesDir: File,
        live: MappingProcessingState?,
        context: Context?,
        force: Boolean,
        blockSize: Long?,
        archiveRoots: Roots?,
        workingCopyRoot: File?,
        cached: Report?,
    ): Report {
        val reason = when {
            force -> "forced"
            cached == null -> "cold"
            else -> "stale"
        }
        val resolvedBlock = blockSize ?: blockSizeFor(archiveVolumeFile(context) ?: filesDir)
        val resolvedArchive = archiveRoots ?: resolveArchiveRoots(context, resolvedBlock)
        val resolvedWorking = workingCopyRoot ?: resolveWorkingCopy(filesDir, context)
        beforeScan?.invoke()
        val report = if (resolvedArchive is SafArchiveRoots) {
            measureFromSaf(
                filesDir = filesDir,
                live = live,
                context = context,
                previous = cached?.takeIf { !force },
                blockSize = resolvedBlock,
                saf = resolvedArchive,
                workingCopyRoot = resolvedWorking,
                reason = reason,
            )
        } else {
            measure(
                filesDir = filesDir,
                live = live,
                context = context,
                previous = cached?.takeIf { !force },
                blockSize = resolvedBlock,
                archiveRoots = resolvedArchive as? Roots,
                workingCopyRoot = resolvedWorking,
                reason = reason,
            )
        }
        save(filesDir, report)
        return report
    }

    fun scanLogLine(report: Report): String {
        val total = formatGb(report.totalBytes)
        val working = formatGb(report.workingCopyBytes)
        val seconds = String.format(Locale.US, "%.1fs", report.scanElapsedMs / 1000.0)
        return "Mapping storage scan: projects=${report.projects.size} rescanned=${report.rescannedProjects} " +
            "total=$total working=$working $seconds reason=${report.scanReason.ifEmpty { "full" }}"
    }

    private fun logScan(report: Report) {
        val line = scanLogLine(report)
        scanLogSink?.invoke(line)
        runCatching {
            org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("MappingStorage", line)
        }
    }

    private fun formatGb(bytes: Long): String =
        String.format(Locale.US, "%.1fGB", bytes / 1_000_000_000.0)

    private fun filesDirIsWorkingProjection(filesDir: File, workingRoot: File): Boolean {
        val alias = File(filesDir, "mapping-missions")
        if (!alias.exists()) return false
        return runCatching {
            val target = File(Os.readlink(alias.path)).canonicalFile
            target.path.startsWith(workingRoot.canonicalFile.path)
        }.getOrDefault(false)
    }

    /**
     * Resolve on-card archive mapping roots. Returns [Roots] for file:// archives, or a
     * [SafArchiveRoots] handle for SAF trees. Null when Archive Dir is unavailable.
     */
    fun resolveArchiveRoots(context: Context?, blockSize: Long = DEFAULT_BLOCK_SIZE): Any? {
        context ?: return null
        offMain()
        val archive = runCatching { org.ncssar.rid2caltopo.data.CaltopoClient.GetArchiveDir() }.getOrNull()
            ?: return null
        val uri = archive.uri
        if (uri.scheme == "file") {
            val root = File(requireNotNull(uri.path))
            return fileArchiveRoots(root)
        }
        return runCatching {
            SafArchiveRoots(DocumentArchiveTree(context, archive), blockSize)
        }.getOrNull()
    }

    fun fileArchiveRoots(archiveRoot: File): Roots? {
        val current = File(archiveRoot, "mapping")
        if (File(current, "missions").exists() || File(current, "layers").exists() || current.isDirectory) {
            return Roots(
                missions = File(current, "missions"),
                layers = File(current, "layers"),
                processing = File(current, "processing"),
                stats = File(current, "stats"),
            )
        }
        val legacy = File(archiveRoot, "cache/local-maps")
        if (legacy.exists()) {
            return Roots(
                missions = File(legacy, "mapping-missions"),
                layers = File(legacy, "mapping-layers"),
                processing = File(legacy, "mapping-processing"),
                stats = File(legacy, "mapping-storage-stats"),
            )
        }
        return null
    }

    /** operational-work/<hash> that filesDir aliases point at. Never creates or deletes. */
    fun resolveWorkingCopy(filesDir: File, context: Context?): File? {
        offMain()
        val alias = File(filesDir, "mapping-missions")
        if (alias.exists()) {
            runCatching {
                val target = File(Os.readlink(alias.path)).canonicalFile
                // …/operational-work/<hash>/missions
                val parent = target.parentFile ?: return@runCatching null
                if (parent.parentFile?.name == "operational-work") return parent
                if (target.name == "operational-work") return target
            }
        }
        context ?: return null
        for (cacheRoot in TemporaryStorage.roots(context)) {
            val sessions = File(cacheRoot, "operational-work").listFiles()?.filter {
                it.isDirectory && it.name.matches(Regex("[a-f0-9]{16}"))
            }.orEmpty()
            val best = sessions.maxByOrNull { it.lastModified() }
            if (best != null) return best
        }
        return null
    }

    private class SafArchiveRoots(val tree: ArchiveTree, val blockSize: Long) {
        fun walk(path: String): WalkTotals {
            if (!tree.exists(path)) return WalkTotals(0L, 0L, true)
            var allocated = 0L
            var logical = 0L
            var complete = true
            fun rec(p: String) {
                val entries = runCatching { tree.list(p) }.getOrElse {
                    complete = false
                    return
                }
                for (entry in entries) {
                    val child = if (p.isEmpty()) entry.name else "$p/${entry.name}"
                    if (entry.directory) {
                        rec(child)
                    } else {
                        logical += entry.bytes
                        allocated += allocatedBytes(entry.bytes, blockSize)
                    }
                }
            }
            rec(path)
            return WalkTotals(allocated, logical, complete)
        }

        fun listNames(path: String): List<String> =
            if (!tree.exists(path)) emptyList()
            else runCatching { tree.list(path).filter { it.directory }.map { it.name } }.getOrDefault(emptyList())
    }

    private fun measureFromSaf(
        filesDir: File,
        live: MappingProcessingState?,
        context: Context?,
        previous: Report?,
        blockSize: Long,
        saf: SafArchiveRoots,
        workingCopyRoot: File?,
        reason: String,
    ): Report {
        val startedNs = System.nanoTime()
        val localRoots = rootsFor(filesDir)
        val stamp = localRoots.stamp()
        val previousById = previous?.projects?.associateBy { it.id }.orEmpty()
        val previousStamp = previous?.stamp
        var rescanned = 0
        var complete = true
        var logicalAcc = 0L

        val base = when {
            saf.tree.exists("mapping/missions") || saf.tree.exists("mapping/layers") -> "mapping"
            saf.tree.exists("cache/local-maps/mapping-missions") -> "cache/local-maps"
            else -> null
        }
        val missionPrefix = if (base == "mapping") "mapping/missions" else "cache/local-maps/mapping-missions"
        val layerPrefix = if (base == "mapping") "mapping/layers" else "cache/local-maps/mapping-layers"
        val processingPrefix = if (base == "mapping") "mapping/processing" else "cache/local-maps/mapping-processing"
        val statsPrefix = if (base == "mapping") "mapping/stats" else "cache/local-maps/mapping-storage-stats"

        fun measurePath(path: String, stampKey: String?, lineId: String?): Long {
            if (stampKey != null) {
                val current = stamp.projects[stampKey]
                val priorStamp = previousStamp?.projects?.get(stampKey)
                val cached = lineId?.let { previousById[it] }
                if (current != null && priorStamp == current && cached != null && cached.sized) {
                    return cached.bytes
                }
                rescanned++
            }
            val walked = saf.walk(path)
            if (!walked.complete) complete = false
            logicalAcc += walked.logical
            return walked.allocated
        }

        val missionBytes = linkedMapOf<String, Long>()
        if (base != null) {
            for (name in saf.listNames(missionPrefix)) {
                missionBytes[name] = measurePath("$missionPrefix/$name", "mission:$name", "flight:$name")
            }
        }

        val library = libraryForScan(filesDir, context, live)
        val projects = mutableListOf<ProjectLine>()
        if (library != null) {
            for (entry in MappingLibraryEntry.sorted(library.entries, newestFirst = true)) {
                val flightId = entry.flight?.id
                val layerId = entry.layer?.id
                val bytes = when {
                    flightId != null -> missionBytes[flightId] ?: 0L
                    layerId != null && base != null ->
                        measurePath("$layerPrefix/$layerId", "layer:$layerId", entry.id)
                    else -> 0L
                }
                val prior = previousById[entry.id]
                projects += ProjectLine(
                    id = entry.id,
                    name = entry.name,
                    timeLabel = entry.timeLabel,
                    photoCount = entry.photoCount,
                    bytes = bytes,
                    layersBytes = prior?.layersBytes ?: 0L,
                    workingBytes = prior?.workingBytes ?: 0L,
                    complete = true,
                    sized = true,
                    flightId = flightId,
                    layerId = layerId,
                )
            }
            if (library.stale) complete = false
        } else {
            for ((id, bytes) in missionBytes) {
                projects += ProjectLine(
                    id = "flight:$id",
                    name = id,
                    timeLabel = "",
                    photoCount = 0,
                    bytes = bytes,
                    layersBytes = 0L,
                    workingBytes = 0L,
                    complete = true,
                    sized = true,
                    flightId = id,
                )
            }
        }

        val importedLayerIds = projects.mapNotNull { line ->
            line.layerId?.takeIf { line.flightId == null }
        }.toSet()
        var sharedLayersBytes = 0L
        if (base != null) {
            for (entry in runCatching { saf.tree.list(layerPrefix) }.getOrDefault(emptyList())) {
                val child = "$layerPrefix/${entry.name}"
                if (entry.directory) {
                    if (entry.name in importedLayerIds) continue
                    val walked = saf.walk(child)
                    if (!walked.complete) complete = false
                    logicalAcc += walked.logical
                    sharedLayersBytes += walked.allocated
                } else {
                    logicalAcc += entry.bytes
                    sharedLayersBytes += allocatedBytes(entry.bytes, blockSize)
                }
            }
        }

        val processingWalk = if (base != null) saf.walk(processingPrefix) else WalkTotals(0, 0, true)
        if (!processingWalk.complete) complete = false
        logicalAcc += processingWalk.logical
        val statsWalk = if (base != null) saf.walk(statsPrefix) else WalkTotals(0, 0, true)
        if (!statsWalk.complete) complete = false
        logicalAcc += statsWalk.logical

        val listedMissionIds = projects.mapNotNull { it.flightId }.toSet()
        val orphanMissionBytes = missionBytes.filterKeys { it !in listedMissionIds }.values.sum()
        val otherBytes = orphanMissionBytes
        val projectSum = projects.sumOf { it.bytes }
        val totalBytes = projectSum + sharedLayersBytes + processingWalk.allocated + statsWalk.allocated + otherBytes

        val workingCopyBytes = workingCopyRoot?.let { walkAllocated(it, blockSize).allocated } ?: 0L

        val elapsed = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)
        val report = Report(
            projects = projects,
            sharedLayersBytes = sharedLayersBytes,
            statsBytes = statsWalk.allocated,
            processingBytes = processingWalk.allocated,
            otherBytes = otherBytes,
            totalBytes = totalBytes,
            logicalBytes = if (logicalAcc > 0L) logicalAcc else totalBytes,
            workingCopyBytes = workingCopyBytes,
            complete = complete,
            stamp = stamp,
            scanReason = reason,
            scanElapsedMs = elapsed,
            rescannedProjects = rescanned,
        )
        logScan(report)
        return report
    }

    private data class LibraryView(val entries: List<MappingLibraryEntry>, val stale: Boolean)

    /** Loaded index when one exists. A full library refresh walks every mission and is the slow path. */
    private fun libraryForScan(
        filesDir: File,
        context: Context?,
        live: MappingProcessingState?,
    ): LibraryView? {
        context ?: return null
        val loaded = runCatching { MappingLibraryIndex.load(filesDir) }.getOrNull()
        if (loaded != null) {
            val entries = runCatching { MappingLibraryIndex.entries(loaded, context, live) }.getOrNull()
                ?: return null
            return LibraryView(entries, false)
        }
        val refreshed = runCatching { MappingLibraryIndex.refresh(context, live) }.getOrNull() ?: return null
        return LibraryView(refreshed.entries, refreshed.stale)
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * Allocated size using the filesystem cluster size. Does not follow symlinks and does not
     * trust FUSE `st_blocks` (that reports logical 512-byte units on exFAT).
     */
    private fun walkAllocated(root: File, blockSize: Long): WalkTotals {
        if (!root.exists()) return WalkTotals(0L, 0L, true)
        // A root symlink is the directory we were asked to measure (filesDir projection).
        // Nested symlinks are not followed, so a working copy cannot be counted twice.
        val start = if (isSymlink(root)) runCatching { root.canonicalFile }.getOrDefault(root) else root
        if (!start.exists()) return WalkTotals(0L, 0L, true)
        var allocated = 0L
        var logical = 0L
        var ok = true
        val stack = ArrayDeque<File>()
        stack.add(start)
        while (stack.isNotEmpty()) {
            val next = stack.removeFirst()
            if (isSymlink(next)) continue
            val attrs = runCatching {
                Files.readAttributes(next.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
            if (attrs == null) {
                ok = false
                continue
            }
            if (attrs.isDirectory) {
                val children = next.listFiles()
                if (children == null) ok = false else children.forEach { stack.add(it) }
            } else {
                val len = attrs.size()
                logical += len
                allocated += allocatedBytes(len, blockSize)
            }
        }
        return WalkTotals(allocated, logical, ok)
    }

    /** StatFs path for the archive volume (SD card), not the internal filesDir. */
    private fun archiveVolumeFile(context: Context?): File? {
        context ?: return null
        val uri = runCatching { org.ncssar.rid2caltopo.data.CaltopoClient.GetArchiveDir()?.uri }.getOrNull()
            ?: return null
        if (uri.scheme.equals("file", ignoreCase = true)) return uri.path?.let { File(it) }
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volumeId = documentId.substringBefore(':')
        val storageManager = context.getSystemService(StorageManager::class.java)
        val volumeDirectory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageManager?.storageVolumes?.firstOrNull { volume ->
                if (volumeId.equals("primary", ignoreCase = true)) volume.isPrimary
                else volume.uuid.equals(volumeId, ignoreCase = true)
            }?.directory
        } else null
        return volumeDirectory ?: when {
            volumeId.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
            volumeId.isNotBlank() -> File("/storage/$volumeId")
            else -> null
        }
    }
}

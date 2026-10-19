package org.ncssar.rid2caltopo.mapping

import android.system.Os
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Allocated file blocks on Android. A hard link is charged once within a mission's complete footprint. */
data class MappingStorageUsage(
    val originals: Long = 0, val controllerReturns: Long = 0, val maps2D: Long = 0,
    val models3D: Long = 0, val exports: Long = 0, val working: Long = 0,
    val metadata: Long = 0, val complete: Boolean = true, val planning: Long = 0
) {
    val total get() = originals + controllerReturns + maps2D + models3D + exports + working + metadata + planning
    val totalLabel get() = "${if (complete) "" else "At least "}${format(total)}"
    companion object {
        fun format(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
            bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

/** Products are immutable after publication; cache their stat results, never photo/model contents. */
object MappingMissionStorage {
    private enum class Kind { ORIGINAL, RETURNED, MAP, MODEL, EXPORT, WORK, METADATA, PLAN }
    private data class Entry(val identity: String, val bytes: Long, val kind: Kind)
    private data class Cached(val generation: String, val directories: Map<String, Long>, val probes: Map<String, List<Long>>, val entries: List<Entry>, val complete: Boolean)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private fun json(file: File) = runCatching { JSONObject(file.readText()) }.getOrNull()
    private fun uuid(value: String?): String? = value?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }

    class Index internal constructor(
        private val filesDir: File, private val products: List<Pair<File, String?>>, private val work: List<Pair<File, String?>>, private val active: Boolean
    ) {
        internal fun resultRoots(id: String): List<File> = products.filter { it.second == id }.map { it.first }
        internal fun workRoots(id: String): List<File> = work.filter { it.second == id }.map { it.first }
        fun mission(id: String): MappingStorageUsage {
            require(uuid(id) == id)
            val roots = listOf(File(filesDir, "mapping-missions/$id")) + products.filter { it.second == id }.map { it.first }
            return measure(roots, work.filter { it.second == id }.map { it.first }, active)
        }
        fun cachedMission(id: String): MappingStorageUsage? {
            require(uuid(id) == id)
            return cachedUsage(listOf(File(filesDir, "mapping-missions/$id")) + resultRoots(id), workRoots(id), active)
        }
        fun unassigned(): MappingStorageUsage = measure(products.filter { it.second == null }.map { it.first }, work.filter { it.second == null }.map { it.first }, active)

        fun freeBytes(): Long = filesDir.usableSpace
    }

    fun index(filesDir: File, live: MappingProcessingState? = null): Index {
        val missions = File(filesDir, "mapping-missions").listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { root ->
            val record = json(File(root, "mission.json")) ?: return@mapNotNull null
            val id = uuid(record.optString("missionId")) ?: return@mapNotNull null
            if (root.name != id) return@mapNotNull null
            Triple(id, uuid(json(File(root, "processing-status.json"))?.optString("layerId")),
                File(root, "inventory").listFiles().orEmpty().filter { it.extension == "json" }.map { it.nameWithoutExtension }.toSet())
        }
        val ids = missions.map { it.first }.toSet()
        fun owner(root:File, result:Boolean):String? {
            val report = if(result)json(File(root, "layer.json")) else null
            val explicit = uuid(report?.optString("missionId")) ?: uuid(json(File(root, "work-owner.json"))?.optString("missionId"))
            val references = if(result)missions.filter { it.second == root.name }.map { it.first } else emptyList()
            // Older RTK results carry exact original digests. Use them only when ownership is unambiguous.
            val hashes = (report?.optJSONArray("originalSha256") ?: json(File(root, "surface/cameras.json"))?.optJSONArray("originalSha256"))?.let { array ->
                runCatching { (0 until array.length()).map { array.getString(it) }.toSet() }.getOrNull()
            }
            val candidates = if (!hashes.isNullOrEmpty()) missions.filter { it.third.containsAll(hashes) }.map { it.first } else emptyList()
            return when {
                explicit != null -> explicit.takeIf { it in ids }
                references.size == 1 -> references.single()
                candidates.size == 1 -> candidates.single()
                else -> null
            }
        }
        val products = File(filesDir, "mapping-layers").listFiles().orEmpty().filter { it.isDirectory }.map { it to owner(it,true) }
        val workRoots=File(filesDir,"mapping-processing").listFiles().orEmpty().filter{it.isDirectory}
        val work=workRoots.map { directory -> directory to (owner(directory,false) ?: live?.takeIf { it.active&&it.missionId in ids&&workRoots.size==1 }?.missionId) }
        return Index(filesDir, products, work, live?.active == true)
    }

    fun layer(filesDir: File, id: String): MappingStorageUsage {
        require(uuid(id) == id)
        return measure(listOf(File(filesDir, "mapping-layers/$id")), emptyList())
    }

    // Cache beside the trees, so writing statistics never changes the measured footprint.
    private fun cacheFile(root: File, suffix: String = ".json"): File {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(root.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(root.parentFile.parentFile, "mapping-storage-stats/$key$suffix")
    }
    private fun generation(root: File) = runCatching { cacheFile(root,".generation").readText() }.getOrDefault("")

    /** Writers call before and after in-place changes, which directory timestamps cannot detect. */
    fun invalidate(file: File) {
        var root: File? = file.absoluteFile
        while(root != null && root.parentFile?.name !in setOf("mapping-missions","mapping-layers","mapping-processing")) root=root.parentFile
        root ?: return
        cache.remove(root.absolutePath)
        val marker=cacheFile(root,".generation")
        runCatching { marker.parentFile!!.mkdirs(); marker.writeText(UUID.randomUUID().toString()) }
        cacheFile(root).delete()
    }
    internal fun clearMemoryCache() { cache.clear() }

    private fun valid(root: File, value: Cached): Boolean = value.complete && value.generation == generation(root) &&
        value.directories.all { (path, time) -> File(root,path).let { it.isDirectory && it.lastModified()==time } } &&
        value.probes.all { (path, stamp) -> File(root,path).let { listOf(it.lastModified(),it.length())==stamp } }

    private fun cached(root: File): Cached? {
        if(!root.exists()) return Cached(generation(root),emptyMap(),emptyMap(),emptyList(),true)
        val found=cache[root.absolutePath] ?: runCatching {
            val record=JSONObject(cacheFile(root).readText())
            require(record.getInt("version")==1 && record.getString("root")==root.absolutePath)
            val dirs=record.getJSONObject("directories")
            val probes=record.getJSONObject("probes")
            val rows=record.getJSONArray("entries")
            Cached(record.getString("generation"),dirs.keys().asSequence().associateWith { dirs.getLong(it) },
                probes.keys().asSequence().associateWith { key -> probes.getJSONArray(key).let { listOf(it.getLong(0),it.getLong(1)) } },
                (0 until rows.length()).map { rows.getJSONArray(it).let { row -> Entry(row.getString(0),row.getLong(1),Kind.valueOf(row.getString(2))) } },true)
        }.getOrNull()?.also { cache[root.absolutePath]=it }
        return found?.takeIf { valid(root,it) }
    }

    fun cachedLayer(filesDir: File, id: String): MappingStorageUsage? {
        require(uuid(id)==id)
        return cachedUsage(listOf(File(filesDir,"mapping-layers/$id")),emptyList(),false)
    }
    private fun cachedUsage(roots: List<File>, work: List<File>, active: Boolean): MappingStorageUsage? {
        if(active && work.isNotEmpty())return null
        val values=(roots+work).map { cached(it) ?: return null }
        return aggregate(values.flatMap { it.entries },values.all { it.complete })
    }
    private fun measure(roots: List<File>, work: List<File>, active: Boolean = false): MappingStorageUsage {
        val values=roots.map { collectCached(it,false) } + work.map { collectCached(it,true,active) }
        return aggregate(values.flatMap { it.entries },values.all { it.complete })
    }
    private fun collectCached(root: File, working: Boolean, active: Boolean = false): Cached {
        if(!active)cached(root)?.let { return it }
        val before=generation(root)
        val directories=linkedMapOf<String,Long>()
        val probes=linkedMapOf<String,List<Long>>()
        val collected=collect(root,working,directories,probes)
        val value=Cached(before,directories,probes,collected.first,collected.second)
        if(!active && root.exists() && valid(root,value)) {
            val record=JSONObject().put("version",1).put("root",root.absolutePath).put("generation",before)
                .put("directories",JSONObject(directories)).put("probes",JSONObject().apply { probes.forEach { (key,stamp)->put(key,JSONArray(stamp)) } })
                .put("entries",JSONArray().apply { value.entries.forEach { put(JSONArray(listOf(it.identity,it.bytes,it.kind.name))) } })
            runCatching {
                val target=cacheFile(root);target.parentFile!!.mkdirs()
                val temporary=File(target.parentFile,"${target.name}.${UUID.randomUUID()}.tmp")
                try { temporary.writeText(record.toString()); if(valid(root,value)) { check(temporary.renameTo(target));cache[root.absolutePath]=value } }
                finally { temporary.delete() }
            }
        }
        return value
    }
    private fun aggregate(entries: List<Entry>, complete: Boolean): MappingStorageUsage {
        val seen = mutableSetOf<String>(); val sizes = LongArray(Kind.entries.size)
        // Prefer the original-photo category when the controller object is a second link to the same bytes.
        entries.sortedBy { it.kind.ordinal }.forEach { entry -> if (seen.add(entry.identity)) sizes[entry.kind.ordinal] += entry.bytes }
        return MappingStorageUsage(sizes[0], sizes[1], sizes[2], sizes[3], sizes[4], sizes[5], sizes[6], complete, sizes[7])
    }

    private fun collect(root: File, working: Boolean, directories: MutableMap<String,Long>, probes: MutableMap<String,List<Long>>): Pair<List<Entry>, Boolean> {
        val entries = mutableListOf<Entry>(); var complete = true
        if (!root.exists()) return entries to true
        runCatching {
            val physical=root.canonicalFile
            physical.walkTopDown().onEnter {
                val path=it.relativeTo(physical).invariantSeparatorsPath
                val allowed=!Files.isSymbolicLink(it.toPath()) || (File(root,"mapping-volume.json").isFile && path in listOf("originals","pending","cloud/storage"))
                if(allowed)directories[path]=it.lastModified()
                allowed
            }.onFail { _, _ -> complete = false }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }.forEach { file ->
                    val name = file.relativeTo(physical).invariantSeparatorsPath
                    // Small mutable metadata and exports may be overwritten in place.
                    if('/' !in name || name in setOf("surface/surface.json","surface/collection.json"))probes[name]=listOf(file.lastModified(),file.length())
                    val kind = when {
                        working -> Kind.WORK
                        root.parentFile.name == "mapping-missions" && name in setOf("mission.json", "boundary.kml", "mission.kmz", "route-summary.json") -> Kind.PLAN
                        root.parentFile.name == "mapping-layers" && name.matches(Regex("[0-9]+/[0-9]+/[0-9]+\\.png")) -> Kind.MAP
                        root.parentFile.name == "mapping-layers" && name.startsWith("surface/") -> Kind.MODEL
                        root.parentFile.name == "mapping-layers" && name in setOf("share.zip", "map.tif") -> Kind.EXPORT
                        name.startsWith("originals/") -> Kind.ORIGINAL
                        name.startsWith("cloud/storage/") || name.startsWith("pending/") -> Kind.RETURNED
                        else -> Kind.METADATA
                    }
                    runCatching { entry(file, kind) }.onSuccess { entries.add(it) }.onFailure { complete = false }
                }
        }.onFailure { complete = false }
        return entries to complete
    }

    private fun entry(file: File, kind: Kind): Entry {
        return runCatching {
            val stat = Os.lstat(file.absolutePath)
            Entry("${stat.st_dev}:${stat.st_ino}", stat.st_blocks * 512, kind)
        }.getOrElse {
            // Host tests and file systems without Android stat support: count bytes and retain hard-link identity.
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            Entry(attributes.fileKey()?.toString() ?: file.canonicalPath, attributes.size(), kind)
        }
    }
}

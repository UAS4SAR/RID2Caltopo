package org.ncssar.rid2caltopo.ui

import android.content.Context
import android.provider.DocumentsContract
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRoot
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRootResolver
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings
import org.ncssar.rid2caltopo.video.mapcache.MapCacheUsage
import org.ncssar.rid2caltopo.video.mapcache.UnifiedMapCache
import org.ncssar.rid2caltopo.video.queryAvailableCacheBytes
import java.io.File
import kotlin.coroutines.coroutineContext

internal data class LocalMapStorage(val projects: Long, val layers: Long, val processing: Long, val complete: Boolean) {
    val total: Long get() = projects + layers + processing
}

/** Worker-only inventory; resolve aliases so internal links and SD-card targets count once. */
internal fun localMapStorage(roots: List<List<File>>, checkCancelled: () -> Unit = {}): LocalMapStorage {
    val visited = HashSet<String>()
    var complete = true
    fun measure(root: File): Long {
        checkCancelled()
        val canonical = try { root.canonicalFile } catch (_: Exception) { complete = false; return 0 }
        if (!visited.add(canonical.path)) return 0
        if (!canonical.exists()) {
            if (java.nio.file.Files.isSymbolicLink(root.toPath())) complete = false
            return 0
        }
        if (canonical.isDirectory) {
            val children = canonical.listFiles() ?: run { complete = false; return 0 }
            return children.sumOf(::measure)
        }
        if (!canonical.canRead()) complete = false
        return canonical.length()
    }
    val totals = roots.map { group -> group.sumOf(::measure) }
    return LocalMapStorage(totals[0], totals[1], totals[2], complete)
}

internal data class MapStorageSnapshot(
    val cache: MapCacheUsage,
    val local: LocalMapStorage,
    val maximumBytes: Long,
    val maximumDays: Long,
    val internalFree: Long,
    val cacheFree: Long?,
    val cacheLocation: String,
    val flightUsed: Long = 0,
    val flightMaximum: Long = 0
)

/** Invoke on Dispatchers.IO; provider queries, traversal and stat calls all stay here. */
private val mapInventoryGate = Mutex()

internal suspend fun readMapStorage(context: Context): MapStorageSnapshot = mapInventoryGate.withLock {
    val current = coroutineContext
    current.ensureActive()
    val cache = UnifiedMapCache.usage(context)
    val selected = MapCacheRootResolver.resolveRoot(context)
    val location = when (selected) {
        is MapCacheRoot.FileBacked -> if (selected.dir.path.startsWith(context.applicationInfo.dataDir)) "Internal storage" else "Selected archive storage"
        is MapCacheRoot.SafBacked -> {
            val volume = if (selected.dir.uri.authority == "com.android.externalstorage.documents")
                runCatching { DocumentsContract.getTreeDocumentId(selected.dir.uri).substringBefore(':') }.getOrNull() else null
            when (volume) { "primary" -> "Internal storage"; null -> "Selected document provider"; else -> "SD card ($volume)" }
        }
    }
    val external = context.getExternalFilesDirs(null).filterNotNull().map { File(it, "local-mapping") }
    val groups = listOf("mapping-missions", "mapping-layers", "mapping-processing").map { category ->
        listOf(File(context.filesDir, category)) + external.map { File(it, category) }
    }
    val local = localMapStorage(groups) { current.ensureActive() }
    val flight = org.ncssar.rid2caltopo.app.FlightStorage.snapshot(context)
    MapStorageSnapshot(cache, local, MapCacheSettings.maxCacheBytes(context), MapCacheSettings.maxTileAgeDays(context),
        context.filesDir.usableSpace, queryAvailableCacheBytes(context), location, flight.used,
        org.ncssar.rid2caltopo.app.FlightStorage.maximumBytes(context))
}

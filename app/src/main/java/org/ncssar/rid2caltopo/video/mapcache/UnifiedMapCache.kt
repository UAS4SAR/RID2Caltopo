package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.io.File
import java.io.IOException

internal data class MapCacheUsage(val imagery: Long, val terrain: Long, val decoded: Long, val other: Long, val reserved: Long) {
    val total: Long get() = imagery + terrain + decoded + other
}

/** One disk-content budget, including in-flight writes, for every map cache namespace. */
internal object UnifiedMapCache {
    val lock = Any()
    private data class Store(val namespace: String, val cache: BlobCacheStore)
    private data class Entry(val bytes: Long, val terrain: Boolean, val name: String, val modified: Long, val delete: () -> Boolean)
    private val stores = java.util.concurrent.ConcurrentHashMap<String, Store>()
    private val files = linkedMapOf<String, Entry>()
    private val protectedTerrain = mutableSetOf<String>()
    private val touched = mutableMapOf<String, Long>()
    private var scannedRoot: String? = null
    private var initialized = false
    private var reserved = 0L
    @Volatile var generation = 0L; private set
    @Volatile var blocked = false; private set

    fun register(id: String, namespace: String, store: BlobCacheStore) {
        stores[id] = Store(namespace, store)
    }

    private fun initialize(context: Context) {
        if (!initialized) {
            initialized = true
            try {
                BlobCacheStoreFactory.create(context, "tile_cache_v1", MapCachePolicy.TILE_CACHE_DB,
                    MapCacheSettings.maxCacheBytes(context), MapCachePolicy.TILE_TTL_MS).prewarm()
                BlobCacheStoreFactory.create(context, "icon_cache_v1", MapCachePolicy.ICON_CACHE_DB,
                    MapCachePolicy.ICON_CACHE_MAX_BYTES, MapCachePolicy.ICON_TTL_MS).prewarm()
                BlobCacheStoreFactory.create(context, "dem_point_v2", "dem_point_v2.db",
                    50L * 1024 * 1024, 365L * 86400000).prewarm()
            } catch (e: Exception) { initialized = false; throw e }
        }
        val root = CaltopoClient.GetArchiveUri()?.toString() ?: "internal"
        if (scannedRoot != root) {
            files.clear()
            val cache = CaltopoClient.GetArchiveDir()?.findFile("cache")
            listOfNotNull(cache, cache?.findFile("dem")).forEach { dir ->
                dir.listFiles().filter { it.isFile && (it.name.orEmpty().endsWith(".tif", true) || it.name.orEmpty().endsWith(".tiff", true) || it.name.orEmpty().endsWith(".part") || it.name.orEmpty().endsWith(".cog")) }
                    .forEach { remember(it) }
            }
            context.noBackupFilesDir.resolve("dem_blocks_v2").walkTopDown().filter { it.isFile }.forEach { remember(it) }
            when (val selected = MapCacheRootResolver.resolveRoot(context)) {
                is MapCacheRoot.FileBacked -> selected.dir.resolve("surface_v1").walkTopDown().filter { it.isFile }.forEach { remember(it) }
                is MapCacheRoot.SafBacked -> {
                    fun scan(dir: DocumentFile) {
                        dir.listFiles().forEach { if (it.isDirectory) scan(it) else rememberSurface(it) }
                    }
                    selected.dir.findFile("surface_v1")?.let(::scan)
                }
            }
            scannedRoot = root
        }
    }

    fun usage(context: Context): MapCacheUsage = synchronized(lock) {
        initialize(context)
        MapCacheUsage(
            stores.values.filter { it.namespace.startsWith("tile_") }.sumOf { it.cache.snapshot().bytesUsed },
            files.values.filter { it.terrain }.sumOf { it.bytes },
            files.values.filter { !it.terrain }.sumOf { it.bytes },
            stores.values.filter { !it.namespace.startsWith("tile_") }.sumOf { it.cache.snapshot().bytesUsed }, reserved
        )
    }

    fun touchTerrain(name: String) = synchronized(lock) { touched[name] = System.currentTimeMillis() }

    fun protectTerrain(names: Collection<String>) = synchronized(lock) {
        protectedTerrain.clear(); protectedTerrain.addAll(names)
    }

    private fun trim(context: Context, target: Long) {
        var used = usage(context).total
        if (used <= target) return
        // Merge the oldest entries across every store; keep small batches to avoid full DB scans.
        val queues = stores.values.associateWith { ArrayDeque(it.cache.oldestEntries()) }.toMutableMap()
        val eligible = files.entries.filter { (path, entry) ->
            !path.contains("/surface_v1/") && !path.contains("surface_v1%2F", true) && !entry.name.startsWith("surface_v1/") && !entry.name.endsWith(".aol") && (!entry.terrain || (entry.name !in protectedTerrain && (touched[entry.name] ?: 0) < System.currentTimeMillis() - 60_000))
        }.sortedBy { maxOf(it.value.modified, touched[it.value.name] ?: 0) }.toMutableList()
        while (used > target) {
            val store = queues.filterValues { it.isNotEmpty() }.minByOrNull { it.value.first().accessed }?.key
            val blob = store?.let { queues.getValue(it).first() }
            val file = eligible.firstOrNull()
            if (blob == null && file == null) break
            if (file != null && (blob == null || maxOf(file.value.modified, touched[file.value.name] ?: 0) <= blob.accessed)) {
                eligible.removeAt(0)
                if (file.value.delete()) { files.remove(file.key); used -= file.value.bytes; if (file.value.terrain) generation++ }
            } else if (store != null && blob != null) {
                val queue = queues.getValue(store)
                queue.removeFirst()
                if (store.cache.remove(blob.key)) used -= blob.bytes
                if (queue.isEmpty()) queue.addAll(store.cache.oldestEntries())
                // A storage failure must not spin forever on an undeletable oldest entry.
                if (queue.firstOrNull()?.key == blob.key) queues.remove(store)
            }
        }
    }

    fun maintain(context: Context) = synchronized(lock) {
        initialize(context)
        val cutoff = System.currentTimeMillis() - MapCachePolicy.tileCacheMaxAgeMs(context)
        stores.values.filter { it.namespace.startsWith("tile_") }.forEach {
            it.cache.runMaintenance(cutoff, it.cache.snapshot().bytesUsed)
        }
        trim(context, (MapCacheSettings.maxCacheBytes(context) - reserved).coerceAtLeast(0))
    }

    fun reserveWithoutEviction(context: Context, bytes: Long): Reservation = synchronized(lock) {
        initialize(context)
        if (!MapCacheBudgetPolicy.fits(usage(context).total, reserved, bytes, MapCacheSettings.maxCacheBytes(context))) {
            throw IOException("Not enough spare map-cache space for AOL preparation; select a smaller region or increase Cache Size")
        }
        reserve(context,bytes)
    }

    fun reserve(context: Context, bytes: Long): Reservation = synchronized(lock) {
        initialize(context)
        val amount = bytes.coerceAtLeast(0)
        val limit = MapCacheSettings.maxCacheBytes(context)
        if (amount > limit - reserved) {
            blocked = true; throw IOException("Map cache limit is too small; increase Cache Size")
        }
        trim(context, limit - reserved - amount)
        if (!MapCacheBudgetPolicy.fits(usage(context).total, reserved, amount, limit)) {
            blocked = true; throw IOException("Map cache is full; increase Cache Size")
        }
        reserved += amount
        blocked = false
        Reservation(amount)
    }

    class Reservation internal constructor(private val bytes: Long) : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(lock) { if (!closed) { reserved -= bytes; closed = true } }
    }

    fun remember(file: File) = synchronized(lock) {
        files[file.absolutePath] = Entry(file.length(), false, file.name, file.lastModified(), { !file.exists() || file.delete() })
    }
    fun remember(file: DocumentFile) = synchronized(lock) {
        files[file.uri.toString()] = Entry(file.length(), true, file.name.orEmpty(), file.lastModified(), { !file.exists() || file.delete() })
    }
    fun rememberSurface(file: DocumentFile) = synchronized(lock) {
        files[file.uri.toString()] = Entry(file.length(), false, "surface_v1/" + file.name.orEmpty(), file.lastModified(), { !file.exists() || file.delete() })
    }
    fun forget(file: DocumentFile) = synchronized(lock) { files.remove(file.uri.toString()); Unit }
}

internal object MapCacheBudgetPolicy {
    fun defaultLimit(free: Long): Long = minOf(10_000_000_000L, free.coerceAtLeast(0) / 5 * 4)
    fun fits(used: Long, reserved: Long, incoming: Long, limit: Long): Boolean =
        used >= 0 && reserved >= 0 && incoming >= 0 && used <= limit &&
            reserved <= limit - used && incoming <= limit - used - reserved
}

internal class BudgetedMapStore(private val context: Context, private val delegate: BlobCacheStore) : BlobCacheStore by delegate {
    override fun put(cacheKey: String, bytes: ByteArray, expiresAtMs: Long) = synchronized(UnifiedMapCache.lock) {
        UnifiedMapCache.reserve(context, bytes.size.toLong()).use {
            delegate.put(cacheKey, bytes, expiresAtMs)
        }
    }
}

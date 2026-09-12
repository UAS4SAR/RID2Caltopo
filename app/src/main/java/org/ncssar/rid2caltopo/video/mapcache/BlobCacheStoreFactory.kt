package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn

internal object BlobCacheStoreFactory {
    private val instances = mutableMapOf<String, BlobCacheStore>()
    fun create(
        context: Context, namespace: String, dbName: String, maxBytes: Long,
        defaultTtlMs: Long, forceFileBacked: Boolean = false
    ): BlobCacheStore = synchronized(UnifiedMapCache.lock) {
        val id = "${MapCacheRootResolver.resolveRoot(context)}:$namespace:$dbName:$forceFileBacked"
        instances.getOrPut(id) {
            val raw = createRaw(context, namespace, dbName, maxBytes, defaultTtlMs, forceFileBacked)
            UnifiedMapCache.register(id, namespace, raw)
            BudgetedMapStore(context.applicationContext, raw)
        }
    }

    private fun createRaw(
        context: Context,
        namespace: String,
        dbName: String,
        maxBytes: Long,
        defaultTtlMs: Long,
        forceFileBacked: Boolean = false
    ): BlobCacheStore {
        val internalFallbackRoot = context.applicationContext.noBackupFilesDir.resolve("map_cache")
        if (!internalFallbackRoot.exists()) {
            internalFallbackRoot.mkdirs()
        }
        MapCacheDebug.log(
            "store create namespace=$namespace db=$dbName maxBytes=$maxBytes ttlMs=$defaultTtlMs forceFile=$forceFileBacked"
        )
        if (forceFileBacked) {
            MapCacheDebug.log("store backend=file(forced) root=${internalFallbackRoot.absolutePath}")
            return BlobSqlDiskCache(
                cacheDir = internalFallbackRoot,
                dbName = dbName,
                maxBytes = maxBytes,
                defaultTtlMs = defaultTtlMs
            )
        }

        return when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> try {
                MapCacheDebug.log("store backend=file root=${root.dir.absolutePath}")
                BlobSqlDiskCache(
                    cacheDir = root.dir,
                    dbName = dbName,
                    maxBytes = maxBytes,
                    defaultTtlMs = defaultTtlMs
                )
            } catch (t: Throwable) {
                CTWarn(
                    "SplitMapPane",
                    "Map cache SQL open failed for ${root.dir.absolutePath}; using internal fallback.",
                    Exception(t)
                )
                MapCacheDebug.log(
                    "store backend=file-open-failed root=${root.dir.absolutePath} err=${t.javaClass.simpleName}:${t.message}"
                )
                MapCacheDebug.log("store backend=file-fallback root=${internalFallbackRoot.absolutePath}")
                BlobSqlDiskCache(
                    cacheDir = internalFallbackRoot,
                    dbName = dbName,
                    maxBytes = maxBytes,
                    defaultTtlMs = defaultTtlMs
                )
            }

            is MapCacheRoot.SafBacked -> {
                MapCacheDebug.log("store backend=saf root=${root.dir.uri}")
                SafBlobCacheStore(
                    context = context.applicationContext,
                    rootDir = root.dir,
                    namespace = namespace,
                    maxBytes = maxBytes,
                    defaultTtlMs = defaultTtlMs
                )
            }
        }
    }
}

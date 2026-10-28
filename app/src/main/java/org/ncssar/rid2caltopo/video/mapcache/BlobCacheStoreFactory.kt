package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn

internal object BlobCacheStoreFactory {
    private val instances = java.util.concurrent.ConcurrentHashMap<String, BlobCacheStore>()
    fun create(
        context: Context, namespace: String, dbName: String, maxBytes: Long,
        defaultTtlMs: Long, forceFileBacked: Boolean = false
    ): BlobCacheStore {
        val id = "${MapCacheRootResolver.resolveRoot(context)}:$namespace:$dbName"
        // Opening a reader must not wait for the budget's full SD-card scan.
        return instances.computeIfAbsent(id) {
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
        // The file backend is only for an explicitly temporary archive/session.
        // A document-provider failure must never create an untracked persistent store.
        return when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> BlobSqlDiskCache(
                cacheDir = root.dir, dbName = dbName, maxBytes = maxBytes, defaultTtlMs = defaultTtlMs
            )

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

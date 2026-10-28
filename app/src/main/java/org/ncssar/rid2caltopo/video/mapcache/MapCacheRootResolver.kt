package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.net.Uri
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import java.io.File

internal object MapCacheRootResolver {
    private const val TAG = "SplitMapPane"
    @Volatile
    private var cached: CachedRoot? = null

    private data class CachedRoot(
        val archiveUri: String?,
        val root: MapCacheRoot
    )

    fun resolveRoot(context: Context): MapCacheRoot {
        val appContext = context.applicationContext
        val archiveUri = CaltopoClient.GetArchiveUri()?.toString()
        MapCacheDebug.log("resolveRoot archiveUri=${archiveUri ?: "<none>"}")
        cached?.let { entry ->
            if (entry.archiveUri == archiveUri) {
                MapCacheDebug.log("resolveRoot cache-hit root=${entry.root}")
                return entry.root
            }
        }

        synchronized(this) {
            cached?.let { entry ->
                if (entry.archiveUri == archiveUri) {
                    return entry.root
                }
            }

            val resolved = resolveRootUncached(appContext)
            cached = CachedRoot(archiveUri = archiveUri, root = resolved)
            MapCacheDebug.log("resolveRoot resolved root=$resolved")
            return resolved
        }
    }

    fun invalidate() {
        synchronized(this) {
            cached = null
        }
    }

    private fun resolveRootUncached(context: Context): MapCacheRoot {
        val archiveDir = CaltopoClient.GetArchiveDir()
        if (archiveDir != null && archiveDir.isDirectory) {
            if (CaltopoClient.GetArchiveUri() != null) org.ncssar.rid2caltopo.storage.OperationalStorage.requireReady(context)
            val archiveCacheDir = archiveDir.findFile("cache") ?: archiveDir.createDirectory("cache")
            if (archiveCacheDir != null && archiveCacheDir.isDirectory) {
                val uri = archiveCacheDir.uri
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    val root = uri.path?.let { File(it) }
                    if (root != null && (root.exists() || root.mkdirs())) {
                        CTDebug(TAG, "Map cache root using archive file dir: ${root.absolutePath}")
                        MapCacheDebug.log("root archive-file path=${root.absolutePath}")
                        return MapCacheRoot.FileBacked(root)
                    }
                }
                CTDebug(TAG, "Map cache root using archive SAF dir: $uri")
                MapCacheDebug.log("root archive-saf uri=$uri")
                return MapCacheRoot.SafBacked(archiveCacheDir)
            }
        }

        val fallback = org.ncssar.rid2caltopo.storage.TemporaryStorage.directory(context, "map-cache")
        if (!fallback.exists()) {
            fallback.mkdirs()
        }
        CTDebug(TAG, "Map cache root using app cache fallback: ${fallback.absolutePath}")
        MapCacheDebug.log("root app-cache-fallback path=${fallback.absolutePath}")
        return MapCacheRoot.FileBacked(fallback)
    }
}

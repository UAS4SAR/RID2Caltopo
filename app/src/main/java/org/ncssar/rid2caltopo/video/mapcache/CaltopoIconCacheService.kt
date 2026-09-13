package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

class CaltopoIconCacheService(context: Context) {
    // Construction occurs on the UI thread; opening the store may wait for cache maintenance.
    private val diskCache: BlobCacheStore by lazy {
        BlobCacheStoreFactory.create(
            context = context.applicationContext,
            namespace = "icon_cache_v${MapCachePolicy.ICON_CACHE_VERSION}",
            dbName = MapCachePolicy.ICON_CACHE_DB,
            maxBytes = MapCachePolicy.ICON_CACHE_MAX_BYTES,
            defaultTtlMs = MapCachePolicy.ICON_TTL_MS
        )
    }
    private val memoryCache = object : LinkedHashMap<String, Drawable>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable>?): Boolean {
            return size > 256
        }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun loadBestAvailableDrawable(
        resources: android.content.res.Resources,
        markerSymbol: String,
        markerColor: String?
    ): Drawable? = withContext(Dispatchers.IO) {
        val key = cacheKey(markerSymbol, markerColor)
        synchronized(memoryCache) {
            memoryCache[key]?.let {
                MapCacheDebug.log("icon mem-hit key=$key")
                return@withContext cloneDrawable(resources, it)
            }
        }

        val cached = diskCache.get(key)
        val staleDrawable = cached?.let { bytesToDrawable(resources, it.bytes) }
        if (cached != null && !cached.stale && staleDrawable != null) {
            synchronized(memoryCache) { memoryCache[key] = staleDrawable }
            MapCacheDebug.log("icon disk-hit key=$key bytes=${cached.bytes.size}")
            return@withContext cloneDrawable(resources, staleDrawable)
        }
        if (cached == null) {
            MapCacheDebug.log("icon disk-miss key=$key")
        } else if (cached.stale) {
            MapCacheDebug.log("icon disk-stale key=$key bytes=${cached.bytes.size}")
        }

        val url = iconUrl(markerSymbol, markerColor)
        val networkDrawable = fetchDrawable(resources, url)
        if (networkDrawable != null) {
            val bytes = drawableToPngBytes(networkDrawable)
            if (bytes != null) {
                diskCache.put(key, bytes, diskCache.defaultExpiry())
                MapCacheDebug.log("icon network-fetch key=$key bytes=${bytes.size}")
            } else {
                MapCacheDebug.log("icon network-fetch key=$key bytes=unavailable")
            }
            synchronized(memoryCache) { memoryCache[key] = networkDrawable }
            return@withContext cloneDrawable(resources, networkDrawable)
        }
        MapCacheDebug.log("icon network-fail key=$key")

        if (staleDrawable != null) {
            synchronized(memoryCache) { memoryCache[key] = staleDrawable }
            diskCache.markStaleServed()
            CTDebug("SplitMapPane", "Icon cache serving stale icon for key=$key")
            MapCacheDebug.log("icon stale-fallback key=$key")
            return@withContext cloneDrawable(resources, staleDrawable)
        }

        null
    }

    fun statsSnapshot(): CacheStatsSnapshot = diskCache.snapshot()

    fun prewarm() {
        diskCache.prewarm()
    }

    fun clear() {
        synchronized(memoryCache) {
            memoryCache.clear()
        }
        diskCache.clear()
        CTDebug("SplitMapPane", "Icon cache cleared")
    }

    fun cacheKey(symbol: String, colorHex: String?): String {
        val normalizedSymbol = symbol.ifBlank { "point" }
        val normalizedColor = colorHex?.trim().orEmpty()
        return "v${MapCachePolicy.ICON_CACHE_VERSION}|$normalizedSymbol|$normalizedColor"
    }

    private fun iconUrl(symbol: String, colorHex: String?): String {
        val normalizedSymbol = symbol.ifBlank { "point" }
        val color = colorHex
            ?.trim()
            ?.removePrefix("#")
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        val cfg = if (color != null) "$normalizedSymbol,$color" else normalizedSymbol
        val encodedCfg = URLEncoder.encode(cfg, StandardCharsets.UTF_8.toString())
        return "https://caltopo.com/icon@2x.png?cfg=$encodedCfg"
    }

    private fun fetchDrawable(
        resources: android.content.res.Resources,
        url: String
    ): Drawable? {
        return try {
            val request = Request.Builder()
                .url(url.toUri().toString())
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val bytes = body.bytes()
                bytesToDrawable(resources, bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bytesToDrawable(
        resources: android.content.res.Resources,
        bytes: ByteArray
    ): Drawable? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return BitmapDrawable(resources, bitmap)
    }

    private fun drawableToPngBytes(drawable: Drawable): ByteArray? {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return null
        val out = java.io.ByteArrayOutputStream()
        return try {
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) return null
            out.toByteArray()
        } finally {
            out.close()
        }
    }

    private fun cloneDrawable(
        resources: android.content.res.Resources,
        drawable: Drawable
    ): Drawable {
        return drawable.constantState?.newDrawable(resources)?.mutate() ?: drawable
    }
}

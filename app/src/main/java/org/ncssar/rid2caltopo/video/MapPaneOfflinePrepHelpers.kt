package org.ncssar.rid2caltopo.video

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.video.mapcache.BadTilePolicy
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import org.ncssar.rid2caltopo.video.mapcache.MapCacheDebug
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRoot
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRootResolver
import org.ncssar.rid2caltopo.video.mapcache.MapCacheSettings
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil

internal enum class DemResolutionOption(val meters: Int, val label: String, val explanation: String) {
    MAXIMUM_1M(1, "USGS S1M (1 m)", "Default; downloads available USGS seamless 1 m tiles with 10 m fallback; may be very large."),
    STANDARD_30M(30, "Standard (30 m)", "Broad coverage and smallest download."),
    ENHANCED_10M(10, "Enhanced (10 m)", "About 9x as many terrain samples as 30 m.")
}

internal data class DemDownload(
    val url: String,
    val fileName: String,
    val expectedBytes: Long? = null
)

internal fun estimatedDemDownloadBytes(
    download: DemDownload,
    resolution: DemResolutionOption,
): Long = download.expectedBytes ?: when {
    download.fileName.startsWith("USGS_1_") -> 54_000_000L
    download.fileName.startsWith("USGS_13_") -> 486_000_000L
    resolution == DemResolutionOption.STANDARD_30M -> 54_000_000L
    resolution == DemResolutionOption.ENHANCED_10M -> 486_000_000L
    else -> 400_000_000L
}

internal fun weightedTransferredBytes(
    estimatedBytes: Long,
    transferredBytes: Long,
    expectedBytes: Long?,
): Long {
    val estimate = estimatedBytes.coerceAtLeast(0L)
    if (estimate == 0L || transferredBytes <= 0L) return 0L
    val expected = (expectedBytes ?: estimate).coerceAtLeast(1L)
    return (estimate.toDouble() *
        (transferredBytes.toDouble() / expected.toDouble()).coerceIn(0.0, 1.0))
        .toLong()
        .coerceIn(0L, estimate)
}

internal fun estimateDemDownloadCount(bounds: BoundingBox, resolution: DemResolutionOption): Int {
    if (resolution != DemResolutionOption.MAXIMUM_1M) return demTileNamesForBounds(bounds).size
    val centerLat = (bounds.latNorth + bounds.latSouth) / 2.0
    val widthMeters = kotlin.math.abs(bounds.lonEast - bounds.lonWest) * 111_320.0 *
        kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
    val heightMeters = kotlin.math.abs(bounds.latNorth - bounds.latSouth) * 111_320.0
    val oneMeterTiles = maxOf(1, kotlin.math.ceil(widthMeters / 10_000.0).toInt() * kotlin.math.ceil(heightMeters / 10_000.0).toInt())
    return demTileNamesForBounds(bounds).size + oneMeterTiles
}

internal fun conservativeDemBytes(count: Int, resolution: DemResolutionOption): Long {
    val perTile = when (resolution) {
        DemResolutionOption.STANDARD_30M -> 54_000_000L
        DemResolutionOption.ENHANCED_10M -> 486_000_000L
        // Maximum detail also includes 10 m coverage beneath any unavailable/NoData 1 m areas.
        DemResolutionOption.MAXIMUM_1M -> 486_000_000L
    }
    return count.toLong() * perTile
}

internal fun conservativeDemBytes(bounds: BoundingBox, resolution: DemResolutionOption): Long {
    if (resolution != DemResolutionOption.MAXIMUM_1M) {
        return conservativeDemBytes(estimateDemDownloadCount(bounds, resolution), resolution)
    }
    val fallbackCount = demTileNamesForBounds(bounds).size
    val oneMeterCount = (estimateDemDownloadCount(bounds, resolution) - fallbackCount).coerceAtLeast(1)
    return fallbackCount * 486_000_000L + oneMeterCount * 400_000_000L
}

internal fun resolveDemDownloads(
    bounds: BoundingBox,
    resolution: DemResolutionOption,
    client: OkHttpClient
): List<DemDownload> {
    if (resolution != DemResolutionOption.MAXIMUM_1M) {
        val product = if (resolution == DemResolutionOption.ENHANCED_10M) "13" else "1"
        return demTileNamesForBounds(bounds).map { name ->
            val fileName = "USGS_${product}_$name.tif"
            DemDownload(
                url = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/$product/TIFF/current/$name/$fileName",
                fileName = fileName
            )
        }
    }
    val out = linkedMapOf<String, DemDownload>()
    // Keep complete 10 m coverage below S1M tiles so gaps and NoData areas
    // remain useful offline. The sampler will choose the finest valid overlapping value.
    demTileNamesForBounds(bounds).forEach { name ->
        val fileName = "USGS_13_$name.tif"
        val url = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/current/$name/$fileName"
        out[url] = DemDownload(url, fileName)
    }
    var offset = 0
    do {
        val url = HttpUrl.Builder()
            .scheme("https").host("tnmaccess.nationalmap.gov")
            .addPathSegments("api/v1/products")
            .addQueryParameter("bbox", "${bounds.lonWest},${bounds.latSouth},${bounds.lonEast},${bounds.latNorth}")
            .addQueryParameter("prodFormats", "GeoTIFF")
            .addQueryParameter("outputFormat", "JSON")
            .addQueryParameter("datasets", "Seamless 1-m DEM (S1M)")
            .addQueryParameter("max", "100").addQueryParameter("offset", offset.toString())
            .build()
        val page = fetchTnmCatalogPage(client, Request.Builder().url(url).build())
        val items = page.optJSONArray("items") ?: break
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val downloadUrl = item.optString("downloadURL").takeIf { it.startsWith("https://") } ?: continue
            val originalName = downloadUrl.substringAfterLast('/').takeIf { it.endsWith(".tif", ignoreCase = true) } ?: continue
            val boundingBox = item.optJSONObject("boundingBox")
            val fileName = if (boundingBox != null) {
                val minLat = kotlin.math.round(boundingBox.optDouble("minY") * 100_000.0).toLong()
                val maxLat = kotlin.math.round(boundingBox.optDouble("maxY") * 100_000.0).toLong()
                val minLon = kotlin.math.round(boundingBox.optDouble("minX") * 100_000.0).toLong()
                val maxLon = kotlin.math.round(boundingBox.optDouble("maxX") * 100_000.0).toLong()
                "R2C_S1M_${minLat}_${maxLat}_${minLon}_${maxLon}_$originalName"
            } else originalName
            val expected = item.optLong("sizeInBytes", -1L).takeIf { it > 0L }
            out[downloadUrl] = DemDownload(downloadUrl, fileName, expected)
        }
        offset += items.length()
        val total = page.optInt("total", offset)
        if (items.length() == 0 || offset >= total) break
    } while (offset < 2_000)
    return out.values.sortedBy { it.fileName }
}

internal fun fetchTnmCatalogPage(
    client: OkHttpClient,
    request: Request,
    maxAttempts: Int = 4,
    sleeper: (Long) -> Unit = Thread::sleep
): JSONObject {
    require(maxAttempts > 0)
    var lastFailure: Exception? = null
    repeat(maxAttempts) { attempt ->
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    return JSONObject(response.body?.string() ?: error("TNM catalog response was empty"))
                }
                val failure = IllegalStateException("TNM catalog HTTP ${response.code}")
                lastFailure = failure
                val transient = response.code == 408 || response.code == 429 || response.code in 500..599
                if (!transient || attempt == maxAttempts - 1) throw failure
                val retryAfterMs = response.header("Retry-After")
                    ?.toLongOrNull()
                    ?.coerceIn(0L, 30L)
                    ?.times(1_000L)
                sleeper(retryAfterMs ?: (1_000L shl attempt).coerceAtMost(8_000L))
            }
        } catch (e: IOException) {
            lastFailure = e
            if (attempt == maxAttempts - 1) throw e
            sleeper((1_000L shl attempt).coerceAtMost(8_000L))
        }
    }
    throw lastFailure ?: IllegalStateException("TNM catalog request failed")
}

private fun estimateDemSamplesForBounds(
    bounds: BoundingBox,
    stepMeters: Double,
    clipBoundary: GeoBoundary? = null
): Int {
    if (stepMeters <= 0.0) return 0
    val north = bounds.latNorth
    val south = bounds.latSouth
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    val centerLat = (loLat + hiLat) / 2.0
    val latStepDeg = stepMeters / 111_320.0
    val lonMetersAtLat = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
    val lonStepDeg = stepMeters / lonMetersAtLat
    var total = 0
    var lat = loLat
    while (lat <= hiLat) {
        var lon = loLon
        while (lon <= hiLon) {
            if (clipBoundary == null || pointInPolygon(lat, lon, clipBoundary.ring)) {
                total++
            }
            lon += lonStepDeg
        }
        lat += latStepDeg
    }
    return total
}

internal fun estimateDemSamplesApproximate(
    bounds: BoundingBox,
    stepMeters: Double,
    clipBoundary: GeoBoundary? = null
): Int {
    if (stepMeters <= 0.0) return 0
    val effectiveArea = if (clipBoundary != null) {
        polygonAreaMeters2(clipBoundary.ring).coerceAtLeast(0.0)
    } else {
        boundsAreaMeters2(bounds).coerceAtLeast(0.0)
    }
    if (effectiveArea <= 0.0) return 0
    val sampleArea = stepMeters * stepMeters
    return kotlin.math.max(1, kotlin.math.ceil(effectiveArea / sampleArea).toInt())
}

private suspend fun forEachDemSamplePointForBounds(
    bounds: BoundingBox,
    stepMeters: Double,
    clipBoundary: GeoBoundary? = null,
    block: suspend (Double, Double) -> Unit
) {
    if (stepMeters <= 0.0) return
    val north = bounds.latNorth
    val south = bounds.latSouth
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    val centerLat = (loLat + hiLat) / 2.0
    val latStepDeg = stepMeters / 111_320.0
    val lonMetersAtLat = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
    val lonStepDeg = stepMeters / lonMetersAtLat
    var lat = loLat
    while (lat <= hiLat) {
        var lon = loLon
        while (lon <= hiLon) {
            if (clipBoundary == null || pointInPolygon(lat, lon, clipBoundary.ring)) {
                block(lat, lon)
            }
            lon += lonStepDeg
        }
        lat += latStepDeg
    }
}

/** Returns the USGS 3DEP 1 degree tile name (e.g. "n40w122") that covers the given coordinate. */
internal fun tileNameForLocation(lat: Double, lng: Double): String {
    val tileNorth = kotlin.math.floor(lat).toInt() + 1
    val tileLonBlock = kotlin.math.floor(lng).toInt()
    val latPart = if (tileNorth >= 0) "n%02d".format(tileNorth) else "s%02d".format(-tileNorth)
    val lonPart = if (tileLonBlock < 0) "w%03d".format(-tileLonBlock) else "e%03d".format(tileLonBlock + 1)
    return "$latPart$lonPart"
}

internal fun demPrefetchCellKey(lat: Double, lng: Double): String =
    "${kotlin.math.floor(lat * 20.0).toInt()}:${kotlin.math.floor(lng * 20.0).toInt()}"

internal suspend fun autoDownloadDemTile(
    tileName: String,
    context: Context,
    client: OkHttpClient,
    service: DemElevationService
) {
    val archiveRoot = CaltopoClient.GetArchiveDir() ?: run {
        MapCacheDebug.log("auto-dem: no archive dir; skipping fallback")
        return
    }
    val cacheDir = archiveRoot.findFile("cache") ?: archiveRoot.createDirectory("cache") ?: return
    val demDir = cacheDir.findFile("dem") ?: cacheDir.createDirectory("dem") ?: return
    val fileName = "USGS_1_$tileName.tif"
    val existing = demDir.findFile(fileName)
    if (existing != null && existing.isFile && existing.length() > 5_000_000L) {
        MapCacheDebug.log("auto-dem: fallback already present bytes=${existing.length()}")
        service.refreshGeoTiffCatalog()
        return
    }
    val url = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/1/TIFF/current/$tileName/USGS_1_$tileName.tif"
    CTDebug(MAP_PANE_TAG, "auto-dem: downloading fallback")
    try {
        val ok = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                CTError(MAP_PANE_TAG, "auto-dem fallback http-fail code=${resp.code}")
                return@use false
            }
            val body = resp.body ?: run { CTError(MAP_PANE_TAG, "auto-dem fallback no-body"); return@use false }
            val destFile = demDir.findFile(fileName) ?: demDir.createFile("image/tiff", fileName)
                ?: run { CTError(MAP_PANE_TAG, "auto-dem fallback create-failed"); return@use false }
            context.contentResolver.openOutputStream(destFile.uri, "wt")?.use { out ->
                body.byteStream().copyTo(out)
            } ?: run { CTError(MAP_PANE_TAG, "auto-dem fallback stream-open-failed"); return@use false }
            true
        }
        if (ok) {
            CTDebug(MAP_PANE_TAG, "auto-dem: fallback complete")
            service.refreshGeoTiffCatalog()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CTError(MAP_PANE_TAG, "auto-dem fallback ex=${e.javaClass.simpleName}")
    }
}

/** Resolves the single S1M tile covering a point without requesting a broad-area catalog. */
internal fun resolveS1mDownloadForLocation(
    lat: Double,
    lng: Double,
    client: OkHttpClient,
): DemDownload? {
    val delta = 0.0001
    val url = HttpUrl.Builder()
        .scheme("https").host("tnmaccess.nationalmap.gov")
        .addPathSegments("api/v1/products")
        .addQueryParameter("bbox", "${lng - delta},${lat - delta},${lng + delta},${lat + delta}")
        .addQueryParameter("prodFormats", "GeoTIFF")
        .addQueryParameter("outputFormat", "JSON")
        .addQueryParameter("datasets", "Seamless 1-m DEM (S1M)")
        .addQueryParameter("max", "20")
        .build()
    val page = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return null
        JSONObject(response.body?.string() ?: return null)
    }
    return parseS1mDownloadForLocation(page, lat, lng)
}

internal fun parseS1mDownloadForLocation(page: JSONObject, lat: Double, lng: Double): DemDownload? {
    val items = page.optJSONArray("items") ?: return null
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val box = item.optJSONObject("boundingBox") ?: continue
        val minLat = box.optDouble("minY")
        val maxLat = box.optDouble("maxY")
        val minLon = box.optDouble("minX")
        val maxLon = box.optDouble("maxX")
        if (lat < minLat || lat > maxLat || lng < minLon || lng > maxLon) continue
        val downloadUrl = item.optString("downloadURL").takeIf { it.startsWith("https://") } ?: continue
        val originalName = downloadUrl.substringAfterLast('/').takeIf { it.endsWith(".tif", ignoreCase = true) } ?: continue
        val fileName = "R2C_S1M_${kotlin.math.round(minLat * 100_000.0).toLong()}_" +
            "${kotlin.math.round(maxLat * 100_000.0).toLong()}_${kotlin.math.round(minLon * 100_000.0).toLong()}_" +
            "${kotlin.math.round(maxLon * 100_000.0).toLong()}_$originalName"
        return DemDownload(downloadUrl, fileName, item.optLong("sizeInBytes", -1L).takeIf { it > 0 })
    }
    return null
}

/** Prefers the point's S1M tile and retains the established 30 m tile as coverage fallback. */
internal suspend fun autoDownloadBestDemForLocation(
    lat: Double,
    lng: Double,
    context: Context,
    client: OkHttpClient,
    service: DemElevationService,
) {
    val s1m = try { resolveS1mDownloadForLocation(lat, lng, client) } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { null }
    if (s1m != null) {
        downloadAutomaticDem(s1m, context, client, service)
    }
    autoDownloadDemTile(tileNameForLocation(lat, lng), context, client, service)
}

private suspend fun downloadAutomaticDem(
    download: DemDownload,
    context: Context,
    client: OkHttpClient,
    service: DemElevationService,
) {
    val archiveRoot = CaltopoClient.GetArchiveDir() ?: return
    val cacheDir = archiveRoot.findFile("cache") ?: archiveRoot.createDirectory("cache") ?: return
    val demDir = cacheDir.findFile("dem") ?: cacheDir.createDirectory("dem") ?: return
    val existing = demDir.findFile(download.fileName)
    val minimumBytes = download.expectedBytes?.let { maxOf(100_000L, it * 95L / 100L) } ?: 5_000_000L
    if (existing != null && existing.isFile && existing.length() >= minimumBytes) {
        service.refreshGeoTiffCatalog()
        return
    }
    val requiredBytes = (download.expectedBytes ?: 400_000_000L) + 250_000_000L
    if (queryAvailableCacheBytes(context)?.let { it < requiredBytes } == true) return
    try {
        client.newCall(Request.Builder().url(download.url).build()).execute().use { response ->
            if (!response.isSuccessful) return
            val body = response.body ?: return
            val destination = existing ?: demDir.createFile("image/tiff", download.fileName) ?: return
            val copied = context.contentResolver.openOutputStream(destination.uri, "wt")?.use { output ->
                body.byteStream().copyTo(output)
            } ?: return
            if (copied < minimumBytes || destination.length() < minimumBytes) return
        }
        service.refreshGeoTiffCatalog()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The 30 m fallback below remains available; avoid logging location-bearing tile names.
    }
}

internal fun demTileNamesForBounds(bounds: BoundingBox): List<String> {
    val latMin = minOf(bounds.latNorth, bounds.latSouth)
    val latMax = maxOf(bounds.latNorth, bounds.latSouth)
    val lonMin = minOf(bounds.lonWest, bounds.lonEast)
    val lonMax = maxOf(bounds.lonWest, bounds.lonEast)
    val latSouthBlock = kotlin.math.floor(latMin).toInt()
    val latNorthBlock = kotlin.math.ceil(latMax).toInt() - 1
    val lonWestBlock = kotlin.math.floor(lonMin).toInt()
    val lonEastBlock = kotlin.math.ceil(lonMax).toInt() - 1
    val names = mutableListOf<String>()
    for (latBlock in latSouthBlock..latNorthBlock) {
        val tileNorth = latBlock + 1
        val latPart = if (tileNorth >= 0) "n%02d".format(tileNorth) else "s%02d".format(-tileNorth)
        for (lonBlock in lonWestBlock..lonEastBlock) {
            val lonPart = if (lonBlock < 0) "w%03d".format(-lonBlock) else "e%03d".format(lonBlock + 1)
            names += "$latPart$lonPart"
        }
    }
    return names
}

internal fun formatDurationShort(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val h = safe / 3600L
    val m = (safe % 3600L) / 60L
    val s = safe % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

internal data class OfflinePrepCapacity(
    val currentTileCacheBytes: Long,
    val estimatedTileBytes: Long,
    val estimatedDemBytes: Long,
    val maximumTileCacheBytes: Long,
    val availableVolumeBytes: Long?
) {
    val projectedTileCacheBytes: Long = saturatedAdd(currentTileCacheBytes, estimatedTileBytes)
    val estimatedDownloadBytes: Long = saturatedAdd(estimatedTileBytes, estimatedDemBytes)
    val exceedsCacheLimit: Boolean = projectedTileCacheBytes > maximumTileCacheBytes
    val exceedsAvailableVolume: Boolean = availableVolumeBytes?.let {
        estimatedDownloadBytes > it * 95L / 100L
    } ?: false
    val recommendedMaximumBytes: Long = recommendedCacheMaximumBytes(projectedTileCacheBytes)
}

internal fun recommendedCacheMaximumBytes(projectedBytes: Long): Long {
    val decimalGb = 1_000_000_000L
    val withHeadroom = saturatedAdd(projectedBytes, maxOf(100_000_000L, projectedBytes / 10L))
    return (ceil(withHeadroom.toDouble() / decimalGb.toDouble()) * decimalGb)
        .toLong()
        .coerceIn(100_000_000L, MapCacheSettings.MAX_CACHE_BYTES)
}

internal fun reasonableCacheMaximumBytes(
    currentCacheBytes: Long,
    availableVolumeBytes: Long?
): Long? = availableVolumeBytes?.let { available ->
    // Leave five percent of currently free space outside the cache allowance.
    saturatedAdd(currentCacheBytes, available.coerceAtLeast(0L) - available.coerceAtLeast(0L) / 20L)
}

internal fun formatStorageBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return if (safe >= 1_000_000_000L) {
        String.format(Locale.US, "%.1f GB", safe.toDouble() / 1_000_000_000.0)
    } else {
        String.format(Locale.US, "%.0f MB", safe.toDouble() / 1_000_000.0)
    }
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left.coerceAtLeast(0L) < right.coerceAtLeast(0L)) Long.MAX_VALUE
    else left.coerceAtLeast(0L) + right.coerceAtLeast(0L)

internal fun queryAvailableCacheBytes(context: Context): Long? {
    return try {
        when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> {
                val stat = StatFs(root.dir.absolutePath)
                stat.availableBytes
            }
            is MapCacheRoot.SafBacked -> {
                availableBytesForDocumentTree(context.applicationContext, root.dir.uri)
            }
        }
    } catch (_: Exception) {
        null
    }
}

private fun availableBytesForDocumentTree(context: Context, uri: android.net.Uri): Long? {
    val documentId = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (_: Exception) {
        return null
    }
    val volumeId = documentId.substringBefore(':')
    val storageManager = context.getSystemService(StorageManager::class.java)
    val volumeDirectory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        storageManager?.storageVolumes?.firstOrNull { volume ->
            if (volumeId.equals("primary", ignoreCase = true)) volume.isPrimary
            else volume.uuid.equals(volumeId, ignoreCase = true)
        }?.directory
    } else {
        null
    }
    val fallback = when {
        volumeId.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
        volumeId.isNotBlank() -> File("/storage/$volumeId")
        else -> null
    }
    val path = volumeDirectory ?: fallback ?: return null
    return try {
        StatFs(path.absolutePath).availableBytes
    } catch (_: Exception) {
        null
    }
}

internal fun mapCacheRootSignature(context: Context): String {
    return try {
        when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> "file:${root.dir.absolutePath}"
            is MapCacheRoot.SafBacked -> "saf:${root.dir.uri}"
        }
    } catch (_: Exception) {
        "unknown"
    }
}

internal fun exportBadTileHashes(context: Context): String? {
    return try {
        val hashes = BadTilePolicy.blockedHashesSorted(context)
        val header = "# RID2Caltopo bad tile hashes\n# count=${hashes.size}\n"
        val body = if (hashes.isEmpty()) "# (none)\n" else hashes.joinToString(separator = "\n", postfix = "\n")
        val payload = (header + body).toByteArray(Charsets.UTF_8)
        when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> {
                val out = File(root.dir, "bad_tile_hashes.txt")
                out.writeBytes(payload)
                out.absolutePath
            }
            is MapCacheRoot.SafBacked -> {
                val existing = root.dir.findFile("bad_tile_hashes.txt")
                val file = existing ?: root.dir.createFile("text/plain", "bad_tile_hashes.txt")
                if (file == null) return null
                context.applicationContext.contentResolver.openOutputStream(file.uri, "w")?.use { out ->
                    out.write(payload)
                    out.flush()
                } ?: return null
                file.uri.toString()
            }
        }
    } catch (e: Exception) {
        MapCacheDebug.log("bad-hash export failed err=${e.javaClass.simpleName}:${e.message}")
        null
    }
}

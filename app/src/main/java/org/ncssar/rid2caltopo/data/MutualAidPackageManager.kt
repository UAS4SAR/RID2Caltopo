package org.ncssar.rid2caltopo.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.video.mapcache.TileDiskCacheWriter
import org.ncssar.rid2caltopo.video.ArcGisWorldImageryTileSource
import org.ncssar.rid2caltopo.video.GeoBoundary
import org.ncssar.rid2caltopo.video.OsmStandardTileSource
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.floor

object MutualAidPackageManager {
    private const val FORMAT = "rid2caltopo_mutual_aid_package"
    private const val VERSION = 1
    private const val MANIFEST_PATH = "manifest.json"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class PackagePreview(
        val packageName: String,
        val sourceOrg: String,
        val displayName: String,
        val incident: String,
        val opPeriod: String,
        val targetMapId: String,
        val targetMapTitle: String,
        val expiresAtEpochMs: Long,
        val tileCount: Int,
        val demCount: Int,
        val aolCount: Int = 0
    )

    internal fun exportPackage(
        context: Context,
        destUri: Uri,
        packageName: String,
        displayName: String,
        incident: String,
        opPeriod: String,
        targetMapId: String,
        targetMapTitle: String,
        expiresAtEpochMs: Long,
        bounds: BoundingBox,
        minZoom: Int,
        maxZoom: Int,
        tileSource: ITileSource,
        includeDem: Boolean,
        clipBoundary: GeoBoundary? = null
    ): Pair<Boolean, String> {
        return try {
            val resolver = context.contentResolver
            resolver.openOutputStream(destUri, "w")?.use { rawOut ->
                writePackage(
                    context = context,
                    rawOut = rawOut,
                    packageName = packageName,
                    displayName = displayName,
                    incident = incident,
                    opPeriod = opPeriod,
                    targetMapId = targetMapId,
                    targetMapTitle = targetMapTitle,
                    expiresAtEpochMs = expiresAtEpochMs,
                    bounds = bounds,
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    tileSource = tileSource,
                    includeDem = includeDem,
                    clipBoundary = clipBoundary
                )
            } ?: return false to "Could not open destination for MA package export."

        } catch (e: Exception) {
            CaltopoClient.CTWarn("MutualAidPackageMgr", "exportPackage() failed.", e)
            false to (e.message ?: "Failed to save MA package.")
        }
    }

    internal fun exportPackageToTempFile(
        context: Context,
        packageName: String,
        displayName: String,
        incident: String,
        opPeriod: String,
        targetMapId: String,
        targetMapTitle: String,
        expiresAtEpochMs: Long,
        bounds: BoundingBox,
        minZoom: Int,
        maxZoom: Int,
        tileSource: ITileSource,
        includeDem: Boolean,
        clipBoundary: GeoBoundary? = null
    ): Pair<Boolean, File?> {
        return try {
            val tempDir = context.cacheDir.resolve("ma-transfer").apply { mkdirs() }
            val file = File(tempDir, "${sanitizePath(packageName)}_mutual_aid_package.zip")
            if (file.exists()) file.delete()
            file.outputStream().use { rawOut ->
                writePackage(
                    context = context,
                    rawOut = rawOut,
                    packageName = packageName,
                    displayName = displayName,
                    incident = incident,
                    opPeriod = opPeriod,
                    targetMapId = targetMapId,
                    targetMapTitle = targetMapTitle,
                    expiresAtEpochMs = expiresAtEpochMs,
                    bounds = bounds,
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    tileSource = tileSource,
                    includeDem = includeDem,
                    clipBoundary = clipBoundary
                )
            }
            true to file
        } catch (e: Exception) {
            CaltopoClient.CTWarn("MutualAidPackageMgr", "exportPackage() failed.", e)
            false to null
        }
    }

    fun importPackage(context: Context, srcUri: Uri): Pair<Boolean, String> {
        return try {
            val tileWriter = TileDiskCacheWriter(context)
            val archiveRoot = CaltopoClient.GetArchiveDir()
            val demDir = archiveRoot?.findFile("cache")?.findFile("dem")
                ?: archiveRoot?.findFile("cache")?.createDirectory("dem")
            val resolver = context.contentResolver
            val entryBytes = readPackageEntries(context, srcUri)
            val manifestBytes = entryBytes[MANIFEST_PATH] ?: return false to "Package is missing manifest.json."
            val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
            if (manifest.optString("format") != FORMAT) {
                return false to "Unexpected MA package format."
            }

            val profileEnc = manifest.optString("profile_enc")
            if (profileEnc.isNotBlank()) {
                val profileResult = MutualAidProfileManager.installEncryptedProfilePayload(profileEnc)
                if (!profileResult.first) return profileResult
            }

            val tileEntries = manifest.optJSONArray("tile_entries") ?: JSONArray()
            var importedTiles = 0
            for (i in 0 until tileEntries.length()) {
                val item = tileEntries.getJSONObject(i)
                val source = resolveTileSource(item.optString("source")) ?: continue
                val z = item.optInt("z")
                val x = item.optInt("x")
                val y = item.optInt("y")
                val path = item.optString("path")
                val bytes = entryBytes[path] ?: continue
                val expiresAt = item.optLong("expires_at_epoch_ms", 0L).takeIf { it > 0L }
                val idx = MapTileIndex.getTileIndex(z, x, y)
                if (tileWriter.importTileBytes(source, idx, bytes, expiresAt)) {
                    importedTiles++
                }
            }

            val demEntries = manifest.optJSONArray("dem_entries") ?: JSONArray()
            if (demEntries.length() > 0 && demDir == null) {
                return false to "DEM import requires a configured archive directory."
            }
            var importedDem = 0
            for (i in 0 until demEntries.length()) {
                val item = demEntries.getJSONObject(i)
                val fileName = item.optString("file_name")
                val path = item.optString("path")
                val bytes = entryBytes[path] ?: continue
                val target = demDir?.findFile(fileName) ?: demDir?.createFile("image/tiff", fileName) ?: continue
                resolver.openOutputStream(target.uri, "w")?.use { out ->
                    ByteArrayInputStream(bytes).copyTo(out)
                } ?: continue
                importedDem++
            }

            val importedAol=org.ncssar.rid2caltopo.video.surface.SurfaceStore.importSets(context,entryBytes)
            true to "Imported MA package: $importedAol AOL tile(s), $importedTiles tile(s), $importedDem DEM tile(s)."
        } catch (e: Exception) {
            CaltopoClient.CTWarn("MutualAidPackageMgr", "importPackage() failed.", e)
            false to (e.message ?: "Failed to import MA package.")
        }
    }

    fun importPackageAsync(
        context: Context,
        srcUri: Uri,
        callback: (Boolean, String) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = importPackage(appContext, srcUri)
            mainHandler.post {
                callback(result.first, result.second)
            }
        }
    }

    fun readPackagePreview(context: Context, srcUri: Uri): Pair<Boolean, PackagePreview?> {
        return try {
            val entryBytes = readPackageEntries(context, srcUri)
            val manifestBytes = entryBytes[MANIFEST_PATH] ?: return false to null
            val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
            if (manifest.optString("format") != FORMAT) {
                return false to null
            }
            val profileEnc = manifest.optString("profile_enc")
            val profileJson = if (profileEnc.isNotBlank()) {
                JSONObject(MutualAidToken.decryptPayload(profileEnc))
            } else {
                JSONObject()
            }
            true to PackagePreview(
                packageName = manifest.optString("package_name"),
                sourceOrg = profileJson.optString("source_label", manifest.optString("source_org")),
                displayName = profileJson.optString("display_name"),
                incident = profileJson.optString("incident"),
                opPeriod = profileJson.optString("op_period"),
                targetMapId = profileJson.optString("target_map_id"),
                targetMapTitle = profileJson.optString("target_map_title"),
                expiresAtEpochMs = profileJson.optLong("expires_at_epoch_ms", 0L),
                tileCount = manifest.optJSONArray("tile_entries")?.length() ?: 0,
                demCount = manifest.optJSONArray("dem_entries")?.length() ?: 0,
                aolCount = manifest.optJSONArray("aol_entries")?.let { entries -> (0 until entries.length()).count { entries.optString(it).endsWith(".aol") } } ?: 0
            )
        } catch (e: Exception) {
            CaltopoClient.CTWarn("MutualAidPackageMgr", "readPackagePreview() failed.", e)
            false to null
        }
    }

    private fun readPackageEntries(context: Context, srcUri: Uri): LinkedHashMap<String, ByteArray> {
        val entryBytes = LinkedHashMap<String, ByteArray>()
        context.contentResolver.openInputStream(srcUri)?.use { rawIn ->
            ZipInputStream(rawIn).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entryBytes[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalStateException("Could not open selected MA package.")
        return entryBytes
    }

    private fun resolveTileSource(sourceName: String): ITileSource? = when (sourceName) {
        OsmStandardTileSource.name() -> OsmStandardTileSource
        ArcGisWorldImageryTileSource.name() -> ArcGisWorldImageryTileSource
        else -> null
    }

    private fun sanitizePath(raw: String): String =
        raw.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]+"), "_").trim('_')

    private fun writePackage(
        context: Context,
        rawOut: OutputStream,
        packageName: String,
        displayName: String,
        incident: String,
        opPeriod: String,
        targetMapId: String,
        targetMapTitle: String,
        expiresAtEpochMs: Long,
        bounds: BoundingBox,
        minZoom: Int,
        maxZoom: Int,
        tileSource: ITileSource,
        includeDem: Boolean,
        clipBoundary: GeoBoundary?
    ): Pair<Boolean, String> {
        val resolver = context.contentResolver
        val tileWriter = TileDiskCacheWriter(context)
        val tileEntries = ArrayList<JSONObject>()
        val demEntries = ArrayList<JSONObject>()
        val archiveDir = CaltopoClient.GetArchiveDir()
        val demDir = archiveDir?.findFile("cache")?.findFile("dem")
        val profileEnc = MutualAidProfileManager.buildEncryptedProfilePayloadForCurrentIncident(
            displayName = displayName,
            incident = incident,
            opPeriod = opPeriod,
            targetMapId = targetMapId,
            targetMapTitle = targetMapTitle,
            expiresAtEpochMs = expiresAtEpochMs
        )
        if (profileEnc.isNullOrBlank()) {
            throw IllegalStateException("Configure the Mutual Aid account in Settings before exporting an MA package.")
        }
        var aolCount=0
        ZipOutputStream(rawOut).use { zip ->
            forEachTileIndexForBounds(bounds, minZoom, maxZoom, clipBoundary) { tileIndex ->
                val bytes = tileWriter.readTileBytes(tileSource, tileIndex) ?: return@forEachTileIndexForBounds
                val z = MapTileIndex.getZoom(tileIndex)
                val x = MapTileIndex.getX(tileIndex)
                val y = MapTileIndex.getY(tileIndex)
                val path = "tiles/${sanitizePath(tileSource.name())}/$z/$x/$y.bin"
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
                tileEntries += JSONObject()
                    .put("source", tileSource.name())
                    .put("z", z)
                    .put("x", x)
                    .put("y", y)
                    .put("expires_at_epoch_ms", tileWriter.getExpirationTimestamp(tileSource, tileIndex) ?: 0L)
                    .put("path", path)
            }

            if (includeDem) {
                val demTileNames = demTileNamesForBounds(bounds)
                for (tileName in demTileNames) {
                    val fileName = "USGS_1_$tileName.tif"
                    val demFile = demDir?.findFile(fileName) ?: continue
                    if (!demFile.isFile) continue
                    val path = "dem/$fileName"
                    resolver.openInputStream(demFile.uri)?.use { input ->
                        zip.putNextEntry(ZipEntry(path))
                        input.copyTo(zip)
                        zip.closeEntry()
                    } ?: continue
                    demEntries += JSONObject()
                        .put("tile_name", tileName)
                        .put("file_name", fileName)
                        .put("path", path)
                }
            }

            val aolFiles=org.ncssar.rid2caltopo.video.surface.SurfaceStore.exportSets(context,
                org.ncssar.rid2caltopo.video.surface.SurfaceBounds(bounds.lonWest,bounds.latSouth,bounds.lonEast,bounds.latNorth))
            aolCount=aolFiles.count { it.first.endsWith(".aol") }
            for((path,bytes) in aolFiles) { zip.putNextEntry(ZipEntry(path));zip.write(bytes);zip.closeEntry() }
            val manifest = JSONObject()
                .put("aol_entries", JSONArray(aolFiles.map { it.first }))
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("generated", CaltopoClient.TimeDatestampString(System.currentTimeMillis()))
                .put("package_name", packageName)
                .put("source_org", "")
                .put("profile_enc", profileEnc)
                .put("tile_entries", JSONArray(tileEntries))
                .put("dem_entries", JSONArray(demEntries))
            zip.putNextEntry(ZipEntry(MANIFEST_PATH))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return true to "Saved MA package with $aolCount AOL tile(s), ${tileEntries.size} tile(s) and ${demEntries.size} DEM tile(s)."
    }

    private fun forEachTileIndexForBounds(
        bounds: BoundingBox,
        minZoom: Int,
        maxZoom: Int,
        clipBoundary: GeoBoundary? = null,
        block: (Long) -> Unit
    ) {
        val north = bounds.latNorth.coerceIn(-85.05112878, 85.05112878)
        val south = bounds.latSouth.coerceIn(-85.05112878, 85.05112878)
        val west = bounds.lonWest
        val east = bounds.lonEast
        val loLat = minOf(north, south)
        val hiLat = maxOf(north, south)
        val loLon = minOf(west, east)
        val hiLon = maxOf(west, east)
        for (z in minZoom..maxZoom) {
            val maxTile = (1 shl z) - 1
            val minX = lonToTileX(loLon, z).coerceIn(0, maxTile)
            val maxX = lonToTileX(hiLon, z).coerceIn(0, maxTile)
            val minY = latToTileY(hiLat, z).coerceIn(0, maxTile)
            val maxY = latToTileY(loLat, z).coerceIn(0, maxTile)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    if (!tileIndexInsideBoundary(z, x, y, clipBoundary)) continue
                    block(MapTileIndex.getTileIndex(z, x, y))
                }
            }
        }
    }

    private fun demTileNamesForBounds(bounds: BoundingBox): List<String> {
        val latMin = minOf(bounds.latNorth, bounds.latSouth)
        val latMax = maxOf(bounds.latNorth, bounds.latSouth)
        val lonMin = minOf(bounds.lonWest, bounds.lonEast)
        val lonMax = maxOf(bounds.lonWest, bounds.lonEast)
        val latSouthBlock = floor(latMin).toInt()
        val latNorthBlock = kotlin.math.ceil(latMax).toInt() - 1
        val lonWestBlock = floor(lonMin).toInt()
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

    private fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * n.toDouble())
        return floor(x).toInt()
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        val n = 1 shl zoom
        val y = (1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n.toDouble()
        return floor(y).toInt()
    }

    private fun tileXToLon(x: Int, zoom: Int): Double {
        val n = 1 shl zoom
        return x.toDouble() / n.toDouble() * 360.0 - 180.0
    }

    private fun tileYToLat(y: Int, zoom: Int): Double {
        val n = 1 shl zoom
        val m = Math.PI * (1.0 - 2.0 * y.toDouble() / n.toDouble())
        return Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(m)))
    }

    private fun tileIndexInsideBoundary(
        zoom: Int,
        x: Int,
        y: Int,
        boundary: GeoBoundary?
    ): Boolean {
        if (boundary == null) return true
        val west = tileXToLon(x, zoom)
        val east = tileXToLon(x + 1, zoom)
        val north = tileYToLat(y, zoom)
        val south = tileYToLat(y + 1, zoom)
        val corners = arrayOf(
            north to west,
            north to east,
            south to west,
            south to east
        )
        return corners.any { (lat, lon) -> pointInPolygon(lat, lon, boundary.ring) }
    }

    private fun pointInPolygon(lat: Double, lon: Double, ring: List<GeoPoint>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i].latitude
            val xi = ring[i].longitude
            val yj = ring[j].latitude
            val xj = ring[j].longitude
            val intersects = ((yi > lat) != (yj > lat)) &&
                (lon < (xj - xi) * (lat - yi) / ((yj - yi).takeIf { kotlin.math.abs(it) > 1e-12 } ?: 1e-12) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }
}

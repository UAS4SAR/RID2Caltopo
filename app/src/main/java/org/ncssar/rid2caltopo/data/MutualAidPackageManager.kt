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
import org.ncssar.rid2caltopo.video.UsgsContoursTileSource
import org.ncssar.rid2caltopo.video.OsmStandardTileSource
import org.ncssar.rid2caltopo.mapping.MappingLayer
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
        val aolCount: Int = 0,
        val includesMapAccess: Boolean = true
    )

    internal fun exportPackageToTempFile(
        context: Context,
        packageName: String,
        bounds: BoundingBox,
        tileSources: List<ITileSource>,
        includeDem: Boolean,
        includeAol: Boolean,
        mappingLayers: List<MappingLayer> = emptyList(),
    ): Pair<Boolean, File?> {
        return try {
            val tempDir = org.ncssar.rid2caltopo.storage.TemporaryStorage.directory(context,"map-transfers")
            val file = File.createTempFile("${sanitizePath(packageName)}_map_package_", ".zip", tempDir)
            file.outputStream().use { rawOut ->
                writePackage(
                    context = context,
                    rawOut = rawOut,
                    packageName = packageName,
                    bounds = bounds,
                    tileSources = tileSources,
                    includeDem = includeDem,
                    includeAol = includeAol,
                    mappingLayers = mappingLayers,
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
            readPackageEntries(context, srcUri).use { entryBytes ->
            val manifestBytes = entryBytes[MANIFEST_PATH] ?: return false to "Package is missing manifest.json."
            val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
            if (manifest.optString("format") != FORMAT) {
                return false to "Unexpected map package format."
            }

            CredentialImportPolicy.requireMapOnlyPackage(manifest)

            val tileEntries = manifest.optJSONArray("tile_entries") ?: JSONArray()
            val mappingLayers = manifest.optJSONArray("mapping_layers")?.let { array ->
                (0 until array.length()).mapNotNull { index -> runCatching { MappingLayer.parse(array.getJSONObject(index)) }.getOrNull() }
            }.orEmpty()
            val layerBySource = mappingLayers.associateBy { "R2C-Mapping-${it.id}" }
            val importedSurfaces=org.ncssar.rid2caltopo.mapping.MappingSurfaceFiles.import(context,manifest.optJSONArray("surface_entries")?:JSONArray(),mappingLayers){path->entryBytes[path]}
            var importedTiles = 0
            for (i in 0 until tileEntries.length()) {
                val item = tileEntries.getJSONObject(i)
                val sourceName = item.optString("source")
                val layer = layerBySource[sourceName]
                val source = layer?.tileSource() ?: resolveTileSource(sourceName) ?: continue
                val z = item.optInt("z")
                val x = item.optInt("x")
                val y = item.optInt("y")
                val path = item.optString("path")
                val bytes = entryBytes[path] ?: continue
                val expiresAt = if (layer != null) Long.MAX_VALUE else item.optLong("expires_at_epoch_ms", 0L).takeIf { it > 0L }
                val idx = MapTileIndex.getTileIndex(z, x, y)
                if (tileWriter.importTileBytes(source, idx, bytes, expiresAt)) {
                    importedTiles++
                }
            }
            if (mappingLayers.isNotEmpty()) {
                val incident = mappingLayers.first().incidentId
                require(mappingLayers.all { it.incidentId == incident }) { "Map package mixes mapping layers from different incidents." }
                MappingLayer.save(context, incident, MappingLayer.load(context, incident).filterNot { old -> mappingLayers.any { it.id == old.id } } + mappingLayers)
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

            val importedAol=org.ncssar.rid2caltopo.video.surface.SurfaceStore.importSets(context,entryBytes.aolEntries())
            true to "Imported map package: $importedAol AOL tile(s), $importedTiles map tile(s), ${mappingLayers.size} R2C mapping layer(s), $importedSurfaces 3D surface(s), $importedDem DEM tile(s)."
            }
        } catch (e: Exception) {
            CaltopoClient.CTWarn("MutualAidPackageMgr", "importPackage() failed.", e)
            false to (e.message ?: "Failed to import map package.")
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
            readPackageEntries(context, srcUri).use { entryBytes ->
            val manifestBytes = entryBytes[MANIFEST_PATH] ?: return false to null
            val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
            if (manifest.optString("format") != FORMAT) {
                return false to null
            }
            CredentialImportPolicy.requireMapOnlyPackage(manifest)
            val profileJson = JSONObject()
            true to PackagePreview(
                packageName = manifest.optString("package_name"),
                includesMapAccess = false,
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
            }
        } catch (e: Exception) {
            CaltopoClient.CTWarn("MutualAidPackageMgr", "readPackagePreview() failed.", e)
            false to null
        }
    }

    /** Only one entry is held in memory; high-resolution mapping packages can contain thousands of tiles. */
    private class PackageEntries(private val file:File):java.io.Closeable {
        private val zip=java.util.zip.ZipFile(file)
        operator fun get(path:String):ByteArray? {
            val entry=zip.getEntry(path)?:return null
            require(entry.size in 0..128L*1024*1024){"Map package entry is too large."}
            return zip.getInputStream(entry).use{it.readBytes()}
        }
        fun aolEntries():Map<String,ByteArray> = zip.entries().asSequence().filter{!it.isDirectory&&it.name.startsWith("aol/")}.associate{it.name to (get(it.name)?:error("Missing AOL entry"))}
        override fun close(){try{zip.close()}finally{file.delete()}}
    }
    private fun readPackageEntries(context: Context, srcUri: Uri):PackageEntries {
        val file=File.createTempFile("map-package-",".zip",org.ncssar.rid2caltopo.storage.TemporaryStorage.directory(context,"map-transfers"))
        try{
            context.contentResolver.openInputStream(srcUri)?.use{input->file.outputStream().buffered().use{output->
                val block=ByteArray(65536);var total=0L
                while(true){val n=input.read(block);if(n<0)break;total+=n;require(total<=2L*1024*1024*1024){"Map package exceeds 2 GiB."};output.write(block,0,n)}
            }}?:error("Could not open selected map package.")
            return PackageEntries(file)
        }catch(e:Exception){file.delete();throw e}
    }

    private fun resolveTileSource(sourceName: String): ITileSource? = when (sourceName) {
        OsmStandardTileSource.name() -> OsmStandardTileSource
        ArcGisWorldImageryTileSource.name() -> ArcGisWorldImageryTileSource
        UsgsContoursTileSource.name() -> UsgsContoursTileSource
        else -> null
    }

    private fun sanitizePath(raw: String): String =
        raw.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]+"), "_").trim('_')

    private fun writePackage(
        context: Context,
        rawOut: OutputStream,
        packageName: String,
        bounds: BoundingBox,
        tileSources: List<ITileSource>,
        includeDem: Boolean,
        includeAol: Boolean,
        mappingLayers: List<MappingLayer>,
    ): Pair<Boolean, String> {
        val resolver = context.contentResolver
        val tileWriter = TileDiskCacheWriter(context)
        val region = MapPackageRegion(bounds.latNorth, bounds.lonEast, bounds.latSouth, bounds.lonWest)
        val tileEntries = ArrayList<JSONObject>()
        val layerEntries = JSONArray()
        val surfaceEntries = JSONArray()
        val demEntries = ArrayList<JSONObject>()
        val archiveDir = CaltopoClient.GetArchiveDir()
        val demDir = archiveDir?.findFile("cache")?.findFile("dem")
        var aolCount=0
        ZipOutputStream(rawOut).use { zip ->
            for (layer in mappingLayers) {
                val source = layer.tileSource()
                val included = tileWriter.cachedTileIndices(source).filter { index ->
                    region.includesTile(MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index))
                }
                if (included.isEmpty()) continue
                layerEntries.put(layer.json())
                for((record,file) in org.ncssar.rid2caltopo.mapping.MappingSurfaceFiles.export(context,layer)){
                    zip.putNextEntry(ZipEntry(record.getString("path")));file.inputStream().use{it.copyTo(zip)};zip.closeEntry();surfaceEntries.put(record)
                }
                for (index in included) {
                    val bytes = tileWriter.readTileBytes(source, index) ?: continue
                    val z = MapTileIndex.getZoom(index); val x = MapTileIndex.getX(index); val y = MapTileIndex.getY(index)
                    val path = "mapping-layers/${layer.id}/$z/$x/$y.png"
                    zip.putNextEntry(ZipEntry(path));zip.write(bytes);zip.closeEntry()
                    tileEntries += JSONObject().put("source", source.name()).put("z",z).put("x",x).put("y",y)
                        .put("expires_at_epoch_ms",0).put("path",path)
                }
            }
            for (tileSource in tileSources) {
                // Enforce export rights even for old selections and existing cached imagery.
                if (!org.ncssar.rid2caltopo.video.mapcache.MapTileUsagePolicy.allowsPackageExport(tileSource.name())) continue
                for (tileIndex in tileWriter.cachedTileIndices(tileSource)) {
                    if (!region.includesTile(MapTileIndex.getZoom(tileIndex), MapTileIndex.getX(tileIndex), MapTileIndex.getY(tileIndex))) continue
                    val bytes = tileWriter.readTileBytes(tileSource, tileIndex) ?: continue
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

            }

            if (includeDem) {
                for (demFile in demDir?.listFiles().orEmpty()) {
                    if (!demFile.isFile) continue
                    val fileName = demFile.name ?: continue
                    val demBounds = MapPackageRegion.demBounds(fileName) ?: continue
                    if (!region.overlaps(demBounds)) continue
                    val path = "dem/$fileName"
                    resolver.openInputStream(demFile.uri)?.use { input ->
                        zip.putNextEntry(ZipEntry(path))
                        input.copyTo(zip)
                        zip.closeEntry()
                    } ?: continue
                    demEntries += JSONObject()
                        .put("tile_name", fileName)
                        .put("file_name", fileName)
                        .put("path", path)
                }
            }

            val aolFiles=if (includeAol) org.ncssar.rid2caltopo.video.surface.SurfaceStore.exportSets(context,
                org.ncssar.rid2caltopo.video.surface.SurfaceBounds(bounds.lonWest,bounds.latSouth,bounds.lonEast,bounds.latNorth)) else emptyList()
            aolCount=aolFiles.count { it.first.endsWith(".aol") }
            for((path,bytes) in aolFiles) { zip.putNextEntry(ZipEntry(path));zip.write(bytes);zip.closeEntry() }
            val manifest = JSONObject()
                .put("aol_entries", JSONArray(aolFiles.map { it.first }))
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("generated", CaltopoClient.TimeDatestampString(System.currentTimeMillis()))
                .put("package_name", packageName)
                .put("source_org", "")
                .put("profile_enc", "")
                .put("mapping_layers", layerEntries)
                .put("surface_entries", surfaceEntries)
                .put("tile_entries", JSONArray(tileEntries))
                .put("dem_entries", JSONArray(demEntries))
            zip.putNextEntry(ZipEntry(MANIFEST_PATH))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return true to "Saved map package with $aolCount AOL tile(s), ${tileEntries.size} tile(s) and ${demEntries.size} DEM tile(s)."
    }

}

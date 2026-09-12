package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import mil.nga.tiff.compression.DeflateCompression
import mil.nga.tiff.compression.LZWCompression
import mil.nga.tiff.compression.PackbitsCompression
import mil.nga.tiff.compression.Predictor
import mil.nga.tiff.compression.RawCompression
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal class GeoTiffDemSource(context: Context) {
    data class Sample(val elevationMeters: Double, val horizontalResolutionMeters: Double)
    private val appContext = context.applicationContext
    private val lock = Any()
    private var catalogRefreshedAtMs: Long = 0L
    private var tiles: List<DemTile> = emptyList()
    private val metadataCache = object : LinkedHashMap<String, GeoTiffMetadata>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GeoTiffMetadata>?): Boolean {
            return size > 128
        }
    }
    /**
     * Directory for pre-decoded block binaries: {noBackupFilesDir}/dem_blocks_v2/{tileName}/{blockIndex}.f32raw
     * Each file is a flat little-endian float32 array of (blockWidth × blockHeight) samples.
     * Reading elevation from a cached block is O(1): seek to offset, read 4 bytes — no LZW/pred3.
     *
     * Keep the decoder version in the directory name. Version 1 used an incorrect
     * floating-point predictor implementation, so those persisted blocks must
     * never be reused after the decoder is corrected.
     */
    private val blockCacheDir = File(appContext.noBackupFilesDir, "dem_blocks_v2")

    /** In-memory LRU of recently decoded blocks (max 8 ≈ 8 MB). Keyed by "$tileName|$blockIndex". */
    private val blockMemCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ByteArray>?) = size > 8
    }

    /** Force the next call to [sampleElevationMeters] to rebuild the tile catalog from disk. */
    fun invalidateCatalog() {
        synchronized(lock) { catalogRefreshedAtMs = 0L }
    }

    fun sampleElevationMeters(lat: Double, lng: Double): Double? {
        return sample(lat, lng)?.elevationMeters
    }

    fun sample(lat: Double, lng: Double): Sample? {
        if (!lat.isFinite() || !lng.isFinite()) return null
        val candidates = synchronized(lock) {
            refreshCatalogLocked()
            tiles.filter { tile -> tile.bounds?.contains(lat, lng) != false }
        }
        if (candidates.isEmpty()) return null

        var best: Sample? = null
        for (tile in candidates) {
            val metadata = getOrLoadMetadata(tile) ?: continue
            val value = trySampleFromTile(tile, metadata, lat, lng)
            if (value != null && value.isFinite()) {
                val candidate = Sample(value, metadata.horizontalResolutionMeters(lat))
                if (best == null || candidate.horizontalResolutionMeters < best.horizontalResolutionMeters) {
                    best = candidate
                }
            }
        }
        return best
    }

    private var budgetGeneration = -1L
    private fun refreshCatalogLocked() {
        if (budgetGeneration != UnifiedMapCache.generation) {
            catalogRefreshedAtMs = 0L
            budgetGeneration = UnifiedMapCache.generation
        }
        val now = System.currentTimeMillis()
        if (now - catalogRefreshedAtMs < 60_000L && tiles.isNotEmpty()) return
        catalogRefreshedAtMs = now

        val archive = CaltopoClient.GetArchiveDir()
        if (archive == null || !archive.isDirectory) {
            tiles = emptyList()
            return
        }

        val cacheDir = archive.findFile("cache")
        val demDir = cacheDir?.findFile("dem")
        val roots = mutableListOf<DocumentFile>()
        if (demDir != null && demDir.isDirectory) roots += demDir
        if (cacheDir != null && cacheDir.isDirectory) roots += cacheDir

        val out = mutableListOf<DemTile>()
        roots.forEach { root ->
            root.listFiles().forEach { doc ->
                if (!doc.isFile) return@forEach
                val name = doc.name ?: return@forEach
                if (!name.lowercase(Locale.US).endsWith(".tif") && !name.lowercase(Locale.US).endsWith(".tiff")) {
                    return@forEach
                }
                out += DemTile(
                    id = doc.uri.toString(),
                    uri = doc.uri,
                    displayName = name,
                    bounds = boundsFromName(name)
                )
            }
        }

        tiles = out
        if (out.isNotEmpty()) {
            CTDebug("SplitMapPane", "DEM GeoTIFF catalog loaded tiles=${out.size}")
        }
    }

    private fun getOrLoadMetadata(tile: DemTile): GeoTiffMetadata? {
        synchronized(lock) {
            metadataCache[tile.id]?.let { return it }
        }

        val loaded = loadMetadata(tile) ?: return null
        synchronized(lock) {
            metadataCache[tile.id] = loaded
        }
        // Log key metadata once per tile on first load to aid decoder diagnostics.
        val vertUnit = when (loaded.verticalUnitCode) {
            9001 -> if (loaded.verticalUnitCodeExplicit) "m(9001)" else "m(9001-default)"
            9002 -> "ft(9002)"
            9003 -> "ftUS(9003)"
            else -> "?(${loaded.verticalUnitCode})"
        }
        MapCacheDebug.warn(MapCacheDebug.TAG_DEM,
            "dem meta ${tile.displayName}: " +
                "size=${loaded.width}x${loaded.height} " +
                "tile=${loaded.tileWidth}x${loaded.tileHeight} " +
                "rowsPerStrip=${loaded.rowsPerStrip} " +
                "isTiled=${loaded.isTiled} blocks=${loaded.blockOffsets.size} " +
                "comp=${loaded.compression} pred=${loaded.predictor} " +
                "bps=${loaded.bitsPerSample} fmt=${loaded.sampleFormat} " +
                "bo=${loaded.byteOrder} spp=${loaded.samplesPerPixel} " +
                "vertUnit=$vertUnit nodata=${loaded.noDataValue} " +
                "tieIJ=${loaded.tieI},${loaded.tieJ} tieXY=${"%.6f".format(Locale.US, loaded.tieX)},${"%.6f".format(Locale.US, loaded.tieY)} " +
                "scaleXY=${"%.8f".format(Locale.US, loaded.scaleX)},${"%.8f".format(Locale.US, loaded.scaleY)}")
        return loaded
    }

    private fun loadMetadata(tile: DemTile): GeoTiffMetadata? {
        return openDataSource(tile.uri)?.use { source ->
            try {
                parseMetadata(source)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun trySampleFromTile(tile: DemTile, metadata: GeoTiffMetadata, lat: Double, lng: Double): Double? {
        if (metadata.scaleX == 0.0 || metadata.scaleY == 0.0) return null
        val model = metadata.modelCoordinate(lat, lng) ?: return null
        val colF = metadata.tieI + (model.first - metadata.tieX) / metadata.scaleX
        val rowF = metadata.tieJ + (metadata.tieY - model.second) / metadata.scaleY
        if (!colF.isFinite() || !rowF.isFinite()) return null
        if (colF < 0.0 || rowF < 0.0 || colF > (metadata.width - 1).toDouble() || rowF > (metadata.height - 1).toDouble()) {
            return null
        }
        if (metadata.planarConfiguration != 1) return null

        return openDataSource(tile.uri)?.use { source ->
            val c0 = floor(colF).toInt().coerceIn(0, metadata.width - 1)
            val r0 = floor(rowF).toInt().coerceIn(0, metadata.height - 1)
            val c1 = (c0 + 1).coerceAtMost(metadata.width - 1)
            val r1 = (r0 + 1).coerceAtMost(metadata.height - 1)

            val s00 = sampleAtPixel(source, metadata, tile.displayName, c0, r0) ?: return@use null
            if (isNoData(s00, metadata.noDataValue)) return@use null
            if (c0 == c1 && r0 == r1) {
                MapCacheDebug.warn(MapCacheDebug.TAG_DEM,
                    "dem sample lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)} " +
                        "colF=${"%.2f".format(Locale.US, colF)} rowF=${"%.2f".format(Locale.US, rowF)} " +
                        "c0=$c0 r0=$r0 s00=${"%.2f".format(Locale.US, s00)} -> exact")
                return@use s00
            }

            val s10 = sampleAtPixel(source, metadata, tile.displayName, c1, r0) ?: return@use null
            val s01 = sampleAtPixel(source, metadata, tile.displayName, c0, r1) ?: return@use null
            val s11 = sampleAtPixel(source, metadata, tile.displayName, c1, r1) ?: return@use null
            if (isNoData(s10, metadata.noDataValue) ||
                isNoData(s01, metadata.noDataValue) ||
                isNoData(s11, metadata.noDataValue)
            ) {
                return@use null
            }

            val dx = (colF - c0.toDouble()).coerceIn(0.0, 1.0)
            val dy = (rowF - r0.toDouble()).coerceIn(0.0, 1.0)
            val result = bilinearInterpolate(s00, s10, s01, s11, dx, dy)
            MapCacheDebug.warn(MapCacheDebug.TAG_DEM,
                "dem sample lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)} " +
                    "colF=${"%.2f".format(Locale.US, colF)} rowF=${"%.2f".format(Locale.US, rowF)} " +
                    "c0=$c0 r0=$r0 s00=${"%.2f".format(Locale.US, s00)} s10=${"%.2f".format(Locale.US, s10)} " +
                    "s01=${"%.2f".format(Locale.US, s01)} s11=${"%.2f".format(Locale.US, s11)} -> ${"%.2f".format(Locale.US, result)}")
            result
        }
    }

    private fun sampleAtPixel(
        source: RandomAccessDataSource,
        metadata: GeoTiffMetadata,
        tileName: String,
        col: Int,
        row: Int
    ): Double? {
        val decoded = readDecodedBlockBytes(tileName, source, metadata, col, row) ?: return null
        return extractSample(decoded, metadata, col, row)
    }

    /**
     * Returns the decoded byte array for the TIFF block containing pixel (col, row).
     *
     * Three-tier cache (fastest → slowest):
     *   1. [blockMemCache] — in-memory LRU, O(1), no I/O.
     *   2. Block file on disk — flat float32 binary written after first decode; O(1) seek+read,
     *      no LZW/pred3.  Survives app restarts.
     *   3. TIFF decode — LZW decompress + pred3 undo (expensive).  Result is saved to the block
     *      file and mem cache so it is never repeated for this block.
     */
    private fun readDecodedBlockBytes(
        tileName: String,
        source: RandomAccessDataSource,
        metadata: GeoTiffMetadata,
        col: Int,
        row: Int
    ): ByteArray? {
        val blockIndex: Int
        val blockWidth: Int
        val blockHeight: Int

        if (metadata.isTiled) {
            val tw = metadata.tileWidth ?: return null
            val th = metadata.tileHeight ?: return null
            val tileCols = ceil(metadata.width.toDouble() / tw.toDouble()).toInt()
            val tileCol = floor(col.toDouble() / tw.toDouble()).toInt()
            val tileRow = floor(row.toDouble() / th.toDouble()).toInt()
            blockIndex = tileRow * tileCols + tileCol
            if (blockIndex !in metadata.blockOffsets.indices || blockIndex !in metadata.blockByteCounts.indices) return null
            blockWidth = tw
            blockHeight = th
        } else {
            val rowsPerStrip = metadata.rowsPerStrip ?: return null
            val strip = floor(row.toDouble() / rowsPerStrip.toDouble()).toInt()
            if (strip !in metadata.blockOffsets.indices || strip !in metadata.blockByteCounts.indices) return null
            blockIndex = strip
            blockWidth = metadata.width
            blockHeight = minOf(rowsPerStrip, metadata.height - (strip * rowsPerStrip))
        }

        UnifiedMapCache.touchTerrain(tileName)
        val memKey = "$tileName|$blockIndex"

        // 1. Memory cache — no I/O
        synchronized(lock) { blockMemCache[memKey] }?.let { return it }

        // 2. Pre-decoded block file — cheap read, no LZW/pred3
        val bf = blockFile(tileName, blockIndex)
        if (bf.exists() && bf.length() > 0L) {
            try {
                val bytes = bf.readBytes()
                synchronized(lock) { blockMemCache[memKey] = bytes }
                MapCacheDebug.log("dem block-file-hit $tileName #$blockIndex (${bf.length()}B)")
                return bytes
            } catch (e: Exception) {
                MapCacheDebug.warn(MapCacheDebug.TAG_DEM, "dem block-file-read-fail $bf: ${e.message}")
            }
        }

        // 3. Decode from TIFF (expensive: LZW decompress + pred3 undo)
        val raw = try {
            source.readFully(metadata.blockOffsets[blockIndex], metadata.blockByteCounts[blockIndex].toInt())
        } catch (e: Exception) {
            MapCacheDebug.warn(MapCacheDebug.TAG_DEM, "dem block-raw-read-fail idx=$blockIndex: ${e.message}")
            return null
        }
        val decoded = decodeBlock(raw, metadata, blockWidth, blockHeight) ?: return null

        // Persist decoded block — future sessions skip the LZW+pred3 work entirely.
        try {
            bf.parentFile?.mkdirs()
            UnifiedMapCache.reserve(appContext, decoded.size.toLong()).use {
                bf.writeBytes(decoded)
                UnifiedMapCache.remember(bf)
            }
            MapCacheDebug.warn(MapCacheDebug.TAG_DEM,
                "dem block-saved $tileName #$blockIndex (${decoded.size}B)")
        } catch (e: Exception) {
            MapCacheDebug.warn(MapCacheDebug.TAG_DEM, "dem block-file-write-fail $bf: ${e.message}")
        }

        synchronized(lock) { blockMemCache[memKey] = decoded }
        return decoded
    }

    /** Returns the File path for a pre-decoded block binary. */
    private fun blockFile(tileName: String, blockIndex: Int): File {
        val safeName = tileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(File(blockCacheDir, safeName), "$blockIndex.f32raw")
    }

    private fun isNoData(value: Double, noDataValue: Double?): Boolean {
        return noDataValue != null && kotlin.math.abs(value - noDataValue) < 0.001
    }

    private fun bilinearInterpolate(
        s00: Double,
        s10: Double,
        s01: Double,
        s11: Double,
        dx: Double,
        dy: Double
    ): Double {
        val top = s00 + ((s10 - s00) * dx)
        val bottom = s01 + ((s11 - s01) * dx)
        return top + ((bottom - top) * dy)
    }

    private fun decodeBlock(
        raw: ByteArray,
        metadata: GeoTiffMetadata,
        blockWidth: Int,
        blockHeight: Int
    ): ByteArray? {
        val decoded = when (metadata.compression) {
            1 -> RawCompression().decode(raw, metadata.byteOrder)
            5 -> LZWCompression().decode(raw, metadata.byteOrder)
            8, 32946 -> DeflateCompression().decode(raw, metadata.byteOrder)
            32773 -> PackbitsCompression().decode(raw, metadata.byteOrder)
            else -> return null
        }
        val predictor = metadata.predictor
        return when {
            predictor == null || predictor <= 1 -> decoded
            predictor == 2 || predictor == 3 -> try {
                // Predictor.decode expects predictor before the image dimensions.
                // It supports both integer horizontal differencing (2) and the
                // floating-point byte-plane transform (3).
                Predictor.decode(
                    decoded,
                    predictor,
                    blockWidth,
                    blockHeight,
                    metadata.bitsPerSample,
                    metadata.planarConfiguration,
                )
            } catch (e: Exception) {
                MapCacheDebug.warn(
                    MapCacheDebug.TAG_DEM,
                    "decodeBlock predictor$predictor failed: ${e.message}",
                )
                null
            }
            else -> {
                MapCacheDebug.warn(MapCacheDebug.TAG_DEM, "decodeBlock unsupported predictor=$predictor")
                null
            }
        }
    }

    private fun extractSample(
        decodedBlock: ByteArray,
        metadata: GeoTiffMetadata,
        col: Int,
        row: Int
    ): Double? {
        val blockWidth: Int
        val localCol: Int
        val localRow: Int

        if (metadata.isTiled) {
            val tw = metadata.tileWidth ?: return null
            val th = metadata.tileHeight ?: return null
            blockWidth = tw
            localCol = col % tw
            localRow = row % th
        } else {
            val rowsPerStrip = metadata.rowsPerStrip ?: return null
            blockWidth = metadata.width
            localCol = col
            localRow = row % rowsPerStrip
        }

        val bits = metadata.bitsPerSample.firstOrNull() ?: return null
        val bytesPerSample = bits / 8
        if (bytesPerSample <= 0) return null
        val bytesPerPixel = bytesPerSample * metadata.samplesPerPixel
        val offset = ((localRow * blockWidth) + localCol) * bytesPerPixel
        if (offset + bytesPerSample > decodedBlock.size) return null

        val rawValue: Double? = when (metadata.sampleFormat) {
            3 -> {
                if (bits == 32) {
                    val i = readInt(decodedBlock, offset, metadata.byteOrder)
                    Float.fromBits(i).toDouble()
                } else if (bits == 64) {
                    val l = readLong(decodedBlock, offset, metadata.byteOrder)
                    Double.fromBits(l)
                } else null
            }

            2 -> {
                if (bits == 16) readShort(decodedBlock, offset, metadata.byteOrder).toDouble()
                else if (bits == 32) readInt(decodedBlock, offset, metadata.byteOrder).toDouble()
                else null
            }

            else -> {
                if (bits == 16) readUShort(decodedBlock, offset, metadata.byteOrder).toDouble()
                else if (bits == 32) readUInt(decodedBlock, offset, metadata.byteOrder).toDouble()
                else null
            }
        }

        // Convert elevation to metres if the file uses a foot-based vertical unit.
        return when (metadata.verticalUnitCode) {
            9002, 9003 -> rawValue?.let { it * 0.3048 }  // International or US Survey foot → m
            else        -> rawValue                        // 9001 (metre) or unknown → pass through
        }
    }

    private fun parseMetadata(source: RandomAccessDataSource): GeoTiffMetadata {
        val bo = readByteOrder(source)
        val firstIfd = readUInt(source, 4, bo)
        val entryCount = readUShort(source, firstIfd, bo)
        var ptr = firstIfd + 2L

        val values = HashMap<Int, Any>()
        repeat(entryCount) {
            val tag = readUShort(source, ptr, bo)
            val type = readUShort(source, ptr + 2, bo)
            val count = readUInt(source, ptr + 4, bo)
            val valueOrOffset = readUInt(source, ptr + 8, bo)
            ptr += 12L

            val size = typeSize(type)
            if (size <= 0 || count <= 0) return@repeat
            val totalBytes = count * size
            val bytes = if (totalBytes <= 4) {
                val inline = source.readFully(ptr - 4L, 4)
                inline.copyOf(totalBytes.toInt())
            } else {
                source.readFully(valueOrOffset, totalBytes.toInt())
            }

            values[tag] = decodeValues(type, count.toInt(), bytes, bo)
        }

        val width = firstLong(values[256])?.toInt() ?: 0
        val height = firstLong(values[257])?.toInt() ?: 0
        val compression = firstLong(values[259])?.toInt() ?: 1
        val samples = firstLong(values[277])?.toInt() ?: 1
        val planar = firstLong(values[284])?.toInt() ?: 1
        val predictor = firstLong(values[317])?.toInt()

        val bits = toIntList(values[258]).ifEmpty { listOf(16) }
        val sampleFormat = toIntList(values[339]).firstOrNull() ?: 1

        val tie = toDoubleList(values[33922])
        val scale = toDoubleList(values[33550])
        val tieI = tie.getOrElse(0) { 0.0 }
        val tieJ = tie.getOrElse(1) { 0.0 }
        val tieX = tie.getOrElse(3) { 0.0 }
        val tieY = tie.getOrElse(4) { 0.0 }
        val scaleX = scale.getOrElse(0) { 0.0 }
        val scaleY = scale.getOrElse(1) { 0.0 }

        val tileOffsets = toLongList(values[324])
        val tileCounts = toLongList(values[325])
        val stripOffsets = toLongList(values[273])
        val stripCounts = toLongList(values[279])

        val tileWidth = firstLong(values[322])?.toInt()
        val tileHeight = firstLong(values[323])?.toInt()
        val rowsPerStrip = firstLong(values[278])?.toInt()

        val isTiled = tileOffsets.isNotEmpty() && tileCounts.isNotEmpty() && tileWidth != null && tileHeight != null
        val offsets = if (isTiled) tileOffsets else stripOffsets
        val counts = if (isTiled) tileCounts else stripCounts

        val noDataValue = parseNoData(values[42113])

        // GeoKeyDirectoryTag (34735): array of SHORTs laid out as:
        //   [version, revision, minor, numKeys, key0_id, key0_tagLoc, key0_count, key0_valueOrIdx, ...]
        // We extract GeoKey 4099 (VerticalUnitsGeoKey) to know the elevation unit.
        // When tagLoc == 0, the value is stored directly in the valueOrIdx field.
        val geoKeyDir = toIntList(values[34735])
        val numGeoKeys = geoKeyDir.getOrElse(3) { 0 }
        var verticalUnitCode = 9001  // default: Linear_Meter
        var verticalUnitCodeExplicit = false
        var projectedCSTypeCode: Int? = null
        for (k in 0 until numGeoKeys) {
            val base = 4 + k * 4
            val keyId    = geoKeyDir.getOrElse(base)     { 0 }
            val tagLoc   = geoKeyDir.getOrElse(base + 1) { 0 }
            val valueOrIdx = geoKeyDir.getOrElse(base + 3) { 0 }
            if (keyId == 4099 && tagLoc == 0) {
                verticalUnitCode = valueOrIdx
                verticalUnitCodeExplicit = true
            }
            if (keyId == 3072 && tagLoc == 0) {
                projectedCSTypeCode = valueOrIdx
            }
        }

        return GeoTiffMetadata(
            width = width,
            height = height,
            byteOrder = bo,
            compression = compression,
            predictor = predictor,
            samplesPerPixel = samples,
            planarConfiguration = planar,
            bitsPerSample = bits,
            sampleFormat = sampleFormat,
            tieI = tieI,
            tieJ = tieJ,
            tieX = tieX,
            tieY = tieY,
            scaleX = scaleX,
            scaleY = scaleY,
            isTiled = isTiled,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            rowsPerStrip = rowsPerStrip,
            blockOffsets = offsets,
            blockByteCounts = counts,
            noDataValue = noDataValue,
            verticalUnitCode = verticalUnitCode,
            verticalUnitCodeExplicit = verticalUnitCodeExplicit,
            projectedCSTypeCode = projectedCSTypeCode,
        )
    }

    private fun readByteOrder(source: RandomAccessDataSource): ByteOrder {
        val marker = source.readFully(0, 2)
        val m = String(marker, Charsets.US_ASCII)
        return when (m) {
            "II" -> ByteOrder.LITTLE_ENDIAN
            "MM" -> ByteOrder.BIG_ENDIAN
            else -> throw IllegalArgumentException("Not TIFF")
        }
    }

    private fun typeSize(type: Int): Long {
        return when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12 -> 8
            else -> -1
        }
    }

    private fun decodeValues(type: Int, count: Int, bytes: ByteArray, bo: ByteOrder): Any {
        val bb = ByteBuffer.wrap(bytes).order(bo)
        return when (type) {
            2 -> {
                val raw = bytes.copyOf(count)
                val str = String(raw, Charsets.US_ASCII)
                str.trim('\u0000', ' ')
            }

            3 -> List(count) { bb.short.toInt() and 0xFFFF }
            4 -> List(count) { bb.int.toLong() and 0xFFFFFFFFL }
            5 -> List(count) {
                val n = bb.int.toLong() and 0xFFFFFFFFL
                val d = bb.int.toLong() and 0xFFFFFFFFL
                if (d == 0L) 0.0 else n.toDouble() / d.toDouble()
            }

            8 -> List(count) { bb.short.toInt() }
            9 -> List(count) { bb.int.toLong() }
            10 -> List(count) {
                val n = bb.int.toLong()
                val d = bb.int.toLong()
                if (d == 0L) 0.0 else n.toDouble() / d.toDouble()
            }

            11 -> List(count) { bb.float.toDouble() }
            12 -> List(count) { bb.double }
            else -> bytes
        }
    }

    private fun firstLong(v: Any?): Long? {
        val list = when (v) {
            is List<*> -> v
            else -> return null
        }
        return (list.firstOrNull() as? Number)?.toLong()
    }

    private fun toLongList(v: Any?): List<Long> {
        val list = v as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? Number)?.toLong() }
    }

    private fun toDoubleList(v: Any?): List<Double> {
        val list = v as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? Number)?.toDouble() }
    }

    private fun toIntList(v: Any?): List<Int> {
        val list = v as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? Number)?.toInt() }
    }

    private fun parseNoData(v: Any?): Double? {
        return when (v) {
            is String -> v.toDoubleOrNull()
            is List<*> -> (v.firstOrNull() as? Number)?.toDouble()
            else -> null
        }
    }

    private fun openDataSource(uri: Uri): RandomAccessDataSource? {
        return if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let { File(it) } ?: return null
            if (!file.exists() || !file.isFile) return null
            FileRandomAccessDataSource(file)
        } else {
            val pfd = appContext.contentResolver.openFileDescriptor(uri, "r") ?: return null
            PfdRandomAccessDataSource(pfd)
        }
    }

    private fun readUShort(bytes: ByteArray, offset: Int, bo: ByteOrder): Int {
        return if (bo == ByteOrder.LITTLE_ENDIAN) {
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        } else {
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        }
    }

    private fun readShort(bytes: ByteArray, offset: Int, bo: ByteOrder): Short {
        return readUShort(bytes, offset, bo).toShort()
    }

    private fun readUInt(bytes: ByteArray, offset: Int, bo: ByteOrder): Long {
        return readInt(bytes, offset, bo).toLong() and 0xFFFFFFFFL
    }

    private fun readInt(bytes: ByteArray, offset: Int, bo: ByteOrder): Int {
        return if (bo == ByteOrder.LITTLE_ENDIAN) {
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        } else {
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        }
    }

    private fun readLong(bytes: ByteArray, offset: Int, bo: ByteOrder): Long {
        val hi: Long
        val lo: Long
        if (bo == ByteOrder.LITTLE_ENDIAN) {
            lo = readUInt(bytes, offset, bo)
            hi = readUInt(bytes, offset + 4, bo)
        } else {
            hi = readUInt(bytes, offset, bo)
            lo = readUInt(bytes, offset + 4, bo)
        }
        return (hi shl 32) or lo
    }

    private fun readUShort(source: RandomAccessDataSource, position: Long, bo: ByteOrder): Int {
        val b = source.readFully(position, 2)
        return readUShort(b, 0, bo)
    }

    private fun readUInt(source: RandomAccessDataSource, position: Long, bo: ByteOrder): Long {
        val b = source.readFully(position, 4)
        return readUInt(b, 0, bo)
    }

    private fun boundsFromName(name: String): TileBounds? {
        val planned = PLANNED_1M_NAME_PATTERN.matcher(name)
        if (planned.matches()) {
            val minLat = planned.group(1)?.toDoubleOrNull()?.div(100_000.0) ?: return null
            val maxLat = planned.group(2)?.toDoubleOrNull()?.div(100_000.0) ?: return null
            val minLon = planned.group(3)?.toDoubleOrNull()?.div(100_000.0) ?: return null
            val maxLon = planned.group(4)?.toDoubleOrNull()?.div(100_000.0) ?: return null
            return TileBounds(minLat, maxLat, minLon, maxLon)
        }
        val m = TILE_NAME_PATTERN.matcher(name)
        if (!m.matches()) return null
        val ns = m.group(1)?.lowercase(Locale.US) ?: return null
        val latDeg = m.group(2)?.toIntOrNull() ?: return null
        val ew = m.group(3)?.lowercase(Locale.US) ?: return null
        val lonDeg = m.group(4)?.toIntOrNull() ?: return null

        val minLat: Double
        val maxLat: Double
        if (ns == "n") {
            // USGS 3DEP convention: the number in the tile name is the NORTH (NW corner) edge.
            // e.g. n40w122 covers 39–40°N, 121–122°W — NOT 40–41°N.
            maxLat = latDeg.toDouble()
            minLat = maxLat - 1.0
        } else {
            // Southern hemisphere: sXX = NW corner latitude (negative). e.g. s38 → -38°N..−39°N.
            maxLat = -latDeg.toDouble()
            minLat = maxLat - 1.0
        }

        val minLon: Double
        val maxLon: Double
        if (ew == "e") {
            minLon = lonDeg.toDouble()
            maxLon = minLon + 1.0
        } else {
            minLon = -lonDeg.toDouble()
            maxLon = minLon + 1.0
        }

        return TileBounds(minLat, maxLat, minLon, maxLon)
    }

    private data class DemTile(
        val id: String,
        val uri: Uri,
        val displayName: String,
        val bounds: TileBounds?
    )

    private data class TileBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    ) {
        fun contains(lat: Double, lng: Double): Boolean {
            return lat >= minLat && lat < maxLat && lng >= minLon && lng < maxLon
        }
    }

    private data class GeoTiffMetadata(
        val width: Int,
        val height: Int,
        val byteOrder: ByteOrder,
        val compression: Int,
        val predictor: Int?,
        val samplesPerPixel: Int,
        val planarConfiguration: Int,
        val bitsPerSample: List<Int>,
        val sampleFormat: Int,
        val tieI: Double,
        val tieJ: Double,
        val tieX: Double,
        val tieY: Double,
        val scaleX: Double,
        val scaleY: Double,
        val isTiled: Boolean,
        val tileWidth: Int?,
        val tileHeight: Int?,
        val rowsPerStrip: Int?,
        val blockOffsets: List<Long>,
        val blockByteCounts: List<Long>,
        val noDataValue: Double?,
        /**
         * GeoKey 4099 (VerticalUnitsGeoKey): EPSG unit code for elevation values.
         *   9001 = Linear_Meter (default; assumed when key is absent)
         *   9002 = Linear_Foot
         *   9003 = Linear_Foot_US_Survey
         * Stored so that [extractSample] can convert to metres without re-parsing.
         */
        val verticalUnitCode: Int = 9001,
        /** True when GeoKey 4099 was explicitly present in the tile; false when 9001 is a default. */
        val verticalUnitCodeExplicit: Boolean = false,
        /** GeoKey 3072. USGS one-metre project DEMs are normally NAD83 UTM (EPSG 269xx). */
        val projectedCSTypeCode: Int? = null,
    ) {
        fun modelCoordinate(lat: Double, lng: Double): Pair<Double, Double>? {
            val epsg = projectedCSTypeCode ?: return lng to lat
            return if (epsg == 6350) latLonToConusAlbers(lat, lng) else latLonToUtm(lat, lng, epsg)
        }

        fun horizontalResolutionMeters(latitude: Double): Double {
            if (projectedCSTypeCode != null) return maxOf(kotlin.math.abs(scaleX), kotlin.math.abs(scaleY))
            val x = kotlin.math.abs(scaleX) * 111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.1)
            val y = kotlin.math.abs(scaleY) * 111_320.0
            return maxOf(x, y)
        }
    }

    companion object {
        private val TILE_NAME_PATTERN: Pattern =
            Pattern.compile(".*([nNsS])(\\d{2})([eEwW])(\\d{3}).*\\.tiff?$")
        private val PLANNED_1M_NAME_PATTERN: Pattern =
            Pattern.compile("R2C_(?:S1M|1M)_(-?\\d+)_(-?\\d+)_(-?\\d+)_(-?\\d+)_.*\\.tiff?$", Pattern.CASE_INSENSITIVE)

        /** WGS84/NAD83 latitude-longitude to EPSG:6350 NAD83(2011) / Conus Albers. */
        internal fun latLonToConusAlbers(lat: Double, lng: Double): Pair<Double, Double> {
            val a = 6_378_137.0
            val f = 1.0 / 298.257222101
            val e2 = f * (2.0 - f)
            val e = sqrt(e2)
            fun q(phi: Double): Double {
                val sinPhi = sin(phi)
                return (1.0 - e2) * (
                    sinPhi / (1.0 - e2 * sinPhi * sinPhi) -
                        kotlin.math.ln((1.0 - e * sinPhi) / (1.0 + e * sinPhi)) / (2.0 * e)
                    )
            }
            fun m(phi: Double): Double = cos(phi) / sqrt(1.0 - e2 * sin(phi).pow(2))
            val phi0 = Math.toRadians(23.0)
            val lambda0 = Math.toRadians(-96.0)
            val phi1 = Math.toRadians(29.5)
            val phi2 = Math.toRadians(45.5)
            val m1 = m(phi1)
            val m2 = m(phi2)
            val q1 = q(phi1)
            val q2 = q(phi2)
            val n = (m1 * m1 - m2 * m2) / (q2 - q1)
            val c = m1 * m1 + n * q1
            fun rhoAt(phi: Double): Double = a * sqrt(c - n * q(phi)) / n
            val rho = rhoAt(Math.toRadians(lat))
            val theta = n * (Math.toRadians(lng) - lambda0)
            return rho * sin(theta) to rhoAt(phi0) - rho * cos(theta)
        }

        /** Convert WGS84 latitude/longitude to the UTM coordinates used by USGS 1 m DEM projects. */
        internal fun latLonToUtm(lat: Double, lng: Double, epsg: Int): Pair<Double, Double>? {
            val zone: Int
            val northern: Boolean
            val inverseFlattening: Double
            when (epsg) {
                in 26901..26923 -> { zone = epsg - 26900; northern = true; inverseFlattening = 298.257222101 }
                in 32601..32660 -> { zone = epsg - 32600; northern = true; inverseFlattening = 298.257223563 }
                in 32701..32760 -> { zone = epsg - 32700; northern = false; inverseFlattening = 298.257223563 }
                else -> return null
            }
            val a = 6_378_137.0
            val f = 1.0 / inverseFlattening
            val e2 = f * (2.0 - f)
            val ep2 = e2 / (1.0 - e2)
            val phi = Math.toRadians(lat)
            val lambda = Math.toRadians(lng)
            val lambda0 = Math.toRadians(zone * 6.0 - 183.0)
            val n = a / sqrt(1.0 - e2 * sin(phi).pow(2))
            val t = tan(phi).pow(2)
            val c = ep2 * cos(phi).pow(2)
            val aa = cos(phi) * (lambda - lambda0)
            val m = a * ((1 - e2 / 4 - 3 * e2.pow(2) / 64 - 5 * e2.pow(3) / 256) * phi
                - (3 * e2 / 8 + 3 * e2.pow(2) / 32 + 45 * e2.pow(3) / 1024) * sin(2 * phi)
                + (15 * e2.pow(2) / 256 + 45 * e2.pow(3) / 1024) * sin(4 * phi)
                - (35 * e2.pow(3) / 3072) * sin(6 * phi))
            val k0 = 0.9996
            val easting = 500_000.0 + k0 * n * (aa + (1 - t + c) * aa.pow(3) / 6
                + (5 - 18 * t + t.pow(2) + 72 * c - 58 * ep2) * aa.pow(5) / 120)
            var northing = k0 * (m + n * tan(phi) * (aa.pow(2) / 2
                + (5 - t + 9 * c + 4 * c.pow(2)) * aa.pow(4) / 24
                + (61 - 58 * t + t.pow(2) + 600 * c - 330 * ep2) * aa.pow(6) / 720))
            if (!northern) northing += 10_000_000.0
            return easting to northing
        }
    }

    private interface RandomAccessDataSource : Closeable {
        fun readFully(position: Long, length: Int): ByteArray
    }

    private class FileRandomAccessDataSource(file: File) : RandomAccessDataSource {
        private val raf = RandomAccessFile(file, "r")

        override fun readFully(position: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            raf.seek(position)
            raf.readFully(out)
            return out
        }

        override fun close() {
            raf.close()
        }
    }

    private class PfdRandomAccessDataSource(
        pfd: ParcelFileDescriptor
    ) : RandomAccessDataSource {
        private val parcel = pfd
        private val channel = ParcelFileDescriptor.AutoCloseInputStream(parcel).channel

        override fun readFully(position: Long, length: Int): ByteArray {
            val bb = ByteBuffer.allocate(length)
            var offset = 0
            while (offset < length) {
                val read = channel.read(bb, position + offset)
                if (read < 0) throw EOFException("short read at offset $offset of $length")
                // read == 0 is theoretically possible on a FileChannel; just retry
                offset += read
            }
            return bb.array()
        }

        override fun close() {
            channel.close()
        }
    }

}

package org.ncssar.rid2caltopo.video

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import org.ncssar.rid2caltopo.video.mapcache.GeoTiffDemSource
import org.ncssar.rid2caltopo.video.mapcache.S1mCog
import org.ncssar.rid2caltopo.video.mapcache.UnifiedMapCache
import java.io.IOException

private val s1mPieceMutex = kotlinx.coroutines.sync.Mutex()

internal suspend fun downloadS1mPieces(
    download: DemDownload, context: Context, client: OkHttpClient, service: DemElevationService,
    onProgress: (Long, Long?) -> Unit, onCall: (Call, Boolean) -> Unit,
): Boolean {
    s1mPieceMutex.lock()
    try {
        val bounds = download.bounds ?: throw IOException("S1M subset needs bounds")
        var etag: String? = null
        suspend fun readRange(offset: Long, length: Int): ByteArray {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(download.url).header("Range", "bytes=$offset-${offset + length - 1}")
                .header("Accept-Encoding", "identity")
            etag?.let { request.header("If-Match", it) }
            val call = client.newCall(request.build()); onCall(call, true)
            try {
                return call.execute().use { response ->
                    if (response.code != 206 || response.header("Content-Range")?.startsWith("bytes $offset-${offset + length - 1}/") != true)
                        throw IOException("S1M server did not honor byte range")
                    val version = response.header("ETag") ?: throw IOException("S1M response has no version")
                    if (etag != null && etag != version) throw IOException("S1M changed during download")
                    etag = version
                    val body = response.body ?: throw IOException("Empty S1M response")
                    if (body.contentLength() != length.toLong()) throw IOException("Invalid S1M range length")
                    body.byteStream().use { input ->
                        val bytes = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(bytes, read, length - read)
                            if (n <= 0) throw IOException("Truncated S1M block")
                            read += n
                        }
                        bytes
                    }
                }
            } finally { onCall(call, false) }
        }
        val header = readRange(0, 65536)
        val pieces = s1mPieces(header, bounds)
        val archive = CaltopoClient.GetArchiveDir() ?: return false
        val cache = archive.findFile("cache") ?: archive.createDirectory("cache") ?: return false
        val dir = cache.findFile("dem") ?: cache.createDirectory("dem") ?: return false
        val indexName = download.fileName + ".cog"
        UnifiedMapCache.touchTerrain(indexName)
        UnifiedMapCache.reserve(context, header.size.toLong()).use {
            val index = dir.findFile(indexName) ?: dir.createFile("application/octet-stream", indexName) ?: throw IOException("Cannot write S1M index")
            context.contentResolver.openOutputStream(index.uri, "wt")?.use { out -> out.write(header) } ?: throw IOException("Cannot write S1M index")
            synchronized(UnifiedMapCache.lock) { UnifiedMapCache.remember(index); it.close() }
        }
        val total = pieces.sumOf { it.bytes }; var completed = 0L
        for (piece in pieces) {
            val name = piece.name(download.fileName)
            val existing = dir.findFile(name)
            UnifiedMapCache.touchTerrain(name)
            if (existing?.length() == piece.bytes) { completed += piece.bytes; onProgress(completed, total); continue }
            UnifiedMapCache.reserve(context, piece.bytes).use {
                val tempName = "$name.${java.util.UUID.randomUUID()}.part"
                val temp = dir.createFile("application/octet-stream", tempName) ?: throw IOException("Cannot create terrain file")
                var published = false
                try {
                    context.contentResolver.openOutputStream(temp.uri, "wt")?.use { output ->
                        output.write(piece.header); var written = piece.header.size.toLong()
                        for (range in piece.ranges) {
                            output.write(readRange(range.offset, range.length)); written += range.length
                            onProgress(completed + written, total)
                        }
                    } ?: throw IOException("Cannot write terrain file")
                    synchronized(UnifiedMapCache.lock) {
                        if (existing != null) { if (!existing.delete()) throw IOException("Cannot replace terrain"); UnifiedMapCache.forget(existing) }
                        if (!temp.renameTo(name)) throw IOException("Cannot publish terrain")
                        UnifiedMapCache.remember(temp); it.close(); published = true
                    }
                    service.refreshGeoTiffCatalog()
                } finally { if (!published) { if (!temp.delete()) UnifiedMapCache.remember(temp) } }
            }
            completed += piece.bytes
        }
        return true
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        CaltopoClient.CTError("TerrainPrefetch", "S1M subset failed: ${e.message}")
        return false
    } finally { s1mPieceMutex.unlock() }
}


private fun s1mPieces(header: ByteArray, bounds: org.osmdroid.util.BoundingBox): List<S1mCog.Piece> {
    val model = listOf(bounds.latSouth, (bounds.latSouth + bounds.latNorth) / 2, bounds.latNorth).flatMap { lat ->
        listOf(bounds.lonWest, (bounds.lonWest + bounds.lonEast) / 2, bounds.lonEast).map { lon -> GeoTiffDemSource.latLonToConusAlbers(lat, lon) }
    }
    return S1mCog(header).pieces(model.minOf { it.first }, model.maxOf { it.first }, model.minOf { it.second }, model.maxOf { it.second })
}

internal fun hasCachedS1mPieces(download: DemDownload, dir: androidx.documentfile.provider.DocumentFile?, context: Context): Boolean = runCatching {
    val bounds = download.bounds ?: return false
    val index = dir?.findFile(download.fileName + ".cog") ?: return false
    if (index.length() != 65536L) return false
    val header = context.contentResolver.openInputStream(index.uri)?.use { it.readBytes() } ?: return false
    s1mPieces(header, bounds).all { piece -> dir.findFile(piece.name(download.fileName))?.length() == piece.bytes }
}.getOrDefault(false)

package org.ncssar.rid2caltopo.video.surface

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRoot
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRootResolver
import org.ncssar.rid2caltopo.video.mapcache.UnifiedMapCache
import java.io.File
import java.io.IOException

/** A cache entry may be on removable storage or an opaque document provider, not a local path. */
internal class SurfaceCacheFile private constructor(
    private val context: Context?, private val local: File?, private val base: DocumentFile?,
    private val components: List<String> = emptyList()
) {
    val name: String get() = local?.name ?: components.lastOrNull() ?: base?.name.orEmpty()
    val path: String get() = local?.absolutePath ?: "${base!!.uri}/${components.joinToString("/")}"
    private fun document(createDirectories: Boolean = false): DocumentFile? {
        var result = base
        for (name in components) result = result?.let { it.findFile(name) ?: if (createDirectories) it.createDirectory(name) else null }
        return result
    }
    fun child(name: String): SurfaceCacheFile {
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\'))
        return SurfaceCacheFile(context, local?.resolve(name), base, components + name)
    }
    fun exists() = local?.exists() ?: (document()?.exists() == true)
    val isDirectory: Boolean get() = local?.isDirectory ?: (document()?.isDirectory == true)
    fun length() = local?.length() ?: (document()?.length() ?: 0L)
    fun listFiles(): List<SurfaceCacheFile> = if (local != null) local.listFiles().orEmpty().map { child(it.name) }
        else document()?.listFiles().orEmpty().mapNotNull { it.name?.let(::child) }
    fun readBytes(): ByteArray {
        val max = SurfacePackage.MAX_BYTES.toLong()
        require(length() <= max) { "AOL cache entry too large" }
        val stream = local?.inputStream() ?: context!!.contentResolver.openInputStream(document()?.uri ?: throw IOException("AOL cache file missing"))
            ?: throw IOException("Cannot read AOL cache")
        return stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= max) { "AOL cache entry too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    fun readText() = String(readBytes(), Charsets.UTF_8)
    fun writeBytes(bytes: ByteArray) {
        if (local != null) {
            check(local.parentFile!!.isDirectory || local.parentFile!!.mkdirs())
            local.outputStream().use { it.write(bytes); it.fd.sync() }
        } else {
            val parent = SurfaceCacheFile(context, null, base, components.dropLast(1)).document(true)
                ?: throw IOException("Cannot create AOL cache directory")
            val doc = parent.findFile(name) ?: parent.createFile("application/octet-stream", name)
                ?: throw IOException("Cannot create AOL cache file")
            (context!!.contentResolver.openOutputStream(doc.uri, "wt") ?: throw IOException("Cannot write AOL cache")).use { it.write(bytes) }
        }
    }
    fun delete(): Boolean = local?.deleteRecursively() ?: (document()?.delete() ?: true)
    fun remember() {
        if (local != null) UnifiedMapCache.remember(local)
        else document()?.let { UnifiedMapCache.rememberSurface(it) }
    }
    companion object {
        fun local(file: File) = SurfaceCacheFile(null, file, null)
        fun root(context: Context): SurfaceCacheFile {
            // A configured but unavailable archive must not silently redirect prepared data internally.
            val archive = CaltopoClient.GetArchiveDir()
            if (CaltopoClient.GetArchiveUri() != null && (archive == null || !archive.isDirectory))
                throw IOException("Selected Archive Dir is unavailable")
            val root = MapCacheRootResolver.resolveRoot(context)
            if (CaltopoClient.GetArchiveUri() != null && root is MapCacheRoot.FileBacked &&
                root.dir == context.cacheDir.resolve("map_cache_fallback"))
                throw IOException("Cannot access Cache Dir in the selected Archive Dir")
            return when (root) {
                is MapCacheRoot.FileBacked -> SurfaceCacheFile(context, root.dir, null)
                is MapCacheRoot.SafBacked -> SurfaceCacheFile(context, null, root.dir)
            }.child("surface_v1")
        }
    }
}

internal object SurfaceCachePublication {
    /** Index is written last. Incomplete sets are never visible to readers. */
    fun publishSet(staged: File, target: SurfaceCacheFile) {
        val indexBytes = staged.resolve("index.json").readBytes()
        val index = org.json.JSONObject(String(indexBytes, Charsets.UTF_8))
        SurfacePreparedSet.validate(index) { staged.resolve(it).readBytes() }
        if (target.child("complete").exists()) {
            require(target.child("index.json").readBytes().contentEquals(indexBytes)) { "AOL set identifier conflict" }
            SurfacePreparedSet.validate(index) { target.child(it).readBytes() }
            target.child("complete").writeBytes(byteArrayOf(1))
            return
        }
        val entries = index.getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val name = entries.getJSONObject(i).getString("file")
            val bytes = staged.resolve(name).readBytes()
            target.child(name).writeBytes(bytes)
            require(target.child(name).readBytes().contentEquals(bytes)) { "AOL tile copy failed" }
        }
        SurfacePreparedSet.validate(index) { target.child(it).readBytes() }
        target.child("index.json").writeBytes(indexBytes)
        require(target.child("index.json").readBytes().contentEquals(indexBytes)) { "AOL index copy failed" }
        target.child("complete").writeBytes(byteArrayOf(1))
    }
}

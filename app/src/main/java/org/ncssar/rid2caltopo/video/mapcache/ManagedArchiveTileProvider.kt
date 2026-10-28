package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.graphics.drawable.Drawable
import org.ncssar.rid2caltopo.storage.OperationalStorage
import org.osmdroid.tileprovider.modules.ArchiveFileFactory
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Archive discovery, database opening and decoding all happen on the tile worker. */
internal class ManagedArchiveTileProvider(private val context: Context, source: ITileSource) : MapTileModuleProviderBase(2, 64) {
    private val source = AtomicReference(source)
    private val stopped = AtomicBoolean()
    private val diskGate = Any()
    private var archives: List<IArchiveFile>? = null
    override fun getUsesDataConnection() = false
    override fun getName() = "Managed map archives"
    override fun getThreadGroupName() = "r2cMapArchives"
    override fun getMinimumZoomLevel() = source.get().minimumZoomLevel
    override fun getMaximumZoomLevel() = source.get().maximumZoomLevel
    override fun setTileSource(tileSource: ITileSource) { source.set(tileSource) }
    override fun getTileLoader(): TileLoader = object : TileLoader() {
        override fun loadTile(index: Long): Drawable? = synchronized(diskGate) {
            if (stopped.get()) return@synchronized null
            val files = archives ?: run {
                OperationalStorage.requireReady(context)
                File(context.filesDir, "map-archives").walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("zip", "sqlite", "mbtiles", "gemf") }
                    .mapNotNull { ArchiveFileFactory.getArchiveFile(it) }.toList().also { archives = it }
            }
            val selected = source.get()
            for (archive in files) {
                if (stopped.get()) break
                val stream = archive.getInputStream(selected, index) ?: continue
                stream.use { return@synchronized selected.getDrawable(it) }
            }
            null
        }
    }
    override fun detach() {
        stopped.set(true)
        super.detach()
        Thread({ synchronized(diskGate) { archives?.forEach { runCatching { it.close() } }; archives = emptyList() } }, "map-archive-close").apply { isDaemon = true }.start()
    }
}

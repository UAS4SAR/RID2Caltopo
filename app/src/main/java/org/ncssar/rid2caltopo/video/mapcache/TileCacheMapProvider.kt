package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.graphics.drawable.Drawable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.MapTileRequestState
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileAssetsProvider
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileFileStorageProviderBase
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

open class TileCacheMapProvider private constructor(
    context: Context,
    tileSource: ITileSource,
    private val tileWriter: IFilesystemCache,
    private val registerReceiver: SimpleRegisterReceiver,
    private val networkAllowed: Boolean
) : MapTileProviderArray(tileSource, registerReceiver) {
    private companion object {
        private const val PROBE_MIN_INTERVAL_MS = 30_000L
        private val lastProbeByTile = LinkedHashMap<Long, Long>()
    }

    constructor(
        context: Context,
        tileSource: ITileSource = TileSourceFactory.DEFAULT_TILE_SOURCE,
        tileWriter: IFilesystemCache,
        networkAllowed: Boolean = true
    ) : this(context, tileSource, tileWriter, SimpleRegisterReceiver(context), networkAllowed)

    private val platformNetworkCheck = NetworkAvailabliltyCheck(context)
    private val networkAvailabilityCheck: INetworkAvailablityCheck = object : INetworkAvailablityCheck {
        override fun getNetworkAvailable(): Boolean = true
        override fun getWiFiNetworkAvailable(): Boolean = platformNetworkCheck.wiFiNetworkAvailable
        override fun getCellularDataNetworkAvailable(): Boolean = platformNetworkCheck.cellularDataNetworkAvailable
        override fun getRouteToPathExists(hostAddress: Int): Boolean = true
    }
    private val cacheProvider: TileCacheStorageProvider
    private val downloaderProvider: MapTileDownloader
    private val approximater: MapTileApproximater
    private val probeClient = OkHttpClient.Builder().build()

    init {
        val assetsProvider = MapTileAssetsProvider(
            registerReceiver,
            context.assets,
            tileSource
        )
        cacheProvider = TileCacheStorageProvider(registerReceiver, tileSource, tileWriter)
        val archiveProvider = MapTileFileArchiveProvider(registerReceiver, tileSource)

        mTileProviderList.add(assetsProvider)
        mTileProviderList.add(cacheProvider)
        mTileProviderList.add(archiveProvider)

        approximater = MapTileApproximater().apply {
            addProvider(assetsProvider)
            addProvider(cacheProvider)
            addProvider(archiveProvider)
        }
        mTileProviderList.add(approximater)

        downloaderProvider = MapTileDownloader(
            tileSource,
            tileWriter,
            networkAvailabilityCheck,
            Configuration.getInstance().tileDownloadThreads.toInt(),
            Configuration.getInstance().tileDownloadMaxQueueSize.toInt()
        )
        downloaderProvider.setTileDownloader(object : TileDownloader() {
            override fun downloadTile(
                pMapTileIndex: Long,
                redirectCount: Int,
                targetUrl: String,
                pFilesystemCache: IFilesystemCache?,
                pTileSource: org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
            ): Drawable? {
                val drawable = super.downloadTile(
                    pMapTileIndex,
                    redirectCount,
                    targetUrl,
                    pFilesystemCache,
                    pTileSource
                )
                if (MapCacheDebug.isDebugEnabled() && redirectCount == 0) {
                    val z = MapTileIndex.getZoom(pMapTileIndex)
                    val x = MapTileIndex.getX(pMapTileIndex)
                    val y = MapTileIndex.getY(pMapTileIndex)
                    if (drawable == null) {
                        MapCacheDebug.debug(
                            MapCacheDebug.TAG_TILE,
                            "tile net-null source=${pTileSource.name()} z=$z x=$x y=$y url=$targetUrl"
                        )
                    } else {
                        MapCacheDebug.debug(
                            MapCacheDebug.TAG_TILE,
                            "tile net-ok source=${pTileSource.name()} z=$z x=$x y=$y url=$targetUrl"
                        )
                    }
                }
                return drawable
            }
        })
        if (networkAllowed) mTileProviderList.add(downloaderProvider)
        else downloaderProvider.detach()

        // Keep tile activity strictly tied to current viewport to reduce background work.
        getTileCache().setAutoEnsureCapacity(false)
        getTileCache().setStressedMemory(false)

        // Avoid pre-cache provider registration to prevent speculative/background tile requests.
        getTileCache().protectedTileContainers.add(this)

        setOfflineFirst(true)
        setUseDataConnection(networkAllowed)
    }

    override fun setUseDataConnection(enabled: Boolean) {
        super.setUseDataConnection(enabled && networkAllowed)
    }

    override fun getTileWriter(): IFilesystemCache = tileWriter

    fun setCacheLookupEnabled(enabled: Boolean) {
        cacheProvider.setCacheLookupEnabled(enabled)
    }

    override fun mapTileRequestCompleted(pState: MapTileRequestState, pDrawable: Drawable?) {
        if (MapCacheDebug.isLudicrousEnabled()) {
            val idx = pState.mapTile
            MapCacheDebug.log(
                "tile req-complete z=${MapTileIndex.getZoom(idx)} x=${MapTileIndex.getX(idx)} y=${MapTileIndex.getY(idx)} drawable=${pDrawable != null}"
            )
        }
        super.mapTileRequestCompleted(pState, pDrawable)
    }

    override fun mapTileRequestFailed(pState: MapTileRequestState) {
        if (MapCacheDebug.isLudicrousEnabled()) {
            val idx = pState.mapTile
            val provider = pState.currentProvider?.javaClass?.simpleName ?: "unknown"
            val isDownloader = pState.currentProvider is MapTileDownloader
            MapCacheDebug.log(
                (if (isDownloader) "tile req-final-failed" else "tile req-stage-failed") +
                    " z=${MapTileIndex.getZoom(idx)} x=${MapTileIndex.getX(idx)} y=${MapTileIndex.getY(idx)} " +
                    "useData=${useDataConnection()} provider=$provider"
            )
            if (isDownloader) {
                probeFailedTile(idx)
            }
        }
        super.mapTileRequestFailed(pState)
    }

    override fun mapTileRequestFailedExceedsMaxQueueSize(pState: MapTileRequestState) {
        if (MapCacheDebug.isLudicrousEnabled()) {
            val idx = pState.mapTile
            val provider = pState.currentProvider?.javaClass?.simpleName ?: "unknown"
            MapCacheDebug.log(
                "tile req-queue-failed z=${MapTileIndex.getZoom(idx)} x=${MapTileIndex.getX(idx)} y=${MapTileIndex.getY(idx)} useData=${useDataConnection()} provider=$provider"
            )
            probeFailedTile(idx)
        }
        super.mapTileRequestFailedExceedsMaxQueueSize(pState)
    }

    override fun detach() {
        tileWriter.onDetach()
        super.detach()
    }

    private fun probeFailedTile(tileIndex: Long) {
        if (!networkAllowed) return
        val now = System.currentTimeMillis()
        synchronized(lastProbeByTile) {
            val prev = lastProbeByTile[tileIndex]
            if (prev != null && (now - prev) < PROBE_MIN_INTERVAL_MS) return
            lastProbeByTile[tileIndex] = now
            if (lastProbeByTile.size > 2048) {
                val iter = lastProbeByTile.entries.iterator()
                repeat(256) {
                    if (iter.hasNext()) {
                        iter.next()
                        iter.remove()
                    }
                }
            }
        }
        val source = tileSource as? org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase ?: return
        val url = source.getTileURLString(tileIndex)
        val z = MapTileIndex.getZoom(tileIndex)
        val x = MapTileIndex.getX(tileIndex)
        val y = MapTileIndex.getY(tileIndex)
        val uaHeader = Configuration.getInstance().userAgentHttpHeader
        val uaValue = Configuration.getInstance().userAgentValue
        thread(name = "tile-fail-probe", isDaemon = true) {
            try {
                val req = Request.Builder().url(url).header(uaHeader, uaValue).build()
                probeClient.newCall(req).execute().use { resp ->
                    MapCacheDebug.log(
                        "tile probe http=${resp.code} z=$z x=$x y=$y bytes=${resp.body?.contentLength() ?: -1} url=$url"
                    )
                }
            } catch (e: Exception) {
                MapCacheDebug.log(
                    "tile probe ex=${e.javaClass.simpleName}:${e.message} z=$z x=$x y=$y url=$url"
                )
            }
        }
    }

    override fun isDowngradedMode(pMapTileIndex: Long): Boolean {
        if (!networkAvailabilityCheck.networkAvailable || !useDataConnection()) {
            return true
        }
        var zoomMin = -1
        var zoomMax = -1
        for (provider in mTileProviderList) {
            if (provider.usesDataConnection) {
                val min = provider.minimumZoomLevel
                val max = provider.maximumZoomLevel
                zoomMin = if (zoomMin == -1 || zoomMin > min) min else zoomMin
                zoomMax = if (zoomMax == -1 || zoomMax < max) max else zoomMax
            }
        }
        if (zoomMin == -1 || zoomMax == -1) return true
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        return zoom < zoomMin || zoom > zoomMax
    }

    fun setOfflineFirst(offlineFirst: Boolean): Boolean {
        var downloaderIndex = -1
        var approximationIndex = -1
        mTileProviderList.forEachIndexed { index, provider ->
            if (provider === downloaderProvider) downloaderIndex = index
            if (provider === approximater) approximationIndex = index
        }
        if (downloaderIndex == -1 || approximationIndex == -1) return false
        if (approximationIndex < downloaderIndex && offlineFirst) return true
        if (approximationIndex > downloaderIndex && !offlineFirst) return true
        mTileProviderList[downloaderIndex] = approximater
        mTileProviderList[approximationIndex] = downloaderProvider
        return true
    }
}

private class TileCacheStorageProvider(
    pRegisterReceiver: IRegisterReceiver,
    pTileSource: ITileSource,
    private val tileWriter: IFilesystemCache
) : MapTileFileStorageProviderBase(
    pRegisterReceiver,
    Configuration.getInstance().tileFileSystemThreads.toInt(),
    Configuration.getInstance().tileFileSystemMaxQueueSize.toInt()
) {
    private val tileSourceRef = AtomicReference(pTileSource)
    @Volatile
    private var cacheLookupEnabled = true
    private val tileLoader = LocalTileLoader()

    override fun getUsesDataConnection(): Boolean = false

    override fun getName(): String = "R2C Tile Cache Provider"

    override fun getThreadGroupName(): String = "r2cTileCache"

    override fun getTileLoader(): TileLoader = tileLoader

    override fun getMinimumZoomLevel(): Int =
        tileSourceRef.get()?.minimumZoomLevel ?: org.osmdroid.tileprovider.constants.OpenStreetMapTileProviderConstants.MINIMUM_ZOOMLEVEL

    override fun getMaximumZoomLevel(): Int =
        tileSourceRef.get()?.maximumZoomLevel ?: org.osmdroid.util.TileSystem.getMaximumZoomLevel()

    override fun setTileSource(tileSource: ITileSource) {
        MapCacheDebug.log("tile source switch ${tileSourceRef.get()?.name()} -> ${tileSource.name()}")
        tileSourceRef.set(tileSource)
    }

    fun setCacheLookupEnabled(enabled: Boolean) {
        cacheLookupEnabled = enabled
    }

    inner class LocalTileLoader : TileLoader() {
        override fun loadTile(pMapTileIndex: Long): Drawable? {
            if (!cacheLookupEnabled) return null
            val source = tileSourceRef.get() ?: return null
            return tileWriter.loadTile(source, pMapTileIndex)
        }

        override fun tileLoaded(state: MapTileRequestState, drawable: Drawable) {
            removeTileFromQueues(state.mapTile)
            state.callback.mapTileRequestCompleted(state, drawable)
        }
    }
}

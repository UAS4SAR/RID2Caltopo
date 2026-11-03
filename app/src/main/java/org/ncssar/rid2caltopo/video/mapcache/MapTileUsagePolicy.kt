package org.ncssar.rid2caltopo.video.mapcache

/** Public OSM/Esri basemaps are interactive-only. USGS/USFS government topo tiles may be cached offline. */
internal object MapTileUsagePolicy {
    const val MINIMUM_CACHE_AGE_DAYS = 7L
    /** Conservative default for OSM/Esri and other suppliers that misbehave under parallel fetch. */
    const val DEFAULT_FETCH_CONCURRENCY = 1
    /**
     * Bounded parallel fetch for USGS-hosted Scanned Topo and USFS Topo only.
     * Field trials showed serial ~1–2s/tile fill on Android; these servers tolerate modest concurrency.
     */
    const val USGS_HOSTED_FETCH_CONCURRENCY = 5
    /**
     * Low-priority offline pool must cover both parallel USGS/USFS sources plus an optional
     * contours worker when Download Map selects everything allowed offline.
     */
    const val OFFLINE_FETCH_THREAD_POOL = USGS_HOSTED_FETCH_CONCURRENCY * 2 + 1

    private val offlineHosts = setOf(
        "carto.nationalmap.gov",
        "historical1.arcgis.com",
        "basemap.nationalmap.gov"
    )

    private fun offlineSourceIds(): Set<String> =
        MapTileSourceRegistry.offlineAllowedSources.map { it.id }.toSet()

    private fun parallelFetchSourceIds(): Set<String> =
        MapTileSourceRegistry.offlineAllowedBaseSources
            .filter { it.fetchConcurrency > DEFAULT_FETCH_CONCURRENCY }
            .map { it.id }
            .toSet()

    fun cacheAgeDays(configured: Long): Long = configured.coerceIn(MINIMUM_CACHE_AGE_DAYS, 3650L)
    fun allowsPackageExport(sourceName: String): Boolean = sourceName in offlineSourceIds()
    fun allowsOfflineDownload(sourceName: String, url: String): Boolean =
        sourceName in offlineSourceIds() && runCatching {
            val uri = java.net.URI(url)
            uri.scheme == "https" && uri.host in offlineHosts
        }.getOrDefault(false)

    /** Live viewport / priority prefetch concurrency for a tile source name. */
    fun liveFetchConcurrency(sourceName: String): Int =
        MapTileSourceRegistry.descriptor(sourceName)?.fetchConcurrency
            ?: if (sourceName in parallelFetchSourceIds()) USGS_HOSTED_FETCH_CONCURRENCY else DEFAULT_FETCH_CONCURRENCY

    /** Offline download concurrency for a tile source name. */
    fun offlineFetchConcurrency(sourceName: String): Int = liveFetchConcurrency(sourceName)
}

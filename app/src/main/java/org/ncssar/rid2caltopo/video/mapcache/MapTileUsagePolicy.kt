package org.ncssar.rid2caltopo.video.mapcache

/** Public OSM/Esri basemaps are interactive-only. USGS/USFS government topo tiles may be cached offline. */
internal object MapTileUsagePolicy {
    const val MINIMUM_CACHE_AGE_DAYS = 7L
    private val offlineSources = setOf(
        "USGS-Contours",
        "USGS-ScannedTopo",
        "USFS-Topo"
    )
    private val offlineHosts = setOf(
        "carto.nationalmap.gov",
        "historical1.arcgis.com",
        "basemap.nationalmap.gov"
    )

    fun cacheAgeDays(configured: Long): Long = configured.coerceIn(MINIMUM_CACHE_AGE_DAYS, 3650L)
    fun allowsPackageExport(sourceName: String): Boolean = sourceName in offlineSources
    fun allowsOfflineDownload(sourceName: String, url: String): Boolean =
        sourceName in offlineSources && runCatching {
            val uri = java.net.URI(url)
            uri.scheme == "https" && uri.host in offlineHosts
        }.getOrDefault(false)
}

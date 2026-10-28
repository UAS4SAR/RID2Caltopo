package org.ncssar.rid2caltopo.video.mapcache

/** Public basemaps are interactive-only. Add offline providers only with a covering grant. */
internal object MapTileUsagePolicy {
    const val MINIMUM_CACHE_AGE_DAYS = 7L
    fun cacheAgeDays(configured: Long): Long = configured.coerceIn(MINIMUM_CACHE_AGE_DAYS, 3650L)
    fun allowsPackageExport(sourceName: String): Boolean = sourceName == "USGS-Contours"
    fun allowsOfflineDownload(sourceName: String, url: String): Boolean =
        sourceName == "USGS-Contours" && runCatching {
            val uri = java.net.URI(url)
            uri.scheme == "https" && uri.host == "carto.nationalmap.gov"
        }.getOrDefault(false)
}

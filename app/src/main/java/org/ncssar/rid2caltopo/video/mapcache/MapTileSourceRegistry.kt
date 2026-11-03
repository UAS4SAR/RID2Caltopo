package org.ncssar.rid2caltopo.video.mapcache

/**
 * Single source of truth for map tile suppliers on Android.
 * Base-layer picker, Download Map checkboxes, offline/export policy, and fetch concurrency
 * all derive from these descriptors. IDs must stay in sync with Apple's
 * OperationalMapTileSourceRegistry.
 */
internal data class MapTileSourceDescriptor(
    val id: String,
    val label: String,
    val host: String,
    val format: String,
    val minZoom: Int,
    val maxZoom: Int,
    val offlineAllowed: Boolean,
    val attribution: String,
    val fetchConcurrency: Int,
    /** Older cache key source-names that still hold tiles for this supplier. */
    val legacyCacheNames: List<String> = emptyList(),
)

internal object MapTileSourceRegistry {
    val all: List<MapTileSourceDescriptor> = listOf(
        MapTileSourceDescriptor(
            id = "OSM-Standard",
            label = "OpenStreetMap",
            host = "tile.openstreetmap.org",
            format = ".png",
            minZoom = 0,
            maxZoom = 19,
            offlineAllowed = false,
            attribution = "© OpenStreetMap contributors",
            fetchConcurrency = MapTileUsagePolicy.DEFAULT_FETCH_CONCURRENCY,
            legacyCacheNames = listOf("Mapnik", "MAPNIK"),
        ),
        MapTileSourceDescriptor(
            id = "ArcGIS-WorldImagery",
            label = "Imagery",
            host = "services.arcgisonline.com",
            format = ".jpg",
            minZoom = 0,
            maxZoom = 19,
            offlineAllowed = false,
            attribution = "Tiles © Esri",
            fetchConcurrency = MapTileUsagePolicy.DEFAULT_FETCH_CONCURRENCY,
        ),
        MapTileSourceDescriptor(
            id = "USGS-ScannedTopo",
            label = "USGS Scanned Topo",
            host = "historical1.arcgis.com",
            format = ".png",
            minZoom = 0,
            maxZoom = 16,
            offlineAllowed = true,
            attribution = "USGS Historical Topographic Map Collection",
            fetchConcurrency = MapTileUsagePolicy.USGS_HOSTED_FETCH_CONCURRENCY,
        ),
        MapTileSourceDescriptor(
            id = "USFS-Topo",
            label = "USFS Topo",
            host = "basemap.nationalmap.gov",
            format = ".jpg",
            minZoom = 0,
            maxZoom = 16,
            offlineAllowed = true,
            attribution = "USGS The National Map (USGSTopo)",
            fetchConcurrency = MapTileUsagePolicy.USGS_HOSTED_FETCH_CONCURRENCY,
            // Earlier builds cached the same National Map USGSTopo tiles as "USGS-Topo".
            legacyCacheNames = listOf("USGS-Topo"),
        ),
        MapTileSourceDescriptor(
            id = "USGS-Contours",
            label = "Contours",
            host = "carto.nationalmap.gov",
            format = ".png",
            minZoom = 0,
            maxZoom = 22,
            offlineAllowed = true,
            attribution = "Contours: USGS",
            fetchConcurrency = MapTileUsagePolicy.DEFAULT_FETCH_CONCURRENCY,
        ),
    )

    val byId: Map<String, MapTileSourceDescriptor> = all.associateBy { it.id }

    /** Offline-allowed base map sources shown as Download Map checkboxes (not Contours). */
    val offlineAllowedBaseSources: List<MapTileSourceDescriptor> =
        all.filter { it.offlineAllowed && it.id != "USGS-Contours" }

    val offlineAllowedSources: List<MapTileSourceDescriptor> = all.filter { it.offlineAllowed }

    fun descriptor(id: String): MapTileSourceDescriptor? = byId[id]

    fun legacyCacheNames(sourceId: String): List<String> =
        byId[sourceId]?.legacyCacheNames.orEmpty()

    /** Clamp a requested zoom range into a source's native range; empty when inverted after clamp. */
    fun clampZoomRange(sourceId: String, minZoom: Int, maxZoom: Int): IntRange {
        val source = byId[sourceId] ?: return IntRange.EMPTY
        val lo = minZoom.coerceIn(source.minZoom, source.maxZoom)
        val hi = maxZoom.coerceIn(source.minZoom, source.maxZoom)
        return if (lo <= hi) lo..hi else IntRange.EMPTY
    }

    /** Canonical IDs shared with Apple OperationalMapTileSourceRegistry (sorted). */
    val crossPlatformIds: List<String> = all.map { it.id }.sorted()
}

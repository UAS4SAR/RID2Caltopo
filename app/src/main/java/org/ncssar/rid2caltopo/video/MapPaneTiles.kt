package org.ncssar.rid2caltopo.video

import okhttp3.OkHttpClient
import okhttp3.Request
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.video.mapcache.MapCacheDebug
import org.ncssar.rid2caltopo.video.mapcache.TileDiskCacheWriter
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.util.Locale

// Tile source objects
internal object ArcGisWorldImageryTileSource : OnlineTileSourceBase(
    "ArcGIS-WorldImagery",
    0,
    19,
    256,
    ".jpg",
    arrayOf("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$y/$x${imageFilenameEnding()}"
    }
}

internal object OsmStandardTileSource : OnlineTileSourceBase(
    "OSM-Standard",
    0,
    19,
    256,
    ".png",
    arrayOf("https://tile.openstreetmap.org/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "$baseUrl$zoom/$x/$y${imageFilenameEnding()}"
    }
}

internal object UsgsContoursTileSource : OnlineTileSourceBase(
    "USGS-Contours",
    0,
    MAP_DISPLAY_MAX_ZOOM.toInt(),
    256,
    ".png",
    arrayOf("https://carto.nationalmap.gov/arcgis/rest/services/contours/MapServer/export")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val tilesPerSide = 1 shl zoom
        val span = (WEB_MERCATOR_HALF_WORLD_METERS * 2.0) / tilesPerSide.toDouble()
        val minX = -WEB_MERCATOR_HALF_WORLD_METERS + (x * span)
        val maxX = minX + span
        val maxY = WEB_MERCATOR_HALF_WORLD_METERS - (y * span)
        val minY = maxY - span
        val bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", minX, minY, maxX, maxY)
        return "$baseUrl?bbox=$bbox&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true&f=image"
    }
}

internal fun osmUserAgent(): String =
    "RID2Caltopo v${BuildConfig.VERSION_NAME} (contact: kjt@uas4sar.com)"

internal fun buildOfflineTileRequest(
    tileSource: OnlineTileSourceBase,
    url: String
): Request {
    val builder = Request.Builder().url(url)
    if (tileSource.name() == OsmStandardTileSource.name()) {
        val cfg = Configuration.getInstance()
        builder.header(cfg.userAgentHttpHeader, cfg.userAgentValue)
    }
    return builder.build()
}

internal fun tileSourceForBaseLayer(baseLayer: BaseLayerOption): OnlineTileSourceBase =
    when (baseLayer) {
        BaseLayerOption.OpenStreetMap -> OsmStandardTileSource
        BaseLayerOption.Imagery -> ArcGisWorldImageryTileSource
    }

internal fun offlinePrepTileSources(
    baseLayer: BaseLayerOption,
    includeContours: Boolean
): List<OnlineTileSourceBase> {
    val sources = mutableListOf<OnlineTileSourceBase>(tileSourceForBaseLayer(baseLayer))
    if (includeContours) sources += UsgsContoursTileSource
    return sources
}

internal fun offlinePrepTileOperationCount(baseTileCount: Int, includeContours: Boolean): Int {
    val sourceCount = if (includeContours) 2 else 1
    return (baseTileCount.toLong() * sourceCount.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun needsBaseTileProviderRestart(
    currentSourceName: String?,
    desiredTileSource: ITileSource
): Boolean =
    currentSourceName != desiredTileSource.name()

internal fun needsViewportTileProviderRestart(
    previousTileZoom: Int,
    currentTileZoom: Int,
    baseSourceChanged: Boolean
): Boolean =
    false

internal fun visibleTileNetworkActive(suppressLiveMapNetwork: Boolean): Boolean =
    !suppressLiveMapNetwork

internal fun offlineFirstForVisibleTiles(tileNetworkActive: Boolean): Boolean =
    !tileNetworkActive

internal fun prefetchMapTileIfMissing(
    tileSource: ITileSource,
    tileIndex: Long,
    tileWriter: TileDiskCacheWriter,
    httpClient: OkHttpClient,
    reason: String = "prefetch"
): Boolean {
    val onlineTileSource = tileSource as? OnlineTileSourceBase ?: return false
    val z = MapTileIndex.getZoom(tileIndex)
    val x = MapTileIndex.getX(tileIndex)
    val y = MapTileIndex.getY(tileIndex)
    if (tileWriter.exists(tileSource, tileIndex)) {
        MapCacheDebug.debug(
            MapCacheDebug.TAG_TILE,
            "$reason tile hit source=${tileSource.name()} z=$z x=$x y=$y"
        )
        return true
    }
    try {
        MapCacheDebug.debug(
            MapCacheDebug.TAG_TILE,
            "$reason tile miss source=${tileSource.name()} z=$z x=$x y=$y"
        )
        val url = onlineTileSource.getTileURLString(tileIndex)
        val request = buildOfflineTileRequest(onlineTileSource, url)
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                MapCacheDebug.debug(
                    MapCacheDebug.TAG_TILE,
                    "$reason tile fetch-http source=${tileSource.name()} z=$z x=$x y=$y http=${response.code}"
                )
                return false
            }
            val body = response.body
            if (body == null) {
                MapCacheDebug.debug(
                    MapCacheDebug.TAG_TILE,
                    "$reason tile fetch-empty source=${tileSource.name()} z=$z x=$x y=$y"
                )
                return false
            }
            val bytes = body.bytes()
            val saved = tileWriter.saveFile(tileSource, tileIndex, ByteArrayInputStream(bytes), null)
            MapCacheDebug.debug(
                MapCacheDebug.TAG_TILE,
                "$reason tile fetch-ok source=${tileSource.name()} z=$z x=$x y=$y bytes=${bytes.size} saved=$saved"
            )
            return saved
        }
    } catch (e: Exception) {
        if (MapCacheDebug.isDebugEnabled()) {
            MapCacheDebug.debug(
                MapCacheDebug.TAG_TILE,
                "$reason tile fetch-failed source=${tileSource.name()} z=$z x=$x y=$y err=${e.javaClass.simpleName}:${e.message}"
            )
        }
    }
    return false
}

private fun estimateTileCountForBounds(
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    clipBoundary: GeoBoundary? = null
): Int {
    val north = bounds.latNorth.coerceIn(-85.05112878, 85.05112878)
    val south = bounds.latSouth.coerceIn(-85.05112878, 85.05112878)
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    var total = 0L
    for (z in minZoom..maxZoom) {
        val maxTile = (1 shl z) - 1
        val minX = lonToTileX(loLon, z).coerceIn(0, maxTile)
        val maxX = lonToTileX(hiLon, z).coerceIn(0, maxTile)
        val minY = latToTileY(hiLat, z).coerceIn(0, maxTile)
        val maxY = latToTileY(loLat, z).coerceIn(0, maxTile)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                if (tileIndexInsideBoundary(z, x, y, clipBoundary)) {
                    total++
                }
            }
        }
    }
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun estimateTileCountApproximate(
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    clipBoundary: GeoBoundary? = null
): Int {
    val north = bounds.latNorth.coerceIn(-85.05112878, 85.05112878)
    val south = bounds.latSouth.coerceIn(-85.05112878, 85.05112878)
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)

    var total = 0.0
    for (z in minZoom..maxZoom) {
        val maxTile = (1 shl z) - 1
        val minX = lonToTileX(loLon, z).coerceIn(0, maxTile)
        val maxX = lonToTileX(hiLon, z).coerceIn(0, maxTile)
        val minY = latToTileY(hiLat, z).coerceIn(0, maxTile)
        val maxY = latToTileY(loLat, z).coerceIn(0, maxTile)
        val xCount = (maxX - minX + 1).coerceAtLeast(0)
        val yCount = (maxY - minY + 1).coerceAtLeast(0)
        total += xCount.toDouble() * yCount.toDouble()
    }
    val coverage = boundaryCoverageRatio(bounds, clipBoundary)
    return kotlin.math.max(1, kotlin.math.round(total * coverage).toInt())
}

internal suspend fun forEachTileIndexForBounds(
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    clipBoundary: GeoBoundary? = null,
    block: suspend (Long) -> Unit
) {
    val north = bounds.latNorth.coerceIn(-85.05112878, 85.05112878)
    val south = bounds.latSouth.coerceIn(-85.05112878, 85.05112878)
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    for (z in minZoom..maxZoom) {
        val maxTile = (1 shl z) - 1
        val minX = lonToTileX(loLon, z).coerceIn(0, maxTile)
        val maxX = lonToTileX(hiLon, z).coerceIn(0, maxTile)
        val minY = latToTileY(hiLat, z).coerceIn(0, maxTile)
        val maxY = latToTileY(loLat, z).coerceIn(0, maxTile)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                if (!tileIndexInsideBoundary(z, x, y, clipBoundary)) continue
                block(MapTileIndex.getTileIndex(z, x, y))
            }
        }
    }
}

internal fun orderedTileIndexesForOfflinePrep(
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    clipBoundary: GeoBoundary? = null,
    tabletLocation: GeoPoint? = null,
    dronePathPoints: List<GeoPoint> = emptyList()
): List<Long> {
    val allTileIndexes = collectTileIndexesForBounds(bounds, minZoom, maxZoom, clipBoundary)
    if (allTileIndexes.size <= 1 || minZoom > maxZoom) return allTileIndexes

    val available = allTileIndexes.toHashSet()
    val ordered = ArrayList<Long>(allTileIndexes.size)
    val added = HashSet<Long>()

    fun addIfAvailable(tileIndex: Long) {
        if (tileIndex in available && added.add(tileIndex)) {
            ordered += tileIndex
        }
    }

    val mediumZoom = ((minZoom + maxZoom) / 2).coerceIn(minZoom, maxZoom)
    tabletLocation?.let { point ->
        tileIndexForPoint(point, mediumZoom)?.let(::addIfAvailable)
    }
    dronePathTileIndexes(dronePathPoints, mediumZoom).forEach(::addIfAvailable)

    for (zoom in minZoom..maxZoom) {
        if (zoom == mediumZoom) continue
        tabletLocation?.let { point ->
            tileIndexForPoint(point, zoom)?.let(::addIfAvailable)
        }
    }
    for (zoom in minZoom..maxZoom) {
        if (zoom == mediumZoom) continue
        dronePathTileIndexes(dronePathPoints, zoom).forEach(::addIfAvailable)
    }

    for (tileIndex in allTileIndexes) {
        if (added.add(tileIndex)) ordered += tileIndex
    }
    return ordered
}

internal fun liveTilePriorityRequests(
    tabletLocation: GeoPoint?,
    dronePoints: List<TilePriorityPoint>,
    visibleZoom: Int
): List<LiveTileRequest> {
    val tileZoom = visibleZoom.coerceIn(0, OSM_MAX_ZOOM.toInt())
    val requests = ArrayList<LiveTileRequest>()
    tabletLocation?.let { location ->
        tileIndexForPoint(location, tileZoom)?.let { tileIndex ->
            requests += LiveTileRequest(
                tileIndex = tileIndex,
                currentTileIndex = tileIndex,
                requiresCurrentCached = false
            )
        }
    }
    requests += droneTilePriorityRequests(dronePoints, tileZoom, existingTileIndexes = requests.map { it.tileIndex }.toSet())
    return requests
}

internal fun droneTilePriorityRequests(
    dronePoints: List<TilePriorityPoint>,
    zoom: Int,
    existingTileIndexes: Set<Long> = emptySet()
): List<LiveTileRequest> {
    val currentRequests = ArrayList<LiveTileRequest>()
    val headingRequests = ArrayList<LiveTileRequest>()
    val addedCurrent = existingTileIndexes.toHashSet()
    val addedHeading = HashSet<Long>()
    val currentTileByPoint = LinkedHashMap<TilePriorityPoint, Long>()

    for (point in dronePoints) {
        val location = GeoPoint(point.lat, point.lng)
        val currentTileIndex = tileIndexForPoint(location, zoom) ?: continue
        currentTileByPoint[point] = currentTileIndex
        if (addedCurrent.add(currentTileIndex)) {
            currentRequests += LiveTileRequest(
                tileIndex = currentTileIndex,
                currentTileIndex = currentTileIndex,
                requiresCurrentCached = false
            )
        }
    }

    for ((point, currentTileIndex) in currentTileByPoint) {
        val location = GeoPoint(point.lat, point.lng)
        val headingTileIndex = nextTileIndexForHeading(location, zoom, point.headingDeg)
        if (headingTileIndex != null &&
            headingTileIndex != currentTileIndex &&
            headingTileIndex !in addedCurrent &&
            addedHeading.add(headingTileIndex)
        ) {
            headingRequests += LiveTileRequest(
                tileIndex = headingTileIndex,
                currentTileIndex = currentTileIndex,
                requiresCurrentCached = true
            )
        }
    }
    return currentRequests + headingRequests
}

private fun collectTileIndexesForBounds(
    bounds: BoundingBox,
    minZoom: Int,
    maxZoom: Int,
    clipBoundary: GeoBoundary? = null
): List<Long> {
    if (minZoom > maxZoom) return emptyList()
    val north = bounds.latNorth.coerceIn(-85.05112878, 85.05112878)
    val south = bounds.latSouth.coerceIn(-85.05112878, 85.05112878)
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    val tileIndexes = ArrayList<Long>()
    for (z in minZoom..maxZoom) {
        val maxTile = (1 shl z) - 1
        val minX = lonToTileX(loLon, z).coerceIn(0, maxTile)
        val maxX = lonToTileX(hiLon, z).coerceIn(0, maxTile)
        val minY = latToTileY(hiLat, z).coerceIn(0, maxTile)
        val maxY = latToTileY(loLat, z).coerceIn(0, maxTile)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                if (!tileIndexInsideBoundary(z, x, y, clipBoundary)) continue
                tileIndexes += MapTileIndex.getTileIndex(z, x, y)
            }
        }
    }
    return tileIndexes
}

private fun tileIndexForPoint(point: GeoPoint, zoom: Int): Long? {
    if (!point.latitude.isFinite() || !point.longitude.isFinite()) return null
    val maxTile = (1 shl zoom) - 1
    val x = lonToTileX(point.longitude, zoom).coerceIn(0, maxTile)
    val y = latToTileY(point.latitude.coerceIn(-85.05112878, 85.05112878), zoom).coerceIn(0, maxTile)
    return MapTileIndex.getTileIndex(zoom, x, y)
}

private fun nextTileIndexForHeading(point: GeoPoint, zoom: Int, headingDeg: Double?): Long? {
    val heading = headingDeg?.takeIf { it.isFinite() } ?: return null
    val maxTile = (1 shl zoom) - 1
    val currentX = lonToTileX(point.longitude, zoom).coerceIn(0, maxTile)
    val currentY = latToTileY(point.latitude.coerceIn(-85.05112878, 85.05112878), zoom).coerceIn(0, maxTile)
    val normalized = ((heading % 360.0) + 360.0) % 360.0
    val radians = Math.toRadians(normalized)
    val dx = kotlin.math.round(kotlin.math.sin(radians)).toInt()
    val dy = kotlin.math.round(-kotlin.math.cos(radians)).toInt()
    if (dx == 0 && dy == 0) return null
    val nextX = (currentX + dx).coerceIn(0, maxTile)
    val nextY = (currentY + dy).coerceIn(0, maxTile)
    if (nextX == currentX && nextY == currentY) return null
    return MapTileIndex.getTileIndex(zoom, nextX, nextY)
}

private fun dronePathTileIndexes(points: List<GeoPoint>, zoom: Int): List<Long> {
    if (points.isEmpty()) return emptyList()
    val tileCoordinates = points.mapNotNull { point ->
        if (!point.latitude.isFinite() || !point.longitude.isFinite()) {
            null
        } else {
            val maxTile = (1 shl zoom) - 1
            lonToTileX(point.longitude, zoom).coerceIn(0, maxTile) to
                latToTileY(point.latitude.coerceIn(-85.05112878, 85.05112878), zoom).coerceIn(0, maxTile)
        }
    }
    if (tileCoordinates.isEmpty()) return emptyList()

    val tileIndexes = LinkedHashSet<Long>()
    var previous: Pair<Int, Int>? = null
    for (coordinate in tileCoordinates) {
        val line = previous?.let { tileLineBetween(it, coordinate) } ?: listOf(coordinate)
        for ((x, y) in line) {
            tileIndexes += MapTileIndex.getTileIndex(zoom, x, y)
        }
        previous = coordinate
    }
    return tileIndexes.toList()
}

private fun tileLineBetween(start: Pair<Int, Int>, end: Pair<Int, Int>): List<Pair<Int, Int>> {
    val points = ArrayList<Pair<Int, Int>>()
    var x = start.first
    var y = start.second
    val endX = end.first
    val endY = end.second
    val dx = kotlin.math.abs(endX - x)
    val dy = kotlin.math.abs(endY - y)
    val sx = if (x < endX) 1 else -1
    val sy = if (y < endY) 1 else -1
    var err = dx - dy
    while (true) {
        points += x to y
        if (x == endX && y == endY) break
        val e2 = 2 * err
        if (e2 > -dy) {
            err -= dy
            x += sx
        }
        if (e2 < dx) {
            err += dx
            y += sy
        }
    }
    return points
}

internal fun lonToTileX(lon: Double, zoom: Int): Int {
    val n = 1 shl zoom
    val x = ((lon + 180.0) / 360.0 * n.toDouble())
    return kotlin.math.floor(x).toInt()
}

internal fun latToTileY(lat: Double, zoom: Int): Int {
    val latRad = Math.toRadians(lat)
    val n = 1 shl zoom
    val y = (1.0 - kotlin.math.ln(kotlin.math.tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / Math.PI) / 2.0 * n.toDouble()
    return kotlin.math.floor(y).toInt()
}

private fun tileXToLon(x: Int, zoom: Int): Double {
    val n = 1 shl zoom
    return x.toDouble() / n.toDouble() * 360.0 - 180.0
}

private fun tileYToLat(y: Int, zoom: Int): Double {
    val n = 1 shl zoom
    val m = Math.PI * (1.0 - 2.0 * y.toDouble() / n.toDouble())
    return Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(m)))
}

private fun tileIndexInsideBoundary(
    zoom: Int,
    x: Int,
    y: Int,
    clipBoundary: GeoBoundary?
): Boolean {
    val boundary = clipBoundary ?: return true
    val west = tileXToLon(x, zoom)
    val east = tileXToLon(x + 1, zoom)
    val north = tileYToLat(y, zoom)
    val south = tileYToLat(y + 1, zoom)

    if (east < boundary.bounds.lonWest || west > boundary.bounds.lonEast) return false
    if (north < boundary.bounds.latSouth || south > boundary.bounds.latNorth) return false

    val centerLat = (north + south) * 0.5
    val centerLon = (west + east) * 0.5
    if (pointInPolygon(centerLat, centerLon, boundary.ring)) return true

    val corners = arrayOf(
        doubleArrayOf(north, west),
        doubleArrayOf(north, east),
        doubleArrayOf(south, east),
        doubleArrayOf(south, west)
    )
    for (corner in corners) {
        if (pointInPolygon(corner[0], corner[1], boundary.ring)) return true
    }

    boundary.ring.forEach { point ->
        if (point.latitude in south..north && point.longitude in west..east) return true
    }
    return false
}

internal fun pointInPolygon(lat: Double, lon: Double, ring: List<GeoPoint>): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var j = ring.lastIndex
    for (i in ring.indices) {
        val yi = ring[i].latitude
        val xi = ring[i].longitude
        val yj = ring[j].latitude
        val xj = ring[j].longitude
        val denom = (yj - yi).takeIf { kotlin.math.abs(it) > 1e-12 } ?: 1e-12
        val intersects = ((yi > lat) != (yj > lat)) &&
            (lon < (xj - xi) * (lat - yi) / denom + xi)
        if (intersects) inside = !inside
        j = i
    }
    return inside
}

private fun boundaryCoverageRatio(bounds: BoundingBox, clipBoundary: GeoBoundary?): Double {
    if (clipBoundary == null) return 1.0
    val bboxArea = boundsAreaMeters2(bounds)
    if (bboxArea <= 1.0) return 1.0
    val polygonArea = polygonAreaMeters2(clipBoundary.ring)
    if (polygonArea <= 0.0) return 1.0
    return (polygonArea / bboxArea).coerceIn(0.02, 1.0)
}

internal fun boundsAreaMeters2(bounds: BoundingBox): Double {
    val north = bounds.latNorth
    val south = bounds.latSouth
    val west = bounds.lonWest
    val east = bounds.lonEast
    val latCenter = ((north + south) * 0.5)
    val latMetersPerDeg = 111_320.0
    val lonMetersPerDeg = 111_320.0 * kotlin.math.cos(Math.toRadians(latCenter)).coerceAtLeast(0.1)
    val widthM = kotlin.math.abs(east - west) * lonMetersPerDeg
    val heightM = kotlin.math.abs(north - south) * latMetersPerDeg
    return widthM * heightM
}

internal fun polygonAreaMeters2(ring: List<GeoPoint>): Double {
    if (ring.size < 3) return 0.0
    val centerLat = ring.map { it.latitude }.average()
    val centerLon = ring.map { it.longitude }.average()
    val latMetersPerDeg = 111_320.0
    val lonMetersPerDeg = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
    var twiceArea = 0.0
    for (i in ring.indices) {
        val j = (i + 1) % ring.size
        val x1 = (ring[i].longitude - centerLon) * lonMetersPerDeg
        val y1 = (ring[i].latitude - centerLat) * latMetersPerDeg
        val x2 = (ring[j].longitude - centerLon) * lonMetersPerDeg
        val y2 = (ring[j].latitude - centerLat) * latMetersPerDeg
        twiceArea += (x1 * y2) - (x2 * y1)
    }
    return kotlin.math.abs(twiceArea) * 0.5
}

internal fun buildOfflineBoundaryOptions(state: ArtifactOverlayState): List<OfflineBoundaryOption> {
    val options = mutableListOf<OfflineBoundaryOption>()
    state.polygons.forEachIndexed { index, polygon ->
        val boundary = geoBoundaryFromPoints(polygon.points) ?: return@forEachIndexed
        options += OfflineBoundaryOption(
            id = "poly:${polygon.id}:$index",
            label = "[Polygon] ${polygon.title}",
            boundary = boundary
        )
    }
    state.lines.forEachIndexed { index, line ->
        val boundary = geoBoundaryFromPoints(line.points,minimumPoints=2) ?: return@forEachIndexed
        options += OfflineBoundaryOption(
            id = "line:${line.id}:$index",
            label = "[Line] ${line.title}",
            boundary = boundary
        )
    }
    return options
}

private fun geoBoundaryFromPoints(points: List<GeoPoint>, minimumPoints: Int = 3): GeoBoundary? {
    if (points.size < minimumPoints) return null
    val ring = if (points.first().latitude == points.last().latitude && points.first().longitude == points.last().longitude) {
        points
    } else {
        points + points.first()
    }
    return GeoBoundary(
        ring = ring,
        bounds = boundingBoxFromPoints(ring)
    )
}

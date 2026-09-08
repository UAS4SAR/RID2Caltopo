package org.ncssar.rid2caltopo.video

import android.graphics.Color as AndroidColor
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.normalizePilotCallsign
import org.ncssar.rid2caltopo.ui.MapFolderUiState
import org.ncssar.rid2caltopo.ui.MapItemUiState
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.util.Locale

private const val CALTOPO_ASSIGNMENTS_FOLDER_ID = "__caltopo_assignments__"
private const val CALTOPO_ASSIGNMENTS_FOLDER_TITLE = "Assignments"
private const val CALTOPO_RANGE_RINGS_FOLDER_ID = "__caltopo_range_rings__"
private const val CALTOPO_RANGE_RINGS_FOLDER_TITLE = "Range Rings"
private const val CALTOPO_MARKERS_FOLDER_ID = "__caltopo_markers__"
private const val CALTOPO_MARKERS_FOLDER_TITLE = "Markers"
private const val CALTOPO_LINES_POLYGONS_FOLDER_ID = "__caltopo_lines_polygons__"
private const val CALTOPO_LINES_POLYGONS_FOLDER_TITLE = "Lines & Polygons"
private const val CALTOPO_APP_TRACKS_FOLDER_ID = "__caltopo_app_tracks__"
private const val CALTOPO_APP_TRACKS_FOLDER_TITLE = "App Tracks"
private const val CALTOPO_OTHER_MAP_ITEMS_FOLDER_ID = "__caltopo_other_map_items__"
private const val CALTOPO_OTHER_MAP_ITEMS_FOLDER_TITLE = "Other Map Items"

private data class SyntheticArtifactFolder(
    val id: String,
    val title: String,
    val initiallyVisible: Boolean
)

private val syntheticArtifactFoldersById = listOf(
    SyntheticArtifactFolder(CALTOPO_ASSIGNMENTS_FOLDER_ID, CALTOPO_ASSIGNMENTS_FOLDER_TITLE, true),
    SyntheticArtifactFolder(CALTOPO_RANGE_RINGS_FOLDER_ID, CALTOPO_RANGE_RINGS_FOLDER_TITLE, true),
    SyntheticArtifactFolder(CALTOPO_MARKERS_FOLDER_ID, CALTOPO_MARKERS_FOLDER_TITLE, true),
    SyntheticArtifactFolder(CALTOPO_LINES_POLYGONS_FOLDER_ID, CALTOPO_LINES_POLYGONS_FOLDER_TITLE, true),
    SyntheticArtifactFolder(CALTOPO_APP_TRACKS_FOLDER_ID, CALTOPO_APP_TRACKS_FOLDER_TITLE, true),
    SyntheticArtifactFolder(CALTOPO_OTHER_MAP_ITEMS_FOLDER_ID, CALTOPO_OTHER_MAP_ITEMS_FOLDER_TITLE, true)
).associateBy { it.id }

internal fun buildMapFolderUiStates(features: Map<String, JSONObject>): List<MapFolderUiState> {
    val folderItems = mutableMapOf<String, MutableList<MapItemUiState>>()
    val folderMeta = mutableMapOf<String, Pair<String, Boolean>>()  // id -> (title, visible)
    val renderableAssignmentKeys = features.values.mapNotNull { feature ->
        val props = feature.optJSONObject("properties") ?: return@mapNotNull null
        val id = feature.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val className = props.optString("class")
        if (className != "Assignment" || artifactGeoPoints(feature).isEmpty()) return@mapNotNull null
        val folderId = effectiveArtifactFolderId(props, className).takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        assignmentDisplayKey(folderId, artifactDisplayTitle(props, id, className))
    }.toSet()
    for (feature in features.values) {
        val props = feature.optJSONObject("properties") ?: continue
        val id = feature.optString("id").takeIf { it.isNotBlank() } ?: continue
        val className = props.optString("class")
        if (className == "Folder") {
            val title = artifactDisplayTitle(props, id, className)
            folderMeta[id] = Pair(title, props.optBoolean("visible", true))
        } else {
            val folderId = effectiveArtifactFolderId(props, className).takeIf { it.isNotBlank() } ?: continue
            val title = artifactDisplayTitle(props, id, className)
            val zoomableAssignment = className == "Assignment" && artifactGeoPoints(feature).isNotEmpty()
            if (className == "Assignment" && !zoomableAssignment &&
                assignmentDisplayKey(folderId, title) in renderableAssignmentKeys
            ) continue
            folderItems.getOrPut(folderId) { mutableListOf() }.add(
                MapItemUiState(
                    featureId = id,
                    title = title,
                    zoomableAssignment = zoomableAssignment
                )
            )
        }
    }
    for (folder in syntheticArtifactFoldersById.values) {
        if (folderItems.containsKey(folder.id)) {
            folderMeta.putIfAbsent(folder.id, Pair(folder.title, folder.initiallyVisible))
        }
    }
    for (folderId in folderItems.keys) {
        folderMeta.putIfAbsent(folderId, Pair(orphanFolderTitle(folderId), true))
    }
    return folderMeta.entries
        .sortedBy { it.value.first }
        .map { (folderId, meta) ->
            MapFolderUiState(
                folderId = folderId,
                title = meta.first,
                initiallyVisible = meta.second,
                items = (folderItems[folderId] ?: emptyList()).sortedBy { it.title }
            )
        }
}

private fun assignmentDisplayKey(folderId: String, title: String): String =
    "${folderId.lowercase()}\u0000${title.trim().lowercase()}"

internal fun mapFolderUiDebugSummary(
    folders: List<MapFolderUiState>,
    hiddenFolderIds: Set<String>,
    hiddenItemIds: Set<String>
): String {
    val folderSummaries = folders.map { folder ->
        val hiddenItemCount = folder.items.count { it.featureId in hiddenItemIds }
        val sampleItems = folder.items.take(5).joinToString(separator = ",") { item ->
            "${item.title}(${item.featureId})"
        }
        "${folder.title} id=${folder.folderId} hidden=${folder.folderId in hiddenFolderIds} " +
            "defaultVisible=${folder.initiallyVisible} items=${folder.items.size} " +
            "hiddenItems=$hiddenItemCount sample=[$sampleItems]"
    }
    return (listOf("folders=${folders.size}") + folderSummaries).joinToString(separator = " | ")
}

internal fun assignmentDiagnosticSummary(
    features: Map<String, JSONObject>,
    hiddenFolderIds: Set<String>,
    hiddenItemIds: Set<String>,
    viewport: BoundingBox? = null,
): String {
    val assignments = features.values.mapNotNull { feature ->
        val props = feature.optJSONObject("properties") ?: return@mapNotNull null
        if (props.optString("class") != "Assignment") return@mapNotNull null
        val id = feature.optString("id").ifBlank { "?" }
        val title = artifactDisplayTitle(props, id, "Assignment")
        val folderId = effectiveArtifactFolderId(props, "Assignment")
        val points = artifactGeoPoints(feature)
        val bounds = points.takeIf { it.isNotEmpty() }?.let(::boundingBoxFromPoints)
        val visible = folderId !in hiddenFolderIds && id !in hiddenItemIds
        val inViewport = bounds?.let { viewportBounds ->
            viewport?.let { assignmentBoundsIntersect(it, viewportBounds) }
        }
        val boundsSummary = bounds?.let {
            String.format(
                Locale.US,
                "%.5f,%.5f..%.5f,%.5f",
                it.latSouth,
                it.lonWest,
                it.latNorth,
                it.lonEast,
            )
        } ?: "none"
        "$title($id points=${points.size} bounds=$boundsSummary visible=$visible" +
            (inViewport?.let { " inViewport=$it" } ?: "") + ")"
    }
    val geometryless = assignments.count { " points=0 " in it }
    val viewportSummary = viewport?.let {
        String.format(
            Locale.US,
            " viewport=%.5f,%.5f..%.5f,%.5f",
            it.latSouth,
            it.lonWest,
            it.latNorth,
            it.lonEast,
        )
    }.orEmpty()
    return "assignments=${assignments.size} geometryless=$geometryless$viewportSummary " +
        "items=[${assignments.take(50).joinToString(separator = "; ")}]"
}

private fun assignmentBoundsIntersect(viewport: BoundingBox, assignment: BoundingBox): Boolean =
    assignment.lonEast >= viewport.lonWest &&
        assignment.lonWest <= viewport.lonEast &&
        assignment.latNorth >= viewport.latSouth &&
        assignment.latSouth <= viewport.latNorth

internal fun buildArtifactOverlayState(
    features: Collection<JSONObject>,
    hiddenFolderIds: Set<String> = emptySet(),
    hiddenItemIds: Set<String> = emptySet(),
    pilotArchiveTrackColorForCallsign: (String) -> String? = { null }
): ArtifactOverlayState {
    val featuresById = features.mapNotNull { feature ->
        feature.optString("id").takeIf { it.isNotBlank() }?.let { it to feature }
    }.toMap()
    val points = mutableListOf<ArtifactPointSpec>()
    val lines = mutableListOf<ArtifactLineSpec>()
    val polygons = mutableListOf<ArtifactPolygonSpec>()
    var ignoredTrackLikeFeatures = 0
    val representedFolderIds = features.mapNotNull { feature ->
        val props = feature.optJSONObject("properties") ?: return@mapNotNull null
        val className = props.optString("class")
        if (className == "Folder") {
            feature.optString("id").takeIf { it.isNotBlank() }
        } else {
            effectiveArtifactFolderId(props, className).takeIf { it.isNotBlank() }
        }
    }.toSet()

    for (feature in features) {
        val geometry = feature.optJSONObject("geometry") ?: continue
        val properties = feature.optJSONObject("properties")
        val className = properties?.optString("class").orEmpty()
        if (className == "Folder") continue

        val featureId = feature.optString("id")
        val folderId = effectiveArtifactFolderId(properties, className)
        if (folderId.isBlank() || folderId !in representedFolderIds) continue
        if (folderId.isNotBlank() && folderId in hiddenFolderIds) continue
        if (featureId.isNotBlank() && featureId in hiddenItemIds) continue
        if (isMediaObjectWithHiddenParent(properties, featuresById, hiddenFolderIds, hiddenItemIds)) continue
        val featureTitle = artifactDisplayTitle(properties, featureId, className)
        val featureDescription = properties?.optString("description").orEmpty().trim()
        val markerSymbol = properties?.optString("marker-symbol", "point").orEmpty().ifBlank { "point" }
        val markerColor = properties?.optString("marker-color")
        val trackLikeFeature = isTrackLikeFeature(properties, className)
        val defaultStrokeHex = markerColor?.takeIf { it.isNotBlank() } ?: "#FF5A1F"

        val pilotArchiveColor = archivedDroneTrackPilotCallsign(properties)
            ?.let(pilotArchiveTrackColorForCallsign)
        val strokeColor = colorFromHex(
            pilotArchiveColor ?: properties?.optString("stroke", defaultStrokeHex),
            "#FF5A1F",
            properties?.optDouble("stroke-opacity", 1.0) ?: 1.0
        )
        val strokeWidth = (properties?.optDouble("stroke-width", 3.0) ?: 3.0).toFloat()
        val fillColor = colorFromHex(
            properties?.optString("fill", "#33FF5A1F"),
            "#33FF5A1F",
            properties?.optDouble("fill-opacity", 0.20) ?: 0.20
        )

        ignoredTrackLikeFeatures += appendGeometryArtifact(
            featureId = featureId,
            featureTitle = featureTitle,
            featureDescription = featureDescription,
            geometry = geometry,
            strokeColor = strokeColor,
            fillColor = fillColor,
            strokeWidth = strokeWidth,
            markerSymbol = markerSymbol,
            markerColor = markerColor,
            trackLikeFeature = trackLikeFeature,
            pointsOut = points,
            linesOut = lines,
            polygonsOut = polygons
        )
    }

    return ArtifactOverlayState(
        totalFeatures = features.size,
        ignoredTrackLikeFeatures = ignoredTrackLikeFeatures,
        points = points,
        lines = lines,
        polygons = polygons
    )
}

internal fun buildArtifactHydrationResult(
    snapshot: Collection<JSONObject>,
    hiddenFolderIds: Set<String> = emptySet(),
    hiddenItemIds: Set<String> = emptySet(),
    folderVisibilityOverrides: Map<String, Boolean> = emptyMap(),
    progressInterval: Int = 100,
    pilotArchiveTrackColorForCallsign: (String) -> String? = { null },
    onProgress: (ArtifactHydrationProgress) -> Unit = {}
): ArtifactHydrationResult {
    val featuresById = LinkedHashMap<String, JSONObject>()
    val folderDefaultsById = LinkedHashMap<String, ArtifactFolderDefault>()
    val serverHiddenFolderIds = LinkedHashSet<String>()
    val total = snapshot.size
    val checkpoint = progressInterval.coerceAtLeast(1)
    snapshot.forEachIndexed { index, feature ->
        val featureId = feature.optString("id")
        if (featureId.isNotBlank()) {
            featuresById[featureId] = feature
            val props = feature.optJSONObject("properties")
            if (props?.optString("class") == "Folder") {
                if (!props.optBoolean("visible", true)) {
                    serverHiddenFolderIds.add(featureId)
                }
                folderDefaultsById.putIfAbsent(
                    featureId,
                    ArtifactFolderDefault(featureId, props.optBoolean("visible", true))
                )
            } else {
                syntheticArtifactFolderDefault(props)?.let { folderDefault ->
                    folderDefaultsById.putIfAbsent(folderDefault.folderId, folderDefault)
                }
            }
        }
        val completed = index + 1
        if (completed == total || completed % checkpoint == 0) {
            onProgress(ArtifactHydrationProgress(completed = completed, total = total))
        }
    }
    if (total == 0) {
        onProgress(ArtifactHydrationProgress(completed = 0, total = 0))
    }
    val folderDefaults = folderDefaultsById.values.toList()
    return ArtifactHydrationResult(
        featuresById = featuresById,
        overlayState = buildArtifactOverlayState(
            featuresById.values,
            resolveHiddenFolderIds(
                localHiddenFolderIds = hiddenFolderIds,
                defaultHiddenFolderIds = serverHiddenFolderIds,
                operatorVisibilityOverrides = folderVisibilityOverrides
            ),
            hiddenItemIds,
            pilotArchiveTrackColorForCallsign
        ),
        folderDefaults = folderDefaults,
        serverHiddenFolderIds = serverHiddenFolderIds
    )
}

/**
 * Resolves server/default folder visibility without overwriting an explicit operator
 * choice made during the active map session.
 */
internal fun resolveHiddenFolderIds(
    localHiddenFolderIds: Set<String>,
    defaultHiddenFolderIds: Set<String>,
    operatorVisibilityOverrides: Map<String, Boolean>
): Set<String> {
    val resolved = (localHiddenFolderIds + defaultHiddenFolderIds).toMutableSet()
    operatorVisibilityOverrides.forEach { (folderId, visible) ->
        if (visible) resolved.remove(folderId) else resolved.add(folderId)
    }
    return resolved
}

internal fun folderHiddenAfterDefault(
    currentlyHidden: Boolean,
    defaultVisible: Boolean,
    operatorVisibilityOverride: Boolean?
): Boolean = when (operatorVisibilityOverride) {
    true -> false
    false -> true
    null -> currentlyHidden || !defaultVisible
}

internal fun movedDroneFolderMarkerIds(
    previousFeatures: Map<String, JSONObject>,
    incomingFeatures: Map<String, JSONObject>,
    expectedDroneFolderId: String?
): Set<String> {
    val expectedFolder = expectedDroneFolderId?.takeIf { it.isNotBlank() } ?: return emptySet()
    return incomingFeatures.mapNotNull { (featureId, incomingFeature) ->
        if (featureId.isBlank()) return@mapNotNull null
        val previousFeature = previousFeatures[featureId] ?: return@mapNotNull null
        val previousProperties = previousFeature.optJSONObject("properties")
        val incomingProperties = incomingFeature.optJSONObject("properties")
        if (!isDroneTrackMarker(previousProperties) || !isDroneTrackMarker(incomingProperties)) {
            return@mapNotNull null
        }
        val previousFolder = effectiveArtifactFolderId(
            previousProperties,
            previousProperties?.optString("class").orEmpty()
        )
        val incomingFolder = effectiveArtifactFolderId(
            incomingProperties,
            incomingProperties?.optString("class").orEmpty()
        )
        if (previousFolder == expectedFolder && incomingFolder.isNotBlank() && incomingFolder != expectedFolder) {
            featureId
        } else {
            null
        }
    }.toSet()
}

private fun isDroneTrackMarker(properties: JSONObject?): Boolean {
    if (properties?.optString("class") != "Marker") return false
    return !properties.has("r2c-guid")
}

private fun isMediaObjectWithHiddenParent(
    properties: JSONObject?,
    featuresById: Map<String, JSONObject>,
    hiddenFolderIds: Set<String>,
    hiddenItemIds: Set<String>
): Boolean {
    if (properties?.optString("class") != "MapMediaObject") return false
    val parentMarkerId = properties.optString("parentId")
        .takeIf { it.startsWith("Marker:") }
        ?.removePrefix("Marker:")
        ?.takeIf { it.isNotBlank() }
        ?: return false
    if (parentMarkerId in hiddenItemIds) return true
    val parentProperties = featuresById[parentMarkerId]?.optJSONObject("properties") ?: return false
    val parentClassName = parentProperties.optString("class")
    val parentFolderId = effectiveArtifactFolderId(parentProperties, parentClassName)
    return parentFolderId.isNotBlank() && parentFolderId in hiddenFolderIds
}

private fun effectiveArtifactFolderId(properties: JSONObject?, className: String): String {
    val folderId = properties?.optString("folderId").orEmpty()
    val syntheticFolderId = syntheticArtifactFolderId(properties, className)
    if (syntheticFolderId.isNotBlank()) return syntheticFolderId
    if (folderId.isNotBlank()) return folderId
    return ""
}

private fun syntheticArtifactFolderId(properties: JSONObject?, className: String): String {
    val folderId = properties?.optString("folderId").orEmpty()
    return when {
        className == "Assignment" -> CALTOPO_ASSIGNMENTS_FOLDER_ID
        className == "RangeRing" || folderId == CALTOPO_RANGE_RINGS_FOLDER_TITLE -> CALTOPO_RANGE_RINGS_FOLDER_ID
        className == "Marker" && folderId.isBlank() -> CALTOPO_MARKERS_FOLDER_ID
        folderId == CALTOPO_MARKERS_FOLDER_TITLE -> CALTOPO_MARKERS_FOLDER_ID
        className == "Shape" && (folderId.isBlank() || folderId == CALTOPO_LINES_POLYGONS_FOLDER_TITLE) ->
            CALTOPO_LINES_POLYGONS_FOLDER_ID
        className == "AppTrack" && folderId.isBlank() -> CALTOPO_APP_TRACKS_FOLDER_ID
        folderId == CALTOPO_APP_TRACKS_FOLDER_TITLE -> CALTOPO_APP_TRACKS_FOLDER_ID
        folderId.isBlank() -> CALTOPO_OTHER_MAP_ITEMS_FOLDER_ID
        else -> ""
    }
}

private fun artifactDisplayTitle(properties: JSONObject?, featureId: String, className: String): String {
    properties?.optString("title")?.takeIf { it.isNotBlank() }?.let { return it }
    val typeLabel = className.ifBlank { "Map item" }
    return if (featureId.isBlank()) typeLabel else "$typeLabel:$featureId"
}

private fun archivedDroneTrackPilotCallsign(properties: JSONObject?): String? {
    val r2cProp = properties?.optJSONObject("r2c_prop")
    normalizePilotCallsign(r2cProp?.optString("owner"))?.let { return it }
    val description = properties?.optString("description").orEmpty()
    description.lineSequence().forEach { line ->
        val parts = line.split(":", limit = 2)
        if (parts.size == 2 && parts[0].trim().equals("Pilot Callsign", ignoreCase = true)) {
            return normalizePilotCallsign(parts[1])
        }
    }
    return null
}

private fun orphanFolderTitle(folderId: String): String {
    val suffix = folderId.take(8).ifBlank { "unknown" }
    return "Unlisted Folder $suffix"
}

internal fun applySyntheticArtifactFolderDefault(
    properties: JSONObject?,
    applyFolderDefault: (folderId: String, initiallyVisible: Boolean) -> Unit
) {
    syntheticArtifactFolderDefault(properties)?.let {
        applyFolderDefault(it.folderId, it.initiallyVisible)
    }
}

private fun syntheticArtifactFolderDefault(properties: JSONObject?): ArtifactFolderDefault? {
    val className = properties?.optString("class").orEmpty()
    val folderId = syntheticArtifactFolderId(properties, className)
    val folder = syntheticArtifactFoldersById[folderId] ?: return null
    return ArtifactFolderDefault(folder.id, folder.initiallyVisible)
}

private fun isTrackLikeFeature(properties: JSONObject?, className: String): Boolean {
    // Active drone tracks are rendered by the drone tracking system; suppress them
    // here to avoid double-rendering. Archived tracks stay renderable as map-folder artifacts.
    if (className == "LiveTrack") return true
    val folderId = properties?.optString("folderId").orEmpty()
    val mapTrackFolderId = CaltopoMap.GetFolderId().orEmpty()
    if (folderId.isNotBlank() && folderId == mapTrackFolderId) return true
    return false
}

private fun appendGeometryArtifact(
    featureId: String,
    featureTitle: String,
    featureDescription: String,
    geometry: JSONObject,
    strokeColor: Int,
    fillColor: Int,
    strokeWidth: Float,
    markerSymbol: String,
    markerColor: String?,
    trackLikeFeature: Boolean,
    pointsOut: MutableList<ArtifactPointSpec>,
    linesOut: MutableList<ArtifactLineSpec>,
    polygonsOut: MutableList<ArtifactPolygonSpec>
): Int {
    var ignoredTrackLike = 0
    when (geometry.optString("type")) {
        "Point" -> {
            if (trackLikeFeature) {
                ignoredTrackLike++
                return ignoredTrackLike
            }
            val coords = geometry.optJSONArray("coordinates") ?: return 0
            val geoPoint = geoPointFromLngLat(coords) ?: return 0
            pointsOut += ArtifactPointSpec(
                id = featureId,
                lat = geoPoint.latitude,
                lng = geoPoint.longitude,
                title = featureTitle,
                markerSymbol = markerSymbol,
                markerColor = markerColor,
                description = featureDescription
            )
        }

        "LineString" -> {
            if (trackLikeFeature) {
                ignoredTrackLike++
            } else {
                val coords = geometry.optJSONArray("coordinates") ?: return ignoredTrackLike
                val geoPoints = geoPointsFromLine(coords)
                if (geoPoints.isNotEmpty()) {
                    linesOut += ArtifactLineSpec(
                        featureId, geoPoints, strokeColor, strokeWidth, featureTitle, featureDescription
                    )
                }
            }
        }

        "MultiLineString" -> {
            val lineGroups = geometry.optJSONArray("coordinates") ?: return ignoredTrackLike
            for (i in 0 until lineGroups.length()) {
                val lineCoords = lineGroups.optJSONArray(i) ?: continue
                if (trackLikeFeature) {
                    ignoredTrackLike++
                    continue
                }
                val geoPoints = geoPointsFromLine(lineCoords)
                if (geoPoints.isNotEmpty()) {
                    linesOut += ArtifactLineSpec(
                        featureId, geoPoints, strokeColor, strokeWidth, "$featureTitle[$i]", featureDescription
                    )
                }
            }
        }

        "Polygon" -> {
            val coords = geometry.optJSONArray("coordinates") ?: return ignoredTrackLike
            val outerRing = coords.optJSONArray(0) ?: return ignoredTrackLike
            val geoPoints = geoPointsFromLine(outerRing)
            if (geoPoints.isNotEmpty()) {
                polygonsOut += ArtifactPolygonSpec(
                    featureId,
                    geoPoints,
                    strokeColor,
                    fillColor,
                    strokeWidth,
                    featureTitle,
                    featureDescription
                )
            }
        }

        "MultiPolygon" -> {
            val polygonGroups = geometry.optJSONArray("coordinates") ?: return ignoredTrackLike
            for (i in 0 until polygonGroups.length()) {
                val polygonCoords = polygonGroups.optJSONArray(i) ?: continue
                val outerRing = polygonCoords.optJSONArray(0) ?: continue
                val geoPoints = geoPointsFromLine(outerRing)
                if (geoPoints.isNotEmpty()) {
                    polygonsOut += ArtifactPolygonSpec(
                        featureId,
                        geoPoints,
                        strokeColor,
                        fillColor,
                        strokeWidth,
                        "$featureTitle[$i]",
                        featureDescription
                    )
                }
            }
        }

        "GeometryCollection" -> {
            val geometries = geometry.optJSONArray("geometries") ?: return ignoredTrackLike
            for (i in 0 until geometries.length()) {
                val nested = geometries.optJSONObject(i) ?: continue
                ignoredTrackLike += appendGeometryArtifact(
                    featureId = featureId,
                    featureTitle = "$featureTitle[$i]",
                    featureDescription = featureDescription,
                    geometry = nested,
                    strokeColor = strokeColor,
                    fillColor = fillColor,
                    strokeWidth = strokeWidth,
                    markerSymbol = markerSymbol,
                    markerColor = markerColor,
                    trackLikeFeature = trackLikeFeature,
                    pointsOut = pointsOut,
                    linesOut = linesOut,
                    polygonsOut = polygonsOut
                )
            }
        }
    }
    return ignoredTrackLike
}

internal fun isArtifactDelete(feature: JSONObject): Boolean {
    if (feature.optBoolean("deleted", false)) return true
    val props = feature.optJSONObject("properties")
    if (props == null) {
        return feature.has("id") && !feature.has("geometry")
    }
    if (props.optBoolean("deleted", false)) return true
    val action = props.optString("action")
    return action.equals("delete", ignoreCase = true) || action.equals("removed", ignoreCase = true)
}

private fun geoPointsFromLine(coords: JSONArray): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    for (i in 0 until coords.length()) {
        val pair = coords.optJSONArray(i) ?: continue
        val geoPoint = geoPointFromLngLat(pair) ?: continue
        points += geoPoint
    }
    return points
}

internal fun artifactLogSummary(feature: JSONObject): String {
    val featureId = feature.optString("id").ifBlank { "?" }
    val props = feature.optJSONObject("properties")
    val className = props?.optString("class").orEmpty().ifBlank { "unknown" }
    val title = props?.optString("title").orEmpty().ifBlank { "<untitled>" }
    val description = props?.optString("description").orEmpty().trim()
    val descriptionSummary = when {
        description.isBlank() -> ""
        description.length <= 48 -> " desc=\"$description\""
        else -> " desc=\"${description.take(45)}...\""
    }
    val geometrySummary = if (className == "Assignment") {
        val points = artifactGeoPoints(feature)
        " geometryPoints=${points.size} zoomable=${points.isNotEmpty()}"
    } else {
        ""
    }
    return "id=$featureId class=$className title=\"$title\"$descriptionSummary$geometrySummary"
}

private fun geoPointFromLngLat(coords: JSONArray): GeoPoint? {
    if (coords.length() < 2) return null
    val lng = coords.optDouble(0, Double.NaN)
    val lat = coords.optDouble(1, Double.NaN)
    if (!lat.isFinite() || !lng.isFinite()) return null
    return GeoPoint(lat, lng)
}

private fun colorFromHex(colorHex: String?, fallbackHex: String, opacity: Double): Int {
    val base = try {
        AndroidColor.parseColor(colorHex ?: fallbackHex)
    } catch (_: IllegalArgumentException) {
        AndroidColor.parseColor(fallbackHex)
    }
    val alpha = (opacity.coerceIn(0.0, 1.0) * 255.0).toInt()
    return AndroidColor.argb(alpha, AndroidColor.red(base), AndroidColor.green(base), AndroidColor.blue(base))
}

internal fun allArtifactGeoPoints(state: ArtifactOverlayState): List<GeoPoint> {
    val points = mutableListOf<GeoPoint>()
    state.points.forEach { points += GeoPoint(it.lat, it.lng) }
    state.lines.forEach { line -> points += line.points }
    state.polygons.forEach { polygon -> points += polygon.points }
    return points
}

internal fun artifactGeoPoints(feature: JSONObject): List<GeoPoint> =
    allArtifactGeoPoints(buildArtifactOverlayState(listOf(feature)))

internal fun boundingBoxFromPoints(points: List<GeoPoint>): BoundingBox {
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    points.forEach { p ->
        minLat = minOf(minLat, p.latitude)
        maxLat = maxOf(maxLat, p.latitude)
        minLon = minOf(minLon, p.longitude)
        maxLon = maxOf(maxLon, p.longitude)
    }
    return BoundingBox(maxLat, maxLon, minLat, minLon)
}

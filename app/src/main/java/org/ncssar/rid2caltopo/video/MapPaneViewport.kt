package org.ncssar.rid2caltopo.video

internal fun sameMapDrone(first: String?, second: String?): Boolean =
    first != null && second != null && first.equals(second, ignoreCase = true)

data class MapViewportBounds(
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double
) {
    val isUsable: Boolean
        get() = north.isFinite() &&
            east.isFinite() &&
            south.isFinite() &&
            west.isFinite() &&
            north in -85.0..85.0 &&
            south in -85.0..85.0 &&
            east in -180.0..180.0 &&
            west in -180.0..180.0 &&
            north > south &&
            east != west
}

internal class MapViewportRestoreTracker {
    private var restoredTarget: Any? = null

    fun needsRestore(target: Any): Boolean = restoredTarget !== target

    fun markRestored(target: Any) {
        restoredTarget = target
    }

    fun reset() {
        restoredTarget = null
    }
}

internal fun mapPaneMarkerScale(mode: MapPanePresentationMode): Float =
    when (mode) {
        MapPanePresentationMode.Full -> 1.0f
        MapPanePresentationMode.Inset -> 0.55f
    }

internal fun mapPaneLineScale(mode: MapPanePresentationMode): Float =
    when (mode) {
        MapPanePresentationMode.Full -> 1.0f
        MapPanePresentationMode.Inset -> 0.65f
    }

internal fun mapPaneInsetViewportZoom(
    fullWidthPx: Int?,
    fullHeightPx: Int?,
    insetWidthPx: Int,
    insetHeightPx: Int,
    fullZoom: Double,
    maxZoom: Double = MAP_DISPLAY_MAX_ZOOM
): Double {
    if (!fullZoom.isFinite()) return fullZoom
    val fullWidth = fullWidthPx?.takeIf { it > 0 } ?: return fullZoom
    val fullHeight = fullHeightPx?.takeIf { it > 0 } ?: return fullZoom
    if (insetWidthPx <= 0 || insetHeightPx <= 0) return fullZoom
    val widthRatio = fullWidth.toDouble() / insetWidthPx.toDouble()
    val heightRatio = fullHeight.toDouble() / insetHeightPx.toDouble()
    val scaleRatio = maxOf(widthRatio, heightRatio).takeIf { it.isFinite() && it > 1.0 } ?: return fullZoom
    return (fullZoom - kotlin.math.ln(scaleRatio) / kotlin.math.ln(2.0))
        .coerceIn(0.0, maxZoom.takeIf { it.isFinite() && it >= 0.0 } ?: MAP_DISPLAY_MAX_ZOOM)
}

internal fun mapPaneInitialViewportZoom(
    presentationMode: MapPanePresentationMode,
    restoredZoom: Double,
    maxZoom: Double
): Double {
    if (presentationMode != MapPanePresentationMode.Inset) return restoredZoom
    val safeMaxZoom = maxZoom.takeIf { it.isFinite() && it >= 0.0 } ?: MAP_DISPLAY_MAX_ZOOM
    return restoredZoom.coerceAtMost(safeMaxZoom)
}

internal fun shouldFollowFocusedDrone(
    presentationMode: MapPanePresentationMode,
    followFocusedDroneEnabled: Boolean,
    hasFocusedDroneTelemetry: Boolean,
    operatorAdjustedViewport: Boolean
): Boolean {
    if (!followFocusedDroneEnabled || !hasFocusedDroneTelemetry) return false
    return presentationMode == MapPanePresentationMode.Inset || !operatorAdjustedViewport
}

internal enum class OperatorMapGesture {
    Pan,
    Zoom,
    Tap
}

internal fun shouldReleaseFocusedDroneForMapGesture(
    presentationMode: MapPanePresentationMode,
    hasFocusedDrone: Boolean,
    gesture: OperatorMapGesture
): Boolean =
    presentationMode == MapPanePresentationMode.Full &&
        hasFocusedDrone &&
        gesture != OperatorMapGesture.Tap

internal fun mapPaneShouldReplayCachedArtifacts(
    presentationMode: MapPanePresentationMode,
    cachedFeatureCount: Int
): Boolean = presentationMode == MapPanePresentationMode.Full && cachedFeatureCount <= 0

internal fun mapPaneShouldRequestArtifactRefreshOnMount(
    presentationMode: MapPanePresentationMode,
    cachedFeatureCount: Int
): Boolean = false

internal fun mapPaneCanZoomToBoundingBox(
    mapWidthPx: Int,
    mapHeightPx: Int,
    pointCount: Int
): Boolean = mapWidthPx > 0 && mapHeightPx > 0 && pointCount >= 2

internal fun shouldRescueLocalDeviceViewport(
    hasRestoredViewport: Boolean,
    presentationMode: MapPanePresentationMode,
    rescueAlreadyApplied: Boolean,
    localDeviceVisible: Boolean,
    defaultViewportCenter: Boolean,
    operationalContentPresent: Boolean,
    operatorAdjustedViewport: Boolean
): Boolean =
    !hasRestoredViewport &&
        presentationMode == MapPanePresentationMode.Full &&
        !rescueAlreadyApplied &&
        !localDeviceVisible &&
        defaultViewportCenter &&
        !operationalContentPresent &&
        !operatorAdjustedViewport

internal fun isUsableMapViewportState(latitude: Double, longitude: Double, zoom: Double): Boolean {
    if (!latitude.isFinite() || !longitude.isFinite() || !zoom.isFinite()) return false
    if (latitude !in -85.0..85.0 || longitude !in -180.0..180.0) return false
    return !(kotlin.math.abs(latitude) < 0.000001 && kotlin.math.abs(longitude) < 0.000001)
}

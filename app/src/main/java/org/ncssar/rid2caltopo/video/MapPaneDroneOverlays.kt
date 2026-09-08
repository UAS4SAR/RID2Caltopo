package org.ncssar.rid2caltopo.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.content.res.Resources
import java.util.Locale
import kotlin.math.roundToInt
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.PilotDisplayPreference
import org.ncssar.rid2caltopo.data.normalizePilotCallsign
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

internal fun localTrackDesignator(mappedId: String): String = mappedId.ifBlank { "unmapped" }

internal fun layoutDroneLabelGroups(
    labels: List<DroneLabelLayoutInput>,
    viewportWidth: Int,
    viewportHeight: Int
): List<DroneLabelLayout> {
    val placedBounds = mutableListOf<LabelRect>()
    val viewport = LabelRect(0, 0, viewportWidth, viewportHeight)
    return labels.map { label ->
        val candidates = droneLabelCandidates(label)
        val preferred = candidates.firstOrNull { candidate ->
            candidate.group.fitsWithin(viewport) && placedBounds.none { it.intersects(candidate.group) }
        } ?: candidates.minWithOrNull(
            compareBy<DroneLabelCandidate> { candidate ->
                placedBounds.sumOf { candidate.group.overlapArea(it) }
            }.thenBy { candidate ->
                candidate.group.outsideArea(viewport)
            }
        ) ?: droneLabelCandidate(label, offsetX = 0, offsetY = 28)
        placedBounds.add(preferred.group)
        preferred.toLayout(label)
    }
}

private data class DroneLabelCandidate(
    val group: LabelRect,
    val name: LabelRect,
    val status: LabelRect,
    val isDefault: Boolean
) {
    fun toLayout(label: DroneLabelLayoutInput): DroneLabelLayout =
        DroneLabelLayout(
            designator = label.designator,
            bounds = group,
            nameBounds = name,
            statusBounds = status,
            leaderLine = if (isDefault) {
                null
            } else {
                LabelLeaderLine(
                    startX = label.anchorX,
                    startY = label.anchorY,
                    endX = group.centerX,
                    endY = group.centerY
                )
            }
        )
}

private fun droneLabelCandidates(label: DroneLabelLayoutInput): List<DroneLabelCandidate> =
    with(droneLabelGroupSize(label)) {
        val sideOffsetX = width / 2 + 44
        val farSideOffsetX = width / 2 + 92
        val centeredOffsetY = -(height / 2)
        listOf(
            droneLabelCandidate(label, offsetX = 0, offsetY = 28, isDefault = true),
            droneLabelCandidate(label, offsetX = 0, offsetY = -(height + 28)),
            droneLabelCandidate(label, offsetX = sideOffsetX, offsetY = centeredOffsetY),
            droneLabelCandidate(label, offsetX = -sideOffsetX, offsetY = centeredOffsetY),
            droneLabelCandidate(label, offsetX = sideOffsetX, offsetY = 34),
            droneLabelCandidate(label, offsetX = -sideOffsetX, offsetY = 34),
            droneLabelCandidate(label, offsetX = 0, offsetY = height + 34),
            droneLabelCandidate(label, offsetX = farSideOffsetX, offsetY = centeredOffsetY),
            droneLabelCandidate(label, offsetX = -farSideOffsetX, offsetY = centeredOffsetY)
        )
    }

private data class DroneLabelGroupSize(
    val width: Int,
    val height: Int
)

private fun droneLabelGroupSize(label: DroneLabelLayoutInput): DroneLabelGroupSize =
    DroneLabelGroupSize(
        width = maxOf(label.nameWidth, label.statusWidth),
        height = label.nameHeight + 3 + label.statusHeight
    )

private fun droneLabelCandidate(
    label: DroneLabelLayoutInput,
    offsetX: Int,
    offsetY: Int,
    isDefault: Boolean = false
): DroneLabelCandidate {
    val gap = 3
    val groupSize = droneLabelGroupSize(label)
    val left = label.anchorX + offsetX - groupSize.width / 2
    val top = label.anchorY + offsetY
    val group = LabelRect(left, top, left + groupSize.width, top + groupSize.height)
    val nameLeft = group.left + (group.width - label.nameWidth) / 2
    val name = LabelRect(nameLeft, group.top, nameLeft + label.nameWidth, group.top + label.nameHeight)
    val statusLeft = group.left + (group.width - label.statusWidth) / 2
    val statusTop = name.bottom + gap
    val status = LabelRect(statusLeft, statusTop, statusLeft + label.statusWidth, statusTop + label.statusHeight)
    return DroneLabelCandidate(group = group, name = name, status = status, isDefault = isDefault)
}

private fun LabelRect.fitsWithin(container: LabelRect): Boolean =
    left >= container.left &&
        top >= container.top &&
        right <= container.right &&
        bottom <= container.bottom

private fun LabelRect.overlapArea(other: LabelRect): Int {
    val overlapWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
    val overlapHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
    return overlapWidth * overlapHeight
}

private fun LabelRect.outsideArea(container: LabelRect): Int {
    val horizontal = (container.left - left).coerceAtLeast(0) + (right - container.right).coerceAtLeast(0)
    val vertical = (container.top - top).coerceAtLeast(0) + (bottom - container.bottom).coerceAtLeast(0)
    return horizontal * height + vertical * width
}

internal fun fullFlightTrackMappedIds(
    dronePoints: List<DroneMapPoint>,
    eligibleMappedIds: Set<String>,
    mappedIdsByRemoteId: Map<String, Set<String>> = emptyMap()
): Set<String> {
    val mappedIds = LinkedHashSet<String>()
    dronePoints.forEach { point ->
        val currentMappedId = localTrackDesignator(point.designator)
        if (currentMappedId !in eligibleMappedIds) return@forEach
        mappedIds.add(currentMappedId)
        mappedIdsByRemoteId[point.remoteId].orEmpty().forEach { alias ->
            mappedIds.add(localTrackDesignator(alias))
        }
    }
    return mappedIds
}

internal fun confirmedCurrentFlightMappedIds(dronePoints: List<DroneMapPoint>): Set<String> =
    dronePoints
        .asSequence()
        .filter { point -> CaltopoClient.IsCurrentPeerDroneConfirmed(point.remoteId) }
        .map { point -> localTrackDesignator(point.designator) }
        .toSet()

internal fun pilotDisplayPreferencesByMappedId(
    dronePoints: List<DroneMapPoint>,
    mappedIdsByRemoteId: Map<String, Set<String>> = emptyMap(),
    preferenceForPilotKey: (String?) -> PilotDisplayPreference
): Map<String, PilotDisplayPreference> {
    val byMappedId = LinkedHashMap<String, PilotDisplayPreference>()
    dronePoints.forEach { point ->
        val pilotKey = normalizePilotCallsign(point.droneSpec?.owner)
        val preference = preferenceForPilotKey(pilotKey)
        val currentMappedId = localTrackDesignator(point.designator)
        byMappedId[currentMappedId] = preference
        mappedIdsByRemoteId[point.remoteId].orEmpty().forEach { alias ->
            byMappedId[localTrackDesignator(alias)] = preference
        }
    }
    return byMappedId
}

internal data class DroneLabelDrawSpec(
    val designator: String,
    val position: GeoPoint,
    val nameDrawable: Drawable,
    val statusDrawable: Drawable
)

internal data class CameraFovBoundaryBearings(
    val leftBearingDeg: Double,
    val rightBearingDeg: Double
)

internal fun cameraFovBoundaryBearings(
    cameraAzimuthDeg: Double?,
    horizontalFovDeg: Double?
): CameraFovBoundaryBearings? {
    val azimuth = cameraAzimuthDeg?.takeIf { it.isFinite() } ?: return null
    val fov = horizontalFovDeg?.takeIf { it.isFinite() && it > 0.0 && it <= 180.0 } ?: return null
    val halfFov = fov / 2.0
    return CameraFovBoundaryBearings(
        leftBearingDeg = normalizeDegrees(azimuth - halfFov),
        rightBearingDeg = normalizeDegrees(azimuth + halfFov)
    )
}

internal data class CameraFovDrawSpec(
    val position: GeoPoint,
    val leftBearingDeg: Double,
    val rightBearingDeg: Double,
    val scale: Float
)

/** Screen-sized camera field-of-view rays that remain legible at every map zoom. */
internal class CameraFovOverlay(
    private val cameras: List<CameraFovDrawSpec>,
    resources: Resources
) : Overlay() {
    private val density = resources.displayMetrics.density
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#B3000000")
        style = Paint.Style.STROKE
        strokeWidth = 3.0f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FF80DEEA")
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
        strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || cameras.isEmpty()) return
        val projection = mapView.projection
        cameras.forEach { camera ->
            val anchor = projection.toPixels(camera.position, null)
            val safeScale = camera.scale.takeIf { it.isFinite() && it > 0f } ?: 1f
            // The visible drone glyph is approximately 18 dp wide. Start outside its halo,
            // then extend each boundary ray by two glyph widths.
            val startRadiusPx = 14f * density * safeScale
            val rayLengthPx = 36f * density * safeScale
            listOf(camera.leftBearingDeg, camera.rightBearingDeg).forEach { bearing ->
                val (startX, startY) = polarPoint(
                    anchor.x.toFloat(), anchor.y.toFloat(), startRadiusPx, bearing
                )
                val (endX, endY) = polarPoint(
                    anchor.x.toFloat(), anchor.y.toFloat(), startRadiusPx + rayLengthPx, bearing
                )
                canvas.drawLine(startX, startY, endX, endY, haloPaint)
                canvas.drawLine(startX, startY, endX, endY, rayPaint)
            }
        }
    }
}

internal class DroneLabelOverlay(
    private val labels: List<DroneLabelDrawSpec>
) : Overlay() {
    private val leaderHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#AA000000")
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFFFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || labels.isEmpty()) return
        val projection = mapView.projection
        val layoutInputs = labels.map { label ->
            val point = projection.toPixels(label.position, null)
            DroneLabelLayoutInput(
                designator = label.designator,
                anchorX = point.x,
                anchorY = point.y,
                nameWidth = label.nameDrawable.intrinsicWidth.coerceAtLeast(1),
                nameHeight = label.nameDrawable.intrinsicHeight.coerceAtLeast(1),
                statusWidth = label.statusDrawable.intrinsicWidth.coerceAtLeast(1),
                statusHeight = label.statusDrawable.intrinsicHeight.coerceAtLeast(1)
            )
        }
        val layouts = layoutDroneLabelGroups(
            labels = layoutInputs,
            viewportWidth = mapView.width.takeIf { it > 0 } ?: canvas.width,
            viewportHeight = mapView.height.takeIf { it > 0 } ?: canvas.height
        )
        layouts.zip(labels).forEach { (layout, label) ->
            layout.leaderLine?.let { line ->
                canvas.drawLine(
                    line.startX.toFloat(),
                    line.startY.toFloat(),
                    line.endX.toFloat(),
                    line.endY.toFloat(),
                    leaderHaloPaint
                )
                canvas.drawLine(
                    line.startX.toFloat(),
                    line.startY.toFloat(),
                    line.endX.toFloat(),
                    line.endY.toFloat(),
                    leaderPaint
                )
            }
            label.nameDrawable.bounds = layout.nameBounds.toAndroidRect()
            label.nameDrawable.draw(canvas)
            label.statusDrawable.bounds = layout.statusBounds.toAndroidRect()
            label.statusDrawable.draw(canvas)
        }
    }
}

private fun LabelRect.toAndroidRect(): Rect =
    Rect(left, top, right, bottom)

internal fun distanceFeetFromTakeoff(
    point: DroneMapPoint,
    lat: Double = point.lat,
    lng: Double = point.lng
): Double? {
    val spec = point.droneSpec ?: return null
    if (!spec.hasTakeoffLocation()) return null
    val takeoffLat = spec.takeoffLat
    val takeoffLng = spec.takeoffLng
    if (!takeoffLat.isFinite() || !takeoffLng.isFinite()) return null
    if (!lat.isFinite() || !lng.isFinite()) return null
    val result = FloatArray(1)
    Location.distanceBetween(takeoffLat, takeoffLng, lat, lng, result)
    return if (result[0].isFinite()) result[0].toDouble() * METERS_TO_FEET else null
}

internal fun destinationPoint(
    startLat: Double,
    startLng: Double,
    bearingDeg: Double,
    distanceM: Double
): GeoPoint {
    val earthRadiusM = 6_371_000.0
    val angularDistance = distanceM / earthRadiusM
    val bearing = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(startLat)
    val lon1 = Math.toRadians(startLng)

    val sinLat1 = kotlin.math.sin(lat1)
    val cosLat1 = kotlin.math.cos(lat1)
    val sinAngular = kotlin.math.sin(angularDistance)
    val cosAngular = kotlin.math.cos(angularDistance)

    val lat2 = kotlin.math.asin(
        sinLat1 * cosAngular + cosLat1 * sinAngular * kotlin.math.cos(bearing)
    )
    val lon2 = lon1 + kotlin.math.atan2(
        kotlin.math.sin(bearing) * sinAngular * cosLat1,
        cosAngular - sinLat1 * kotlin.math.sin(lat2)
    )

    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

private fun normalizeDegrees(value: Double): Double {
    val normalized = value % 360.0
    return if (normalized < 0.0) normalized + 360.0 else normalized
}

internal data class ScreenLine(
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double
)

internal fun droneStatusLabelText(
    atoFeet: Double?,
    aglFeet: Double?,
    aglStale: Boolean,
    rangeFeet: Double?,
    headingDeg: Double?,
    headingLabel: String = "HDG",
): String {
    val ato = atoFeet
        ?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }
        ?.let { String.format(Locale.US, "%.0f", it) }
        ?: "--"
    val agl = aglFeet
        ?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }
        ?.let { String.format(Locale.US, "%.0f%s", it, if (aglStale) "?" else "") }
        ?: "--"
    val range = rangeFeet
        ?.let { String.format(Locale.US, "%.0f", it) }
        ?: "--"
    val heading = headingDeg
        ?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.US, "%.0f", normalizeDegrees(it)) }
        ?: "--"
    return "ATO:$ato' AGL:$agl' RNG:$range' $headingLabel:$heading°"
}

internal fun droneDetailLines(
    locationText: String,
    coordinateFormatLabel: String,
    atoFeet: Double?,
    aglFeet: Double?,
    aglStale: Boolean,
    rangeFeet: Double?,
    headingDeg: Double?,
    speedKnots: Double? = null,
    climbFpm: Double? = null
): List<String> = buildList {
    val ato = atoFeet
        ?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }
        ?.let { String.format(Locale.US, "%.0f'", it) }
        ?: "--"
    val agl = aglFeet
        ?.takeIf { kotlin.math.abs(it) <= LABEL_MAX_ABS_FEET }
        ?.let { String.format(Locale.US, "%.0f%s'", it, if (aglStale) "?" else "") }
        ?: "--"
    val range = rangeFeet
        ?.let { String.format(Locale.US, "%.0f'", it) }
        ?: "--"
    val heading = headingDeg
        ?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.US, "%.0f°", normalizeDegrees(it)) }
        ?: "--"
    add("Location: $locationText ($coordinateFormatLabel)")
    add("ATO: $ato")
    add("AGL: $agl")
    add("RNG: $range")
    add("HDG: $heading")
    speedKnots?.let { add(String.format(Locale.US, "Speed: %.1f kt", it)) }
    climbFpm?.let { add(String.format(Locale.US, "Climb: %.0f fpm", it)) }
}

internal fun bearingLineToViewportEdge(
    startX: Double,
    startY: Double,
    headingDeg: Double?,
    viewportWidth: Int,
    viewportHeight: Int
): ScreenLine? {
    val heading = headingDeg?.takeIf { it.isFinite() } ?: return null
    if (viewportWidth <= 0 || viewportHeight <= 0) return null
    val radians = Math.toRadians(normalizeDegrees(heading))
    val dx = kotlin.math.sin(radians)
    val dy = -kotlin.math.cos(radians)
    val candidates = mutableListOf<Double>()
    if (dx > 0.0) candidates += (viewportWidth.toDouble() - startX) / dx
    if (dx < 0.0) candidates += (0.0 - startX) / dx
    if (dy > 0.0) candidates += (viewportHeight.toDouble() - startY) / dy
    if (dy < 0.0) candidates += (0.0 - startY) / dy
    val distance = candidates
        .filter { it > 0.0 && it.isFinite() }
        .minOrNull()
        ?: return null
    return ScreenLine(
        startX = startX,
        startY = startY,
        endX = startX + dx * distance,
        endY = startY + dy * distance
    )
}

internal const val MINIMUM_TRAVEL_BEARING_DISPLACEMENT_METERS = 2.0

/**
 * Uses the latest displacement an operator can also see in the marker track.
 * Duplicate/stationary samples retain the last movement course.
 */
internal fun travelBearingDegrees(
    points: List<LocalTrackPoint>,
    minimumDisplacementMeters: Double = MINIMUM_TRAVEL_BEARING_DISPLACEMENT_METERS
): Double? {
    if (!minimumDisplacementMeters.isFinite() || minimumDisplacementMeters <= 0.0) return null
    val latest = points.lastOrNull()?.takeIf { it.lat.isFinite() && it.lng.isFinite() } ?: return null
    for (index in points.lastIndex - 1 downTo 0) {
        val earlier = points[index]
        if (!earlier.lat.isFinite() || !earlier.lng.isFinite()) continue
        val distance = travelDistanceMeters(earlier.lat, earlier.lng, latest.lat, latest.lng)
        if (distance < minimumDisplacementMeters) continue
        return initialTravelBearingDegrees(earlier.lat, earlier.lng, latest.lat, latest.lng)
    }
    return null
}

private fun travelDistanceMeters(lat1Degrees: Double, lng1Degrees: Double, lat2Degrees: Double, lng2Degrees: Double): Double {
    val earthRadiusMeters = 6_371_008.8
    val lat1 = Math.toRadians(lat1Degrees)
    val lat2 = Math.toRadians(lat2Degrees)
    val deltaLat = Math.toRadians(lat2Degrees - lat1Degrees)
    val deltaLng = Math.toRadians(lng2Degrees - lng1Degrees)
    val sinHalfLat = kotlin.math.sin(deltaLat / 2.0)
    val sinHalfLng = kotlin.math.sin(deltaLng / 2.0)
    val a = sinHalfLat * sinHalfLat +
        kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * sinHalfLng * sinHalfLng
    return earthRadiusMeters * 2.0 * kotlin.math.atan2(
        kotlin.math.sqrt(a),
        kotlin.math.sqrt((1.0 - a).coerceAtLeast(0.0))
    )
}

private fun initialTravelBearingDegrees(
    lat1Degrees: Double,
    lng1Degrees: Double,
    lat2Degrees: Double,
    lng2Degrees: Double
): Double? {
    val lat1 = Math.toRadians(lat1Degrees)
    val lat2 = Math.toRadians(lat2Degrees)
    val deltaLng = Math.toRadians(lng2Degrees - lng1Degrees)
    val y = kotlin.math.sin(deltaLng) * kotlin.math.cos(lat2)
    val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
        kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(deltaLng)
    if (x == 0.0 && y == 0.0) return null
    return normalizeDegrees(Math.toDegrees(kotlin.math.atan2(y, x)))
}

private fun polarPoint(
    cx: Float,
    cy: Float,
    distancePx: Float,
    bearingDeg: Double
): Pair<Float, Float> {
    val radians = Math.toRadians(bearingDeg - 90.0)
    val x = cx + (kotlin.math.cos(radians) * distancePx).toFloat()
    val y = cy + (kotlin.math.sin(radians) * distancePx).toFloat()
    return x to y
}

internal fun buildDroneStatusLabelDrawable(
    resources: Resources,
    text: String
): Drawable {
    val density = resources.displayMetrics.density
    val scaledDensity = density * resources.configuration.fontScale
    val textSizePx = 13f * scaledDensity
    val cornerPx = 5f * density
    val horizontalPaddingPx = 5f * density
    val verticalPaddingPx = 2.5f * density

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFFFFF")
        textSize = textSizePx
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
    }
    val haloPaint = Paint(fillPaint).apply {
        color = AndroidColor.parseColor("#CC000000")
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeJoin = Paint.Join.ROUND
    }
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#66000000")
        style = Paint.Style.FILL
    }
    val fm = fillPaint.fontMetrics
    val textWidth = fillPaint.measureText(text)
    val textHeight = fm.descent - fm.ascent
    val width = maxOf(1, (textWidth + (horizontalPaddingPx * 2f)).toInt())
    val height = maxOf(1, (textHeight + (verticalPaddingPx * 2f)).toInt())
    val baselineY = verticalPaddingPx - fm.ascent
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), cornerPx, cornerPx, bgPaint)
    canvas.drawText(text, horizontalPaddingPx, baselineY, haloPaint)
    canvas.drawText(text, horizontalPaddingPx, baselineY, fillPaint)
    return BitmapDrawable(resources, bitmap)
}

internal fun buildDroneNameLabelDrawable(
    resources: Resources,
    text: String
): Drawable {
    val density = resources.displayMetrics.density
    val scaledDensity = density * resources.configuration.fontScale
    val textSizePx = 16f * scaledDensity
    val cornerPx = 6f * density
    val horizontalPaddingPx = 6f * density
    val verticalPaddingPx = 3f * density

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFFFFF")
        textSize = textSizePx
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
    }
    val haloPaint = Paint(fillPaint).apply {
        color = AndroidColor.parseColor("#CC000000")
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeJoin = Paint.Join.ROUND
    }
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#66000000")
        style = Paint.Style.FILL
    }
    val fm = fillPaint.fontMetrics
    val textWidth = fillPaint.measureText(text)
    val textHeight = fm.descent - fm.ascent
    val width = maxOf(1, (textWidth + (horizontalPaddingPx * 2f)).toInt())
    val height = maxOf(1, (textHeight + (verticalPaddingPx * 2f)).toInt())
    val baselineY = verticalPaddingPx - fm.ascent
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), cornerPx, cornerPx, bgPaint)
    canvas.drawText(text, horizontalPaddingPx, baselineY, haloPaint)
    canvas.drawText(text, horizontalPaddingPx, baselineY, fillPaint)
    return BitmapDrawable(resources, bitmap)
}

internal fun buildDroneMarkerDrawable(
    resources: Resources,
    baseIcon: Drawable?,
    tint: Int?,
    headingDeg: Double?,
    scale: Float = 1.0f
): Drawable? {
    if (baseIcon == null) return null
    val safeScale = drawableScaleOrDefault(scale)
    val density = resources.displayMetrics.density
    val icon = baseIcon.constantState?.newDrawable(resources)?.mutate() ?: baseIcon.mutate()
    if (tint != null) {
        icon.setTint(tint)
    } else {
        icon.clearColorFilter()
    }

    val iconW = if (icon.intrinsicWidth > 0) {
        scaledDimension(icon.intrinsicWidth, safeScale)
    } else {
        (18f * density * safeScale).roundToInt().coerceAtLeast(1)
    }
    val iconH = if (icon.intrinsicHeight > 0) {
        scaledDimension(icon.intrinsicHeight, safeScale)
    } else {
        (18f * density * safeScale).roundToInt().coerceAtLeast(1)
    }
    val overlayReach = (18f * density * safeScale).roundToInt().coerceAtLeast(1)
    val pad = maxOf((4f * density * safeScale).roundToInt().coerceAtLeast(1), overlayReach)
    val width = iconW + pad * 2
    val height = iconH + pad * 2
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = width / 2f
    val cy = height / 2f
    val scaledDensity = density * safeScale
    val radius = (maxOf(iconW, iconH) / 2f) + (2.5f * scaledDensity)
    val headingStart = radius + (0.5f * scaledDensity)
    val headingTip = radius + (9.0f * scaledDensity)
    val headingPointerLen = 3.8f * scaledDensity

    val haloFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#CCFFFFFF")
        style = Paint.Style.FILL
    }
    val haloStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#99000000")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * scaledDensity
    }
    val overlayHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#B3000000")
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * scaledDensity
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#FFF8E1")
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * scaledDensity
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    headingDeg?.takeIf { it.isFinite() }?.let { bearing ->
        val normalizedBearing = normalizeDegrees(bearing)
        val (startX, startY) = polarPoint(cx, cy, headingStart, normalizedBearing)
        val (tipX, tipY) = polarPoint(cx, cy, headingTip, normalizedBearing)
        val (leftX, leftY) = polarPoint(tipX, tipY, headingPointerLen, normalizedBearing - 150.0)
        val (rightX, rightY) = polarPoint(tipX, tipY, headingPointerLen, normalizedBearing + 150.0)
        canvas.drawLine(startX, startY, tipX, tipY, overlayHalo)
        canvas.drawLine(leftX, leftY, tipX, tipY, overlayHalo)
        canvas.drawLine(rightX, rightY, tipX, tipY, overlayHalo)
        canvas.drawLine(startX, startY, tipX, tipY, headingPaint)
        canvas.drawLine(leftX, leftY, tipX, tipY, headingPaint)
        canvas.drawLine(rightX, rightY, tipX, tipY, headingPaint)
    }

    canvas.drawCircle(cx, cy, radius, haloFill)
    canvas.drawCircle(cx, cy, radius, haloStroke)

    icon.setBounds(pad, pad, pad + iconW, pad + iconH)
    icon.draw(canvas)
    return BitmapDrawable(resources, bitmap)
}

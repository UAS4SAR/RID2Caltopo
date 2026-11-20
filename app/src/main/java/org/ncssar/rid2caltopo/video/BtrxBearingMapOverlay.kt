package org.ncssar.rid2caltopo.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.os.SystemClock
import android.view.MotionEvent
import java.nio.ByteBuffer
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.ncssar.rid2caltopo.btrx.BtrxBearingDisplay
import org.ncssar.rid2caltopo.btrx.BtrxBearingDrawMode
import org.ncssar.rid2caltopo.btrx.BtrxBearingSnapshot
import org.ncssar.rid2caltopo.btrx.BtrxBearingView
import org.ncssar.rid2caltopo.btrx.BtrxLabelLayout
import org.ncssar.rid2caltopo.btrx.BtrxBearingSource
import org.ncssar.rid2caltopo.btrx.BtrxBearingText
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

/**
 * BTRX bearing layer on the osmdroid map (docs/btrx/BEARING-LAYER-spec.md sections 5,
 * 5.1, 5.2): the library's composite raster on the snapshot bounds, then per unmuted
 * source the latest-ray outline (stroke only, fading over 5 s; dashed mirror), the
 * estimate with its 1 sigma / 2 sigma ellipses, and the label. One overlay draws all of
 * it from the current snapshot (no per-frame allocation besides the bitmap swap).
 *
 * Taps: only [onSingleTapConfirmed] (after the double-tap timeout); double taps, scroll,
 * fling and pinch are not overridden. It sits below the Marker overlays (drone, waypoint,
 * clue) and above the assignment Polygon/Polyline overlays and the MapEventsOverlay, and
 * also declines when a visible Marker is hit. It consumes the tap only when a source has
 * shading there (muted included), or a pinned-source marker is hit.
 *
 * Nov 16 (watch test): the fine raster (2.5 m cells) over the coarse one; wedges as long as
 * the RSSI range says, both lobes alike when ambiguous; labels inside the shading; a
 * distinct marker for a pinned source (tap: inspect card); for the focused source its
 * strength trail along the flight path and a "strongest here" marker.
 *
 * Nov 20 (back-deck test, 40 devices at takeoff): focus by class or maker dims or hides the
 * rest; estimates wider than the Developer Tools limit and labels of thin sources are not
 * drawn; labels are placed around their anchor so they overlap neither each other nor the
 * drone labels (the previous frame's boxes, [DroneLabelScreenRects]); a label with no free
 * spot is left out.
 */
class BtrxBearingMapOverlay(
    private val density: Float,
    private val sourcesAt: (lat: Double, lon: Double, radiusM: Float) -> List<BtrxBearingSource>,
    private val onSourcesTapped: (keys: List<Long>, x: Float, y: Float) -> Unit,
    private val onPinnedTapped: (key: Long) -> Unit = {},
) : Overlay() {
    @Volatile var tapsEnabled = true
    /** Developer Tools thresholds (hide wide estimates, thin labels). */
    @Volatile var display = BtrxBearingDisplay()
    private val labelItems = ArrayList<BtrxLabelLayout.Item>()
    private val labelTexts = HashMap<Long, String>()
    private var snapshot: BtrxBearingSnapshot? = null
    private var bitmap: Bitmap? = null
    private var fineBitmap: Bitmap? = null
    private val matrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val pt = Point()
    private val path = Path()
    private val rasterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density }
    private val mirrorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val ellipsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * density; color = 0xCCFFFFFF.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * density; color = 0xFF000000.toInt() }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9FFFFFF.toInt() }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val pinFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density; color = 0xFFD50000.toInt() }

    /** Main thread: show [snap] with its prebuilt [bmp] and [fineBmp] (built off the main thread by [bitmapOf]). */
    fun show(snap: BtrxBearingSnapshot?, bmp: Bitmap?, fineBmp: Bitmap? = null) {
        snapshot = snap
        bitmap = bmp
        fineBitmap = fineBmp
    }

    private fun drawRaster(c: Canvas, proj: org.osmdroid.views.Projection, bmp: Bitmap, n: Double, s: Double, e: Double, w: Double) {
        // Corners by projection (handles map rotation): NW, NE, SE, SW.
        val corners = arrayOf(n to w, n to e, s to e, s to w)
        corners.forEachIndexed { i, (lat, lon) ->
            proj.toPixels(GeoPoint(lat, lon), pt)
            dst[2 * i] = pt.x.toFloat(); dst[2 * i + 1] = pt.y.toFloat()
        }
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        src[0] = 0f; src[1] = 0f; src[2] = bw; src[3] = 0f; src[4] = bw; src[5] = bh; src[6] = 0f; src[7] = bh
        matrix.setPolyToPoly(src, 0, dst, 0, 4)
        c.drawBitmap(bmp, matrix, rasterPaint)
    }

    /** True while a latest-ray outline is still fading (the map redraws it a few times a second). */
    fun hasFadingRay(nowUs: Long): Boolean = snapshot?.sources?.any { s ->
        !s.muted && s.latestRay?.let { nowUs - it.tUs in 0 until RAY_FADE_US } == true
    } == true

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val snap = snapshot ?: return
        val proj = mapView.projection
        bitmap?.let { drawRaster(c, proj, it, snap.north, snap.south, snap.east, snap.west) }
        val fine = snap.fine
        fineBitmap?.let { if (fine != null) drawRaster(c, proj, it, fine.north, fine.south, fine.east, fine.west) }
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000L
        drawTrail(c, proj, snap)
        val d = display
        labelItems.clear(); labelTexts.clear()
        for (s in snap.sources) {
            val mode = BtrxBearingView.drawMode(s, snap)
            if (mode == BtrxBearingDrawMode.HIDDEN) continue
            val dim = mode == BtrxBearingDrawMode.DIM
            val color = 0xFF000000.toInt() or s.rgb
            s.latestRay?.let { ray ->
                val age = nowUs - ray.tUs
                if (age in 0 until RAY_FADE_US) {
                    val a = ((if (dim) 90 else 255) * (1f - age.toFloat() / RAY_FADE_US)).toInt().coerceIn(0, 255)
                    rayPaint.color = color; rayPaint.alpha = a
                    proj.toPixels(GeoPoint(ray.apexLat, ray.apexLon), pt)
                    val ax = pt.x.toFloat(); val ay = pt.y.toFloat()
                    path.reset()
                    for (b in floatArrayOf(ray.centreTrueDeg - ray.halfWidthDeg, ray.centreTrueDeg, ray.centreTrueDeg + ray.halfWidthDeg)) {
                        val end = destination(ray.apexLat, ray.apexLon, b.toDouble(), ray.lengthM.toDouble())
                        proj.toPixels(end, pt)
                        path.moveTo(ax, ay); path.lineTo(pt.x.toFloat(), pt.y.toFloat())
                    }
                    c.drawPath(path, rayPaint)
                    ray.mirrorTrueDeg?.let { m ->
                        if (ray.ambiguous) {
                            // Front and back about equally likely: the same full wedge both ways.
                            path.reset()
                            for (b in floatArrayOf(m - ray.halfWidthDeg, m, m + ray.halfWidthDeg)) {
                                val end = destination(ray.apexLat, ray.apexLon, b.toDouble(), ray.lengthM.toDouble())
                                proj.toPixels(end, pt)
                                path.moveTo(ax, ay); path.lineTo(pt.x.toFloat(), pt.y.toFloat())
                            }
                            c.drawPath(path, rayPaint)
                        } else {
                            mirrorPaint.color = color; mirrorPaint.alpha = a
                            val end = destination(ray.apexLat, ray.apexLon, m.toDouble(), ray.lengthM.toDouble())
                            proj.toPixels(end, pt)
                            c.drawLine(ax, ay, pt.x.toFloat(), pt.y.toFloat(), mirrorPaint)
                        }
                    }
                }
            }
            val est = s.estimate?.takeIf { BtrxBearingView.estimateShown(s, d, snap.focusKey) }
            val wantLabel = !dim && BtrxBearingView.labelShown(s, d, snap.focusKey)
            if (est != null) {
                for (k in 1..2) {
                    ellipsePath(proj, est.lat, est.lon, k * est.axisMajor1sM, k * est.axisMinor1sM, est.axisMajorDeg)
                    c.drawPath(path, haloPaint)
                    ellipsePaint.color = color; ellipsePaint.alpha = (if (k == 1) 255 else 170) * (if (dim) 1 else 3) / 3
                    c.drawPath(path, ellipsePaint)
                }
                proj.toPixels(GeoPoint(est.lat, est.lon), pt)
                if (s.pinned) drawPin(c, pt.x.toFloat(), pt.y.toFloat(), color, dim)
                else {
                    dotPaint.color = color
                    c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 4f * density, haloPaint)
                    c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 4f * density, dotPaint)
                }
                if (wantLabel) addLabel(s.key, BtrxBearingText.label(s), pt.x.toFloat(), pt.y.toFloat(), BtrxBearingView.labelPriority(s, snap))
            } else if (!wantLabel) {
                // no label
            } else if (s.labelLat != null && s.labelLon != null) {
                // Inside the shading (Nov 16: labels sat 240 m out along a fixed 400 m ray).
                proj.toPixels(GeoPoint(s.labelLat, s.labelLon), pt)
                addLabel(s.key, BtrxBearingText.label(s), pt.x.toFloat(), pt.y.toFloat(), BtrxBearingView.labelPriority(s, snap))
            } else s.latestRay?.let { ray ->
                val end = destination(ray.apexLat, ray.apexLon, ray.centreTrueDeg.toDouble(), ray.lengthM.toDouble() * 0.6)
                proj.toPixels(end, pt)
                addLabel(s.key, BtrxBearingText.label(s), pt.x.toFloat(), pt.y.toFloat(), BtrxBearingView.labelPriority(s, snap))
            }
        }
        val obstacles = ArrayList<BtrxLabelLayout.Box>(DroneLabelScreenRects.recent())
        // "Strongest here" for the focused source, on top.
        snap.sources.firstOrNull { it.key == snap.focusKey && snap.focusKey != 0L }?.let { f ->
            val lat = f.strongestLat ?: return@let
            val lon = f.strongestLon ?: return@let
            proj.toPixels(GeoPoint(lat, lon), pt)
            val x = pt.x.toFloat(); val y = pt.y.toFloat(); val r = 9f * density
            c.drawLine(x - r, y - r, x + r, y + r, haloPaint); c.drawLine(x - r, y + r, x + r, y - r, haloPaint)
            c.drawLine(x - r, y - r, x + r, y + r, starPaint); c.drawLine(x - r, y + r, x + r, y - r, starPaint)
            val text = BtrxBearingText.STRONGEST_HERE + (f.maxRssiDbm?.let { " $it dBm" } ?: "")
            drawLabel(c, text, x + r + 4f * density, y + 4f * density)
            val pad = 3f * density
            obstacles += BtrxLabelLayout.Box(x + r + 4f * density - pad, y + 4f * density - labelPaint.textSize - pad / 2,
                x + r + 4f * density + labelPaint.measureText(text) + pad, y + 4f * density + pad)
        }
        // Labels last, placed clear of each other and of the drone labels (Nov 20).
        val placed = BtrxLabelLayout.place(labelItems, obstacles, 8f * density, 2f * density)
        for ((id, p) in placed) {
            val pad = 3f * density
            labelTexts[id]?.let { drawLabel(c, it, p.box.left + pad, p.box.top + labelPaint.textSize + pad / 2) }
        }
    }

    /** A label to place: its box is the text plus padding, as [drawLabel] draws it. */
    private fun addLabel(id: Long, text: String, ax: Float, ay: Float, priority: Int) {
        val pad = 3f * density
        labelItems += BtrxLabelLayout.Item(id, ax, ay, labelPaint.measureText(text) + 2 * pad, labelPaint.textSize + 1.5f * pad, priority)
        labelTexts[id] = text
    }

    /** Pinned source: a bold ring with a cross and a centre dot, distinct from the estimate dot. */
    private fun drawPin(c: Canvas, x: Float, y: Float, color: Int, dim: Boolean) {
        val r = PIN_RADIUS_DP * density
        c.drawCircle(x, y, r, haloPaint)
        pinPaint.color = color; pinPaint.alpha = if (dim) 120 else 255
        c.drawCircle(x, y, r, pinPaint)
        c.drawLine(x - 1.6f * r, y, x - 0.5f * r, y, pinPaint); c.drawLine(x + 0.5f * r, y, x + 1.6f * r, y, pinPaint)
        c.drawLine(x, y - 1.6f * r, x, y - 0.5f * r, pinPaint); c.drawLine(x, y + 0.5f * r, x, y + 1.6f * r, pinPaint)
        pinFill.color = color; pinFill.alpha = if (dim) 120 else 255
        c.drawCircle(x, y, 3f * density, pinFill)
    }

    /** Focused source: dots along the flight path, blue (weak) to red (strong). */
    private fun drawTrail(c: Canvas, proj: org.osmdroid.views.Projection, snap: BtrxBearingSnapshot) {
        val t = snap.trail
        if (t.isEmpty()) return
        val lo = t.minOf { it.rssiDbm }; val hi = t.maxOf { it.rssiDbm }
        val span = (hi - lo).coerceAtLeast(1)
        for (p in t) {
            proj.toPixels(GeoPoint(p.lat, p.lon), pt)
            val f = (p.rssiDbm - lo).toFloat() / span
            trailPaint.color = android.graphics.Color.argb(230, (255 * f).toInt(), (60 * (1 - f)).toInt(), (255 * (1 - f)).toInt())
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), (2.5f + 2.5f * f) * density, trailPaint)
        }
    }

    private fun drawLabel(c: Canvas, text: String, x: Float, y: Float) {
        val w = labelPaint.measureText(text)
        val pad = 3f * density
        c.drawRoundRect(x - pad, y - labelPaint.textSize - pad / 2, x + w + pad, y + pad, pad, pad, labelBg)
        c.drawText(text, x, y, labelPaint)
    }

    private fun ellipsePath(proj: org.osmdroid.views.Projection, lat: Double, lon: Double, a: Float, b: Float, majorDeg: Float) {
        path.reset()
        val th = Math.toRadians(majorDeg.toDouble())
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        for (i in 0..36) {
            val t = 2 * Math.PI * i / 36
            val u = a * cos(t); val v = b * sin(t)
            // Major axis along true bearing th: north = u cos th - v sin th, east = u sin th + v cos th.
            val n = u * cos(th) - v * sin(th)
            val e = u * sin(th) + v * cos(th)
            proj.toPixels(GeoPoint(lat + n / mPerDegLat, lon + e / mPerDegLon), pt)
            if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
        }
        path.close()
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val snap = snapshot
        if (!tapsEnabled || !isEnabled || snap == null) return false
        // Guard against list-order mistakes: drone, waypoint and clue markers always win.
        if (mapView.overlays.any { it is Marker && it.isEnabled && it.hitTest(e, mapView) }) return false
        val proj = mapView.projection
        // Pinned-source markers first: the inspect card.
        val hitR = (PIN_RADIUS_DP * 2.2f) * density
        snap.sources.filter {
            it.pinned && BtrxBearingView.drawMode(it, snap) != BtrxBearingDrawMode.HIDDEN && BtrxBearingView.estimateShown(it, display, snap.focusKey)
        }.minByOrNull { s ->
            proj.toPixels(GeoPoint(s.estimate!!.lat, s.estimate.lon), pt)
            kotlin.math.hypot(pt.x - e.x, pt.y - e.y)
        }?.let { s ->
            proj.toPixels(GeoPoint(s.estimate!!.lat, s.estimate.lon), pt)
            if (kotlin.math.hypot(pt.x - e.x, pt.y - e.y) <= hitR) { onPinnedTapped(s.key); return true }
        }
        val here = proj.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
        val edge = proj.fromPixels((e.x + TOUCH_RADIUS_DP * density).toInt(), e.y.toInt()) as GeoPoint
        val radiusM = here.distanceToAsDouble(edge).toFloat()
        val hits = sourcesAt(here.latitude, here.longitude, radiusM)
        if (hits.isEmpty()) return false
        onSourcesTapped(hits.map { it.key }, e.x, e.y)
        return true
    }

    companion object {
        const val RAY_FADE_US = 5_000_000L
        /** Spec 5.2: a 24 pt touch radius, converted to metres at the current zoom. */
        const val TOUCH_RADIUS_DP = 24f
        const val PIN_RADIUS_DP = 9f

        /** Off the main thread: the snapshot's premultiplied RGBA as an ARGB_8888 bitmap. */
        fun bitmapOf(snap: BtrxBearingSnapshot): Bitmap? {
            if (snap.width <= 0 || snap.height <= 0 || snap.rgba.size < snap.width * snap.height * 4) return null
            return Bitmap.createBitmap(snap.width, snap.height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(snap.rgba, 0, snap.width * snap.height * 4))
            }
        }

        /** Off the main thread: the fine layer raster as a bitmap, null when there is none. */
        fun fineBitmapOf(snap: BtrxBearingSnapshot): Bitmap? {
            val f = snap.fine ?: return null
            if (f.cells <= 0 || f.rgba.size < f.cells * f.cells * 4) return null
            return Bitmap.createBitmap(f.cells, f.cells, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(f.rgba, 0, f.cells * f.cells * 4))
            }
        }

        /**
         * The map box to zoom to for "Focus this source": its estimate's 2 sigma ellipse, the
         * strongest-here point, its label point and the latest ray apex; at least [minSpanM].
         */
        fun focusBounds(s: BtrxBearingSource, minSpanM: Double = 150.0): org.osmdroid.util.BoundingBox? {
            val pts = mutableListOf<Pair<Double, Double>>()
            s.estimate?.let { e ->
                val r = 2.0 * e.axisMajor1sM
                val dLat = r / 111_320.0; val dLon = r / (111_320.0 * cos(Math.toRadians(e.lat)))
                pts += (e.lat + dLat) to (e.lon + dLon); pts += (e.lat - dLat) to (e.lon - dLon)
            }
            if (s.strongestLat != null && s.strongestLon != null) pts += s.strongestLat to s.strongestLon
            if (s.labelLat != null && s.labelLon != null) pts += s.labelLat to s.labelLon
            s.latestRay?.let { pts += it.apexLat to it.apexLon }
            if (pts.isEmpty()) return null
            var n = pts.maxOf { it.first }; var so = pts.minOf { it.first }
            var e = pts.maxOf { it.second }; var w = pts.minOf { it.second }
            val cLat = (n + so) / 2; val cLon = (e + w) / 2
            val halfLat = maxOf((n - so) / 2, minSpanM / 2 / 111_320.0)
            val halfLon = maxOf((e - w) / 2, minSpanM / 2 / (111_320.0 * cos(Math.toRadians(cLat))))
            n = cLat + halfLat; so = cLat - halfLat; e = cLon + halfLon; w = cLon - halfLon
            return org.osmdroid.util.BoundingBox(n, e, so, w)
        }

        fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): GeoPoint {
            val r = 6_371_008.8
            val d = distM / r
            val br = Math.toRadians(bearingDeg)
            val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
            val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(br))
            val l2 = l1 + atan2(sin(br) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
            return GeoPoint(Math.toDegrees(p2), Math.toDegrees(l2))
        }
    }
}

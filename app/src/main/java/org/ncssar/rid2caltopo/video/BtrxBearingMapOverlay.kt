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
import org.ncssar.rid2caltopo.btrx.BtrxBearingSnapshot
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
 * shading there (muted included).
 */
class BtrxBearingMapOverlay(
    private val density: Float,
    private val sourcesAt: (lat: Double, lon: Double, radiusM: Float) -> List<BtrxBearingSource>,
    private val onSourcesTapped: (keys: List<Long>, x: Float, y: Float) -> Unit,
) : Overlay() {
    @Volatile var tapsEnabled = true
    private var snapshot: BtrxBearingSnapshot? = null
    private var bitmap: Bitmap? = null
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

    /** Main thread: show [snap] with its prebuilt [bmp] (built off the main thread by [bitmapOf]). */
    fun show(snap: BtrxBearingSnapshot?, bmp: Bitmap?) {
        snapshot = snap
        bitmap = bmp
    }

    /** True while a latest-ray outline is still fading (the map redraws it a few times a second). */
    fun hasFadingRay(nowUs: Long): Boolean = snapshot?.sources?.any { s ->
        !s.muted && s.latestRay?.let { nowUs - it.tUs in 0 until RAY_FADE_US } == true
    } == true

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val snap = snapshot ?: return
        val proj = mapView.projection
        bitmap?.let { bmp ->
            // Corners by projection (handles map rotation): NW, NE, SE, SW.
            val corners = arrayOf(snap.north to snap.west, snap.north to snap.east, snap.south to snap.east, snap.south to snap.west)
            corners.forEachIndexed { i, (lat, lon) ->
                proj.toPixels(GeoPoint(lat, lon), pt)
                dst[2 * i] = pt.x.toFloat(); dst[2 * i + 1] = pt.y.toFloat()
            }
            val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
            src[0] = 0f; src[1] = 0f; src[2] = w; src[3] = 0f; src[4] = w; src[5] = h; src[6] = 0f; src[7] = h
            matrix.setPolyToPoly(src, 0, dst, 0, 4)
            c.drawBitmap(bmp, matrix, rasterPaint)
        }
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000L
        for (s in snap.sources) {
            if (s.muted || s.ignored) continue
            val color = 0xFF000000.toInt() or s.rgb
            s.latestRay?.let { ray ->
                val age = nowUs - ray.tUs
                if (age in 0 until RAY_FADE_US) {
                    val a = (255 * (1f - age.toFloat() / RAY_FADE_US)).toInt().coerceIn(0, 255)
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
                        mirrorPaint.color = color; mirrorPaint.alpha = a
                        val end = destination(ray.apexLat, ray.apexLon, m.toDouble(), ray.lengthM.toDouble())
                        proj.toPixels(end, pt)
                        c.drawLine(ax, ay, pt.x.toFloat(), pt.y.toFloat(), mirrorPaint)
                    }
                }
            }
            val est = s.estimate
            if (est != null) {
                for (k in 1..2) {
                    ellipsePath(proj, est.lat, est.lon, k * est.axisMajor1sM, k * est.axisMinor1sM, est.axisMajorDeg)
                    c.drawPath(path, haloPaint)
                    ellipsePaint.color = color; ellipsePaint.alpha = if (k == 1) 255 else 170
                    c.drawPath(path, ellipsePaint)
                }
                proj.toPixels(GeoPoint(est.lat, est.lon), pt)
                dotPaint.color = color
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 4f * density, haloPaint)
                c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 4f * density, dotPaint)
                drawLabel(c, BtrxBearingText.label(s), pt.x + 8f * density, pt.y - 8f * density)
            } else s.latestRay?.let { ray ->
                val end = destination(ray.apexLat, ray.apexLon, ray.centreTrueDeg.toDouble(), ray.lengthM.toDouble() * 0.6)
                proj.toPixels(end, pt)
                drawLabel(c, BtrxBearingText.label(s), pt.x.toFloat(), pt.y.toFloat())
            }
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
        if (!tapsEnabled || !isEnabled || snapshot == null) return false
        // Guard against list-order mistakes: drone, waypoint and clue markers always win.
        if (mapView.overlays.any { it is Marker && it.isEnabled && it.hitTest(e, mapView) }) return false
        val proj = mapView.projection
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

        /** Off the main thread: the snapshot's premultiplied RGBA as an ARGB_8888 bitmap. */
        fun bitmapOf(snap: BtrxBearingSnapshot): Bitmap? {
            if (snap.width <= 0 || snap.height <= 0 || snap.rgba.size < snap.width * snap.height * 4) return null
            return Bitmap.createBitmap(snap.width, snap.height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(snap.rgba, 0, snap.width * snap.height * 4))
            }
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

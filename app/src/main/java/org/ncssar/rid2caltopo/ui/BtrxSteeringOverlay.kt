package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodText
import org.ncssar.rid2caltopo.btrx.BtrxManager
import org.ncssar.rid2caltopo.btrx.BtrxProtocol
import org.ncssar.rid2caltopo.btrx.BtrxSteering
import org.ncssar.rid2caltopo.btrx.BtrxSteeringTarget
import org.ncssar.rid2caltopo.btrx.BtrxTelemetryRoute

/**
 * The pilot's steering overlay on a live stream tile (btrx design doc section 41): one
 * circle and arrow in the source's color (the hint word only as a small label), no numbers. Tapping the
 * circle (or "Mute") mutes that source on the pod. Shows only while the Desk Pod link is
 * up, a Drone Pod is paired and STEER keeps arriving, and only on the drone carrying the
 * pod ([BtrxSteeringTarget]): the single live tile, or with several live the one marked
 * "Drone Pod is on this drone". The card shows only while the stream is the primary window
 * (BTRX telemetry follows it, [BtrxManager.telemetryWindow]; Ken, Nov 23: no hide).
 * Same as the iPad's AppleSteeringOverlay.
 */
@Composable
fun BtrxSteeringOverlay(tile: String, liveTiles: Set<String>, chosenTile: String?, modifier: Modifier = Modifier) {
    val s by BtrxManager.snapshot.collectAsState()
    val window by BtrxManager.telemetryWindow.collectAsState()
    val mapStatus by BtrxManager.mapStatus.collectAsState()
    val v = s.steerOverlay
    val mode = BtrxSteeringTarget.tileOverlay(tile, liveTiles, chosenTile, v != null,
        onStream = BtrxTelemetryRoute.streamShows(window, mapStatus?.active == true))
    if (v == null || mode == BtrxSteeringTarget.TileOverlay.NONE) return
    if (mode != BtrxSteeringTarget.TileOverlay.CARD) {
        // Several live streams and none marked yet: offer the pick on each live tile.
        if (mode == BtrxSteeringTarget.TileOverlay.PICK) {
            Text(
                BtrxDeskPodText.STEER_PICK,
                color = Color.White,
                fontSize = 13.sp,
                modifier = modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .clickable { BtrxManager.chooseSteeringStream(tile) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        return
    }
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The arrow carries the meaning; the word is a small secondary label only.
        SteeringMark(
            v, 132.dp,
            Modifier
                .semantics { contentDescription = "${v.hint.word}. ${BtrxDeskPodText.MUTE}" }
                .clickable(enabled = v.canMute) { BtrxManager.muteSteeringSource() },
        )
        Text(v.hint.word, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
        // Clue from STEER company_id and dev_class, e.g. "Apple · wearable".
        Text(v.clue, color = Color.White, fontSize = 12.sp, maxLines = 1)
        if (v.canMute) {
            Text(
                BtrxDeskPodText.MUTE,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                    .clickable { BtrxManager.muteSteeringSource() }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Circle (shape by device kind) and the pilot's arrow ([BtrxSteering.arrowGeometry]) in the
 * source's palette color: the arrow points the way to turn (up = straight on, U-turn = turn
 * around) and is longer the weaker (farther) the source; a chevron behind the head means
 * closing. Icons instead of words: LEVEL a gray arrow and a wings-level badge, LOST a slashed
 * circle and no arrow, muted a crossed-out speaker. Also the Desk Pod screen's swatch.
 * Same drawing as the iPad's SteeringMark.
 */
@Composable
fun SteeringMark(v: BtrxSteering.View, size: Dp, modifier: Modifier = Modifier) {
    val color = steeringColor(v.colorIndex)
    val vectorColor = if (v.gray) Color(0xFF000000 or BtrxSteering.GRAY.toLong()) else color
    val outline = Color(0xFF000000 or BtrxSteering.OUTLINE.toLong())
    Canvas(modifier.size(size).alpha(if (v.faded) 0.4f else 1f)) {
        val h = this.size.minDimension / 2f
        val c = center
        fun pt(p: BtrxSteering.Pt) = Offset(c.x + p.x.toFloat() * h, c.y + p.y.toFloat() * h)
        fun poly(points: List<BtrxSteering.Pt>) = Path().apply {
            points.forEachIndexed { i, p -> val o = pt(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        }
        val unit = h / 66f // stroke widths scale with the mark (66 = half of the 132 dp overlay)
        val r = BtrxSteering.MARK_CIRCLE_R.toFloat() * h
        drawKindCircle(v.devClass, c, r, color, outline)
        val dir = v.vector
        if (dir == null) {
            // LOST: slash through the circle and a dashed ring; no arrow.
            val d = r * 1.5f
            drawCircle(outline, d, c, style = Stroke(5f * unit))
            drawCircle(Color.White, d, c, style = Stroke(3f * unit, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * unit, 5f * unit))))
            val a = Offset(c.x - d * 0.72f, c.y - d * 0.72f)
            val b = Offset(c.x + d * 0.72f, c.y + d * 0.72f)
            drawLine(outline, a, b, strokeWidth = 7f * unit, cap = StrokeCap.Round)
            drawLine(Color.White, a, b, strokeWidth = 4f * unit, cap = StrokeCap.Round)
        } else {
            val arrow = BtrxSteering.arrowGeometry(dir, v.length, v.closer)
            val shaft = poly(arrow.shaft)
            drawPath(shaft, outline, style = Stroke(12f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(shaft, vectorColor, style = Stroke(8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val head = poly(arrow.head).apply { close() }
            drawPath(head, vectorColor)
            drawPath(head, outline, style = Stroke(2f * unit, join = StrokeJoin.Round))
            if (arrow.chevron.isNotEmpty()) {
                val chev = poly(arrow.chevron)
                drawPath(chev, outline, style = Stroke(8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(chev, vectorColor, style = Stroke(5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (v.hint == BtrxSteering.Hint.LEVEL) drawLevelBadge(Offset(c.x - h * 0.72f, c.y + h * 0.78f), h * 0.2f, unit, outline)
        if (v.muted) drawMutedBadge(Offset(c.x + h * 0.72f, c.y + h * 0.78f), h * 0.17f, unit, outline)
    }
}

/** LEVEL badge: an attitude-indicator ring with a level wings line through it ("level the wings"). */
private fun DrawScope.drawLevelBadge(c: Offset, s: Float, unit: Float, outline: Color) {
    drawCircle(outline, s, c, style = Stroke(5f * unit))
    drawCircle(Color.White, s, c, style = Stroke(3f * unit))
    val a = Offset(c.x - s * 1.6f, c.y)
    val b = Offset(c.x + s * 1.6f, c.y)
    drawLine(outline, a, b, strokeWidth = 6f * unit, cap = StrokeCap.Round)
    drawLine(Color.White, a, b, strokeWidth = 3.5f * unit, cap = StrokeCap.Round)
    drawCircle(Color.White, 2.5f * unit, c)
}

/** Muted badge: a speaker with a cross (a source is muted on the pod; STEER bit 5). */
private fun DrawScope.drawMutedBadge(c: Offset, s: Float, unit: Float, outline: Color) {
    val speaker = Path().apply {
        moveTo(c.x - s * 1.1f, c.y - s * 0.35f)
        lineTo(c.x - s * 0.55f, c.y - s * 0.35f)
        lineTo(c.x, c.y - s * 0.9f)
        lineTo(c.x, c.y + s * 0.9f)
        lineTo(c.x - s * 0.55f, c.y + s * 0.35f)
        lineTo(c.x - s * 1.1f, c.y + s * 0.35f)
        close()
    }
    drawPath(speaker, Color.White)
    drawPath(speaker, outline, style = Stroke(1.5f * unit))
    for ((a, b) in listOf(
        Offset(c.x + s * 0.35f, c.y - s * 0.45f) to Offset(c.x + s * 1.15f, c.y + s * 0.45f),
        Offset(c.x + s * 0.35f, c.y + s * 0.45f) to Offset(c.x + s * 1.15f, c.y - s * 0.45f),
    )) {
        drawLine(outline, a, b, strokeWidth = 5.5f * unit, cap = StrokeCap.Round)
        drawLine(Color.White, a, b, strokeWidth = 3f * unit, cap = StrokeCap.Round)
    }
}

/** Section 41.6, optional shapes: solid phone, ringed wearable/audio, dashed tracker, plain otherwise. */
private fun DrawScope.drawKindCircle(kind: Int, c: Offset, r: Float, color: Color, outline: Color) {
    val stroke = 3.dp.toPx()
    when (kind) {
        BtrxProtocol.DEV_PHONE -> {
            drawCircle(color, r, c)
            drawCircle(outline, r, c, style = Stroke(1.5.dp.toPx()))
        }
        BtrxProtocol.DEV_WEARABLE, BtrxProtocol.DEV_AUDIO -> {
            drawCircle(outline, r, c, style = Stroke(stroke + 2f))
            drawCircle(color, r, c, style = Stroke(stroke))
            drawCircle(color, r * 0.5f, c, style = Stroke(stroke))
        }
        BtrxProtocol.DEV_TRACKER -> {
            drawCircle(outline, r, c, style = Stroke(stroke + 2f))
            drawCircle(color, r, c, style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        }
        else -> {
            drawCircle(outline, r, c, style = Stroke(stroke + 2f))
            drawCircle(color, r, c, style = Stroke(stroke))
        }
    }
}

fun steeringColor(index: Int?): Color {
    val rgb = index?.let { BtrxSteering.PALETTE[it] } ?: BtrxSteering.GRAY
    return Color(0xFF000000 or rgb.toLong())
}

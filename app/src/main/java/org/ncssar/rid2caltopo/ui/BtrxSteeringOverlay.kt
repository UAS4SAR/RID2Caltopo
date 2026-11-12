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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodText
import org.ncssar.rid2caltopo.btrx.BtrxManager
import org.ncssar.rid2caltopo.btrx.BtrxProtocol
import org.ncssar.rid2caltopo.btrx.BtrxSteering

/**
 * The pilot's steering overlay on a live stream tile (btrx design doc section 41): one
 * circle and vector in the source's color and one hint word, no numbers. Tapping the
 * circle (or "Mute") mutes that source on the pod. Shows only while the Desk Pod link is
 * up, a Drone Pod is paired and STEER keeps arriving. Same as the iPad's
 * AppleSteeringOverlay.
 */
@Composable
fun BtrxSteeringOverlay(modifier: Modifier = Modifier) {
    val s by BtrxManager.snapshot.collectAsState()
    val v = s.steerOverlay ?: return
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SteeringMark(
            v, 64.dp,
            Modifier
                .semantics { contentDescription = "${v.hint.word}. ${BtrxDeskPodText.MUTE}" }
                .clickable(enabled = v.canMute) { BtrxManager.muteSteeringSource() },
        )
        Text(v.hint.word, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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

/** Circle (shape by device kind) and vector, in the source's palette color. Also the Desk Pod screen's swatch. */
@Composable
fun SteeringMark(v: BtrxSteering.View, size: Dp, modifier: Modifier = Modifier) {
    val color = steeringColor(v.colorIndex)
    val vectorColor = if (v.gray) Color(0xFF000000 or BtrxSteering.GRAY.toLong()) else color
    val outline = Color(0xFF000000 or BtrxSteering.OUTLINE.toLong())
    Canvas(modifier.size(size).alpha(if (v.faded) 0.4f else 1f)) {
        val r = this.size.minDimension * 0.22f
        val c = center
        drawKindCircle(v.devClass, c, r, color, outline)
        v.vector?.let { dir ->
            val rad = Math.toRadians(dir.degrees.toDouble())
            val len = this.size.minDimension * 0.46f
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val start = Offset(c.x + dx * (r + 2f), c.y + dy * (r + 2f))
            val end = Offset(c.x + dx * len, c.y + dy * len)
            drawLine(outline, start, end, strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
            drawLine(vectorColor, start, end, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            // Arrow head.
            val head = 8.dp.toPx()
            for (side in listOf(-1, 1)) {
                val a = rad + side * Math.toRadians(150.0)
                val p = Offset(end.x + sin(a).toFloat() * head, end.y - cos(a).toFloat() * head)
                drawLine(vectorColor, end, p, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
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

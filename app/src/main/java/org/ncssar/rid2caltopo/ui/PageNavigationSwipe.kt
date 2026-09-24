package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

internal fun isPageNavigationSwipe(dx: Float, dy: Float, toLiveView: Boolean): Boolean =
    (if (toLiveView) -dx else dx) >= 72f && abs(dx) >= 2f * abs(dy)

internal fun shouldClaimPageNavigationSwipe(dx: Float, dy: Float, touchSlop: Float, toLiveView: Boolean): Boolean =
    (if (toLiveView) -dx else dx) > touchSlop && abs(dx) >= 2f * abs(dy)

/** Apply only to navigation chrome, never to maps, video, or scrolling content. */
fun Modifier.pageNavigationSwipe(toLiveView: Boolean, enabled: Boolean = true, onNavigate: () -> Unit): Modifier = composed {
    val callback = rememberUpdatedState(onNavigate)
    val density = LocalDensity.current.density
    var progress by remember { mutableFloatStateOf(0f) }
    val color = MaterialTheme.colorScheme.primary
    if (!enabled) this else drawWithContent {
        drawContent()
        if (progress > 0f) {
            val width = size.width * progress
            drawRect(color, topLeft = Offset(if (toLiveView) size.width - width else 0f, size.height - 3f * density), size = Size(width, 3f * density))
        }
    }.pointerInput(toLiveView, density) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var displacement = Offset.Zero
            var claimed = false
            var outcome = "cancelled"
            try {
                while (true) {
                    // Observe title-bar motion before child buttons handle it. Do not
                    // abandon an unclaimed swipe just because its initial motion wobbles.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) {
                        outcome = "multiple pointers"
                        break
                    }
                    val change = event.changes.first()
                    if (change.id != down.id || change.isConsumed) {
                        outcome = "intercepted"
                        break
                    }
                    displacement += change.position - change.previousPosition
                    if (!change.pressed) {
                        outcome = "below navigation threshold"
                        if (claimed && isPageNavigationSwipe(displacement.x / density, displacement.y / density, toLiveView)) {
                            outcome = "navigate"
                            change.consume()
                            callback.value()
                        }
                        break
                    }
                    val directed = if (toLiveView) -displacement.x else displacement.x
                    if (!claimed) {
                        claimed = shouldClaimPageNavigationSwipe(displacement.x, displacement.y, viewConfiguration.touchSlop, toLiveView)
                    }
                    if (claimed) {
                        progress = (directed / (72f * density)).coerceIn(0f, 1f)
                        change.consume()
                    }
                }
            } finally {
                progress = 0f
                CTDebug("PageNavigationSwipe", "titleBar=${if (toLiveView) "Main" else "LiveView"} outcome=$outcome claimed=$claimed startDp=(${(down.position.x / density).toInt()},${(down.position.y / density).toInt()}) deltaDp=(${(displacement.x / density).toInt()},${(displacement.y / density).toInt()})")
            }
        }
    }
}

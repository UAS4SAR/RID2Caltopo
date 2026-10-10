package org.ncssar.rid2caltopo.video

import StreamsViewModel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.osmdroid.views.MapView

/** Visual updates inside the 10–15 Hz target. Terrain itself stays on the 500 ms worker. */
internal const val CAMERA_FOOTPRINT_FRAME_MS = 80L

/**
 * Latest terrain outlines. Not Compose state: publishing a pass must not rebuild every map overlay.
 */
internal class CameraFootprintTerrainCache {
    @Volatile var value: Map<String, Pair<CameraFootprintInput, List<CameraFootprintVertex>>> = emptyMap()
}

/** Pan/zoom notifies only the footprint HUD. The listener is installed once on the MapView. */
internal class CameraFootprintProjectionClock {
    @Volatile var mapView: MapView? = null
    var onChange: (() -> Unit)? = null
    fun bump() { onChange?.invoke() }
}

internal fun currentCameraFootprintInputs(
    viewModel: StreamsViewModel,
    nowMs: Long = System.currentTimeMillis(),
): Map<String, CameraFootprintInput> {
    val result = LinkedHashMap<String, CameraFootprintInput>()
    viewModel.droneStates.forEach { (designator, state) ->
        if (!viewModel.cameraFootprintEnabled(state.remoteId)) return@forEach
        val designators = viewModel.cameraTelemetryDesignatorsFor(state.remoteId, state.mappedId)
        val camera = designators.firstNotNullOfOrNull { viewModel.cameraFootprintTelemetryFor(it) } ?: return@forEach
        val current = designators.firstNotNullOfOrNull {
            org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetryRegistry.freshOperationalPosition(it, nowMs)
        }
        val reference = viewModel.altitudeCoordinator.cameraFootprintReference(designator) ?: return@forEach
        val azimuth = camera.fovAzimuthDeg
        val cameraLat = camera.latitudeDeg
        val cameraLng = camera.longitudeDeg
        val frameHeight = camera.relativeUpMeters
        val currentHeight = current?.relativeUpMeters
        if (azimuth == null || cameraLat == null || cameraLng == null || frameHeight == null || currentHeight == null) {
            return@forEach
        }
        val scanZone = designators.firstNotNullOfOrNull { id ->
            viewModel.anomalyConfigFor(id).takeIf { it.enabled }?.scanZone?.toDouble()
        }
        result[designator] = cameraFootprintApplyingAdScanZone(
            CameraFootprintInput(
                cameraLat, cameraLng, reference.first, reference.second,
                reference.third + frameHeight - currentHeight,
                azimuth, camera.tiltDeg, camera.horizontalFovDeg, camera.verticalFovDeg,
            ),
            scanZone,
        )
    }
    return result
}

/**
 * Draws the footprint in its own Compose layer. Invalidating this canvas does not remove and
 * rebuild the osmdroid overlay list, and the canvas has no pointer input so map gestures fall through.
 */
@Composable
internal fun CameraFootprintHud(
    viewModel: StreamsViewModel,
    terrain: CameraFootprintTerrainCache,
    projectionClock: CameraFootprintProjectionClock,
    modifier: Modifier = Modifier,
) {
    val drawingsState = remember { mutableStateOf<List<CameraFootprintDrawing>>(emptyList()) }
    var projectionTick by remember { mutableIntStateOf(0) }
    DisposableEffect(projectionClock) {
        projectionClock.onChange = {
            if (drawingsState.value.isNotEmpty()) projectionTick++
        }
        onDispose { projectionClock.onChange = null }
    }
    LaunchedEffect(viewModel, terrain) {
        while (isActive) {
            val inputs = currentCameraFootprintInputs(viewModel)
            val cached = terrain.value
            drawingsState.value = inputs.mapNotNull { (id, input) ->
                val outline = cameraFootprintTerrainForDisplay(cached[id], input)
                cameraFootprintLiveDrawing(input, outline).takeIf { it.corners.isNotEmpty() || it.boundary.isNotEmpty() }
            }
            delay(CAMERA_FOOTPRINT_FRAME_MS)
        }
    }
    val density = LocalContext.current.resources.displayMetrics.density
    val paints = remember(density) { CameraFootprintStrokePaints(density) }
    // Read in composition so a pan or a new pose recomposes only this HUD.
    val frame = projectionTick
    val posed = drawingsState.value
    Canvas(modifier.fillMaxSize()) {
        if (frame < 0 || posed.isEmpty()) return@Canvas
        val map = projectionClock.mapView ?: return@Canvas
        drawIntoCanvas { canvas ->
            drawCameraFootprints(canvas.nativeCanvas, map, posed, paints, density)
        }
    }
}

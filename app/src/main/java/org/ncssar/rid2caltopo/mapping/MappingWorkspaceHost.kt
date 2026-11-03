package org.ncssar.rid2caltopo.mapping

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dialog ownership is independent of the adaptive map/video layout. No disk credentials or imagery. */
object MappingWorkspaceState {
    private val current = MutableStateFlow<MappingMission?>(null)
    val mission = current.asStateFlow()
    private val library = MutableStateFlow(false)
    val libraryOpen = library.asStateFlow()
    private val choices = MutableStateFlow<List<MappingMission>>(emptyList())
    val assignments = choices.asStateFlow()
    fun openLibrary(assignments: List<MappingMission>) { choices.value=assignments; current.value=null; library.value=true }
    fun backToLibrary() { current.value=null }
    private val changes = MutableStateFlow(0L)
    val layerChanges = changes.asStateFlow()
    private val display = MutableStateFlow(0L)
    val displayChanges = display.asStateFlow()
    /** One-shot token set only by [MappingLayer.selectLocal]; cold start never publishes one. */
    private val localPreviewZoom = MutableStateFlow<Long?>(null)
    val localPreviewZoomToken = localPreviewZoom.asStateFlow()
    fun open(mission: MappingMission) { current.value = mission }
    fun close() { current.value = null; library.value=false }
    fun layersChanged() { changes.update { it+1 } }
    fun displayChanged() { display.update { it+1 } }
    fun requestLocalPreviewZoom() { localPreviewZoom.update { (it ?: 0L) + 1L } }
    fun clearLocalPreviewZoom() { localPreviewZoom.value = null }
}

/** Pure zoom gate matching iOS localPreviewZoomToken: persist selection never zooms by itself. */
internal data class MappingLocalLayerZoomPlan(val shouldZoom: Boolean, val consumeToken: Boolean)

internal fun planMappingLocalLayerZoom(
    zoomToken: Long?,
    lastConsumedToken: Long?,
    hasUsableBounds: Boolean,
    mapReady: Boolean,
): MappingLocalLayerZoomPlan {
    if (zoomToken == null || zoomToken == lastConsumedToken) {
        return MappingLocalLayerZoomPlan(shouldZoom = false, consumeToken = false)
    }
    if (!hasUsableBounds) {
        return MappingLocalLayerZoomPlan(shouldZoom = false, consumeToken = true)
    }
    if (!mapReady) {
        return MappingLocalLayerZoomPlan(shouldZoom = false, consumeToken = false)
    }
    return MappingLocalLayerZoomPlan(shouldZoom = true, consumeToken = true)
}

@Composable
fun MappingWorkspaceHost(onViewRequested: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mission by MappingWorkspaceState.mission.collectAsState()
    val processing by MappingProcessingManager.state.collectAsState()
    val libraryOpen by MappingWorkspaceState.libraryOpen.collectAsState()
    val assignments by MappingWorkspaceState.assignments.collectAsState()
    val view: (MappingLayer) -> Unit = { layer ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { MappingLayer.selectLocal(context, layer) }
            if (!ok) {
                Toast.makeText(context, "This map has no imagery yet", Toast.LENGTH_SHORT).show()
                return@launch
            }
            MappingWorkspaceState.layersChanged()
            MappingWorkspaceState.close()
            MappingProcessingManager.dismiss()
            onViewRequested()
        }
    }
    MappingWorkspaceContent(mission, processing, view, libraryOpen=libraryOpen, assignments=assignments, onHidePreview={
        scope.launch {
            withContext(Dispatchers.IO) { MappingLayer.selectLocal(context, null) }
            MappingWorkspaceState.layersChanged(); MappingWorkspaceState.close()
        }
    })
}

@Composable
internal fun MappingWorkspaceContent(mission: MappingMission?, processing: MappingProcessingState?, onView: (MappingLayer) -> Unit,
    onCancel: () -> Unit = MappingProcessingManager::cancel, libraryOpen:Boolean=false, assignments:List<MappingMission> = emptyList(), onHidePreview:()->Unit = {}) {
    val saved = rememberSaveableStateHolder()
    // Keep the planner/selected-flight state while the job owns the screen.
    if (processing == null) mission?.let { value ->
        saved.SaveableStateProvider(value.id) {
            MappingMissionDialog(value, MappingWorkspaceState::layersChanged, if(libraryOpen) MappingWorkspaceState::backToLibrary else MappingWorkspaceState::close, onViewLayer = onView)
        }
    }
    if(processing==null && mission==null && libraryOpen) saved.SaveableStateProvider("local-maps") {
        MappingLocalLayerDialog(assignments=assignments,onCreate=MappingWorkspaceState::open,onView={layer->if(layer==null)onHidePreview() else onView(layer)},onDismiss=MappingWorkspaceState::close)
    }
    processing?.let { MappingProcessingDialog(it, onView = onView, onCancel = onCancel) }
}

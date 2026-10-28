package org.ncssar.rid2caltopo.video

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Called on Main. Only bounded edits and immutable reference publication happen there. */
internal class MapFolderVisibilityController(
    private val scope: CoroutineScope,
    private val preferences: MapFolderVisibilityPreferences,
    private val prepareWorker: CoroutineDispatcher = Dispatchers.Default,
    private val onLoadFailure: (Throwable) -> Unit = {}
) {
    var state by mutableStateOf(MapFolderVisibilityState())
        private set
    private var loadJob: Job? = null
    private val bulkEditMutex = Mutex()
    private data class ItemChoice(val order: Long, val visible: Boolean)
    private var itemChoices = mutableStateMapOf<String, ItemChoice>()
    private var itemEditOrder = 0L

    fun restore(mapKey: String?): Job? {
        if (state.mapKey == mapKey) return loadJob
        loadJob?.cancel()
        itemChoices = mutableStateMapOf()
        val pending = MapFolderVisibilityState(mapKey = mapKey, loading = mapKey != null)
        state = pending
        if (mapKey == null) return null
        return scope.launch {
            try {
                val prepared = withContext(prepareWorker) {
                    MapFolderVisibilityState.restored(pending, preferences.load(mapKey))
                }
                if (state === pending) state = prepared
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onLoadFailure(failure)
                if (state === pending) {
                    state = MapFolderVisibilityState(mapKey, pending.session,
                        loadError = "Could not load saved Map Folders settings. Reconnect the map to retry.")
                }
            }
        }.also { loadJob = it }
    }

    private fun persist() {
        val current = state
        if (current.loading || current.loadError != null) return
        current.mapKey?.let { preferences.save(it, current.saved()) }
    }

    fun applyFolderDefault(folderId: String, visible: Boolean) {
        if (state.loading) return
        val hidden = folderHiddenAfterDefault(folderId in state.hiddenFolders, visible, state.folderOverrides[folderId])
        if (hidden) state.hiddenFolders.add(folderId) else state.hiddenFolders.remove(folderId)
    }

    fun setFolder(folderId: String, visible: Boolean) {
        if (state.loading || state.loadError != null) return
        state.folderOverrides[folderId] = visible
        if (visible) state.hiddenFolders.remove(folderId) else state.hiddenFolders.add(folderId)
        persist()
    }

    fun setItem(itemId: String, visible: Boolean) {
        if (state.loading || state.loadError != null) return
        // Record even an unchanged checkbox: an explicit show must beat an earlier,
        // still-preparing bulk hide of this same item.
        itemChoices[itemId] = ItemChoice(++itemEditOrder, visible)
        if (visible) state.hiddenItems.remove(itemId) else state.hiddenItems.add(itemId)
        persist()
    }

    /** Prepare large item sets off Main; retry if a gesture/delta changed the immutable base. */
    fun setItems(itemIds: Iterable<String>, visible: Boolean): Job {
        val session = state.session
        val editOrder = ++itemEditOrder
        return scope.launch {
            bulkEditMutex.withLock {
                while (true) {
                    val current = state
                    if (current.session !== session || current.loading || current.loadError != null) return@withLock
                    val before = current.hiddenItems.toSet()
                    val laterChoices = itemChoices.toMap()
                    val prepared = withContext(prepareWorker) {
                        val ids = itemIds.toSet()
                        mutableStateSetOf<String>().apply {
                            addAll(if (visible) before - ids else before + ids)
                            laterChoices.forEach { (id, choice) ->
                                if (choice.order > editOrder) {
                                    if (choice.visible) remove(id) else add(id)
                                }
                            }
                        }
                    }
                    if (state.session !== session) return@withLock
                    if (state !== current || current.hiddenItems.toSet() !== before || itemChoices.toMap() !== laterChoices) continue
                    state = MapFolderVisibilityState(current.mapKey, session,
                        hiddenFolders = current.hiddenFolders, hiddenItems = prepared,
                        folderOverrides = current.folderOverrides)
                    persist()
                    return@withLock
                }
            }
        }
    }

    /** Detach without deleting saved preferences or cancelling their application-lifetime writes. */
    fun reset() {
        loadJob?.cancel()
        itemChoices = mutableStateMapOf()
        state = MapFolderVisibilityState()
    }
}

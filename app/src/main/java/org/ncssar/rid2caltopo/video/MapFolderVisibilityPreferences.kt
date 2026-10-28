package org.ncssar.rid2caltopo.video

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.util.Locale
import java.util.concurrent.Executors

/** Private operator preferences, scoped to the server and map ID (never the display title). */
internal fun mapFolderVisibilityKey(domain: String, mapId: String): String? =
    mapId.trim().takeIf { it.isNotEmpty() }?.let {
        JSONArray().put(domain.trim().lowercase(Locale.ROOT)).put(it).toString()
    }

internal data class SavedMapFolderVisibility(
    val hiddenFolders: Set<String> = emptySet(),
    val hiddenItems: Set<String> = emptySet(),
    val folderOverrides: Map<String, Boolean> = emptyMap()
) {
    fun encode(): String = JSONObject()
        .put("hiddenFolders", JSONArray(hiddenFolders.toList()))
        .put("hiddenItems", JSONArray(hiddenItems.toList()))
        .put("folderOverrides", JSONObject(folderOverrides))
        .toString()

    companion object {
        fun decode(encoded: String?): SavedMapFolderVisibility {
            if (encoded == null) return SavedMapFolderVisibility()
            val json = JSONObject(encoded)
            fun ids(name: String): Set<String> {
                val values = json.getJSONArray(name)
                return (0 until values.length()).mapTo(HashSet()) { values.getString(it) }
            }
            val overrides = json.getJSONObject("folderOverrides")
            return SavedMapFolderVisibility(ids("hiddenFolders"), ids("hiddenItems"),
                overrides.keys().asSequence().associateWith { overrides.getBoolean(it) })
        }
    }
}

/** Prepared on a worker; Main publishes this object with a single reference assignment. */
internal class MapFolderVisibilityState(
    val mapKey: String? = null,
    val session: Any = Any(),
    val loading: Boolean = false,
    val loadError: String? = null,
    val hiddenFolders: androidx.compose.runtime.snapshots.SnapshotStateSet<String> = mutableStateSetOf(),
    val hiddenItems: androidx.compose.runtime.snapshots.SnapshotStateSet<String> = mutableStateSetOf(),
    val folderOverrides: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean> = mutableStateMapOf()
) {
    // Compose's toSet/toMap return their immutable backing collections in O(1). No scan or
    // serialization happens on the caller, and later UI edits cannot change a queued save.
    fun saved() = SavedMapFolderVisibility(hiddenFolders.toSet(), hiddenItems.toSet(), folderOverrides.toMap())

    companion object {
        fun restored(pending: MapFolderVisibilityState, saved: SavedMapFolderVisibility) =
            MapFolderVisibilityState(mapKey = pending.mapKey, session = pending.session,
                hiddenFolders = mutableStateSetOf<String>().apply { addAll(saved.hiddenFolders) },
                hiddenItems = mutableStateSetOf<String>().apply { addAll(saved.hiddenItems) },
                folderOverrides = mutableStateMapOf<String, Boolean>().apply { putAll(saved.folderOverrides) })
    }
}

/** One application-lifetime worker orders durable writes and reads across ViewModel recreation.
 * Pending saves coalesce per map, so rapid gestures do not create a job or disk write per click.
 * The mailbox lock protects references only; preferences access and encoding never hold it.
 */
internal class MapFolderVisibilityPreferences(
    private val worker: CoroutineDispatcher,
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val onWriteFailure: (Throwable) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val gate = Any()
    private val pending = LinkedHashMap<String, SavedMapFolderVisibility>()
    private var draining = false

    suspend fun load(mapKey: String): SavedMapFolderVisibility = withContext(worker) {
        SavedMapFolderVisibility.decode(read(mapKey))
    }

    fun save(mapKey: String, visibility: SavedMapFolderVisibility) {
        val schedule = synchronized(gate) {
            pending[mapKey] = visibility
            if (draining) false else { draining = true; true }
        }
        if (schedule) scope.launch {
            while (true) {
                val next = synchronized(gate) {
                    val entry = pending.entries.firstOrNull()
                    if (entry == null) { draining = false; null }
                    else (entry.key to entry.value).also { pending.remove(entry.key) }
                } ?: break
                try { write(next.first, next.second.encode()) }
                catch (failure: Exception) { onWriteFailure(failure) }
            }
        }
    }

    companion object {
        @Volatile private var shared: MapFolderVisibilityPreferences? = null

        fun shared(context: Context): MapFolderVisibilityPreferences = shared ?: synchronized(this) {
            shared ?: run {
                val app = context.applicationContext
                val worker = Executors.newSingleThreadExecutor { task ->
                    Thread(task, "map-folder-preferences").apply { isDaemon = true }
                }.asCoroutineDispatcher()
                // getSharedPreferences itself can wait for its disk load: invoke it only on worker.
                MapFolderVisibilityPreferences(worker,
                    read = { key -> app.getSharedPreferences("map_folder_visibility_v1", Context.MODE_PRIVATE).getString(key, null) },
                    write = { key, value ->
                        check(app.getSharedPreferences("map_folder_visibility_v1", Context.MODE_PRIVATE)
                            .edit().putString(key, value).commit()) { "Could not save Map Folders settings" }
                    },
                    onWriteFailure = { CaltopoClient.CTWarn("MapFolders", "Could not save visibility: ${it.message}") }
                ).also { shared = it }
            }
        }
    }
}

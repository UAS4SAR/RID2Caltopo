package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val Context.mappingMissionPreferences by preferencesDataStore("mapping_mission")

/** One conflated writer outlives a dialog. Closing the panel cannot cancel its last selection. */
internal class MappingAircraftPreference(private val store:DataStore<Preferences>, scope:CoroutineScope) {
    private val requested=MutableStateFlow<String?>(null)
    init {
        scope.launch(Dispatchers.IO) {
            requested.filterNotNull().collect { model ->
                try { store.edit { it[key]=model } }
                catch(error:Exception) { if(error is CancellationException)throw error;Log.w("MappingAircraftPreference","Cannot save selected aircraft",error) }
            }
        }
    }
    suspend fun load():String = requested.value ?: normalized(store.data.first()[key]).let { requested.value ?: it }
    fun select(model:String) { requested.value=normalized(model) }
    companion object {
        private val key=stringPreferencesKey("aircraft")
        fun normalized(model:String?) = model?.takeIf{it in MappingResolution.aircraftModels} ?: MappingResolution.MATRICE_4TD
    }
}

internal object MappingAircraftPreferences {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var preference:MappingAircraftPreference?=null
    suspend fun load(context:Context):String = withContext(Dispatchers.IO) {
        val store=preference ?: synchronized(this@MappingAircraftPreferences) {
            preference ?: MappingAircraftPreference(context.applicationContext.mappingMissionPreferences,scope).also{preference=it}
        }
        store.load()
    }
    // The chooser remains disabled until load() has initialized the worker and backing store.
    fun select(model:String) { preference?.select(model) }
}

package org.ncssar.rid2caltopo.mapping

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MappingAircraftPreferenceTest {
    @Test fun closingAndReopeningThePlannerRetainsTheLatestAircraft() = runBlocking {
        val directory=Files.createTempDirectory("r2c-aircraft-preference").toFile()
        val file=directory.resolve("mission.preferences_pb")
        val key=stringPreferencesKey("aircraft")
        var scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            var store=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
            val first=MappingAircraftPreference(store,scope)
            assertEquals("DJI Matrice 4TD",first.load())
            for(model in listOf("DJI Matrice 30","DJI Matrice 30T","DJI Matrice 4T"))first.select(model)
            assertEquals("DJI Matrice 4T",first.load())
            withTimeout(5000){store.data.first{it[key]=="DJI Matrice 4T"}}
            scope.coroutineContext[Job]!!.cancelAndJoin()
            scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            store=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
            val reopened=MappingAircraftPreference(store,scope)
            assertEquals("DJI Matrice 4T",reopened.load())
            reopened.select("DJI Matrice 400")
            withTimeout(5000){store.data.first{it[key]=="DJI Matrice 400"}}
            assertEquals("DJI Matrice 400",MappingAircraftPreference(store,scope).load())
            reopened.select("retired model")
            withTimeout(5000){store.data.first{it[key]=="DJI Matrice 4TD"}}
            assertEquals("DJI Matrice 4TD",reopened.load())
        } finally {scope.coroutineContext[Job]!!.cancelAndJoin();directory.deleteRecursively()}
    }
}

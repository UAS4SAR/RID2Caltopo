package org.ncssar.rid2caltopo.mapping

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class MappingLibraryTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun consistentCardIncludesAllFiveRequestedFields() {
        val entry=MappingLibraryEntry("test","Test capture","2026:10:11 17:21:00","",true,12,true,true,"Map ready",MappingStorageUsage(originals=2048,maps2D=4096,models3D=8192,planning=1024),0.0 to 0.0)
        compose.setContent { MaterialTheme { MappingLibraryCard(entry,0.0 to 0.0,onOpen={}) } }
        compose.onNodeWithText("Test capture").assertExists()
        compose.onNodeWithText(entry.timeLabel).assertExists()
        compose.onNodeWithText(entry.statusLabel).assertExists()
        compose.onNodeWithText("Distance to area center: 0 ft").assertExists()
        compose.onNodeWithText("Planning files: 1.0 KiB · Captured images: 2.0 KiB").assertExists()
        compose.onNodeWithText("2D tiles: 4.0 KiB · 3D results: 8.0 KiB").assertExists()
    }
    @Test fun missionOpensDetailAndBackReturnsDirectlyToTheSameSortedLibrary() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.randomUUID().toString()
        val root=File(context.filesDir,"mapping-missions/$id").apply{mkdirs()}
        val secondId=UUID.randomUUID().toString()
        val secondRoot=File(context.filesDir,"mapping-missions/$secondId").apply{mkdirs()}
        val preferences=context.getSharedPreferences("mapping_library",0)
        val old=preferences.getBoolean("newestFirst",true)
        try {
            File(root,"mission.json").writeText(JSONObject().put("missionId",id).put("title","Library navigation test").put("createdAt","1990-01-01T12:00:00Z").put("profile","daylight-rgb").toString())
            File(root,"mission.kmz").writeText("synthetic plan")
            File(secondRoot,"mission.json").writeText(JSONObject().put("missionId",secondId).put("title","Second mission test").put("createdAt","1990-01-02T12:00:00Z").put("profile","daylight-rgb").toString())
            File(secondRoot,"mission.kmz").writeText("synthetic plan")
            compose.setContent { MaterialTheme { MappingLocalLayerDialog(onView={},onDismiss={}) } }
            compose.waitUntil(20000){compose.onAllNodesWithTag("mapping-sort-oldest").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("mapping-local-layer-list").performScrollToNode(hasTestTag("mapping-sort-oldest"))
            compose.onNodeWithTag("mapping-sort-oldest").performClick()
            compose.waitUntil(20000){compose.onAllNodesWithTag("mapping-entry-flight:$id").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("mapping-local-layer-list").performScrollToNode(hasTestTag("mapping-entry-flight:$id"))
            compose.onNodeWithTag("mapping-entry-flight:$id").onChildren().filterToOne(hasText("Open mission")).performClick()
            compose.waitUntil(15000){compose.onAllNodesWithTag("mapping-flight-detail").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("mapping-flight-list").assertDoesNotExist()
            compose.onNodeWithText("Back to local maps").performClick()
            compose.onNodeWithTag("mapping-local-layer-list").assertExists()
            compose.onNodeWithTag("mapping-flight-list").assertDoesNotExist()
            compose.onNodeWithTag("mapping-local-layer-list").performScrollToNode(hasTestTag("mapping-sort-oldest"))
            compose.onNodeWithText("✓ Oldest first").assertExists()
            compose.onNodeWithTag("mapping-local-layer-list").performScrollToNode(hasTestTag("mapping-entry-flight:$secondId"))
            compose.onNodeWithTag("mapping-entry-flight:$secondId").onChildren().filterToOne(hasText("Open mission")).performClick()
            compose.waitUntil(15000){compose.onAllNodesWithTag("mapping-flight-detail").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Second mission test").assertExists()
            compose.onNodeWithText("Library navigation test").assertDoesNotExist()
        } finally {
            MappingFlightDeletion.removeTree(root)
            MappingFlightDeletion.removeTree(secondRoot)
            preferences.edit().putBoolean("newestFirst",old).commit()
        }
    }
    @Test fun missingMissionReportsUnavailableInsteadOfLoadingForever() {
        compose.setContent { MaterialTheme { MappingFlightsDialog(null,{}, {}, {}, {},initialFlightId=UUID.randomUUID().toString(),detailOnly=true) } }
        compose.waitUntil(15000){compose.onAllNodesWithText("This saved mission is no longer available. Return to Local maps and select a mission.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Loading mission details…").assertDoesNotExist()
        compose.onNodeWithText("Back to local maps").assertExists()
    }

    @Test fun oldLocationDisplaysDistanceWithAgeInsteadOfUnavailable() {
        val reference=MappingLibraryLocation.from(39.0,-121.0,600000,false)!!
        val entry=MappingLibraryEntry("old-fix","Old fix test","","",true,0,false,false,"",null,39.0 to -121.0)
        compose.setContent { MaterialTheme { MappingLibraryCard(entry,reference.coordinates,reference.note,onOpen={}) } }
        compose.onNodeWithText("Distance to area center: 0 ft · last known location, 10 min old").assertExists()
        compose.onNodeWithText("Distance: current location unavailable").assertDoesNotExist()
    }

    @Test fun measureConnectedLibraryDiscoveryBeforeStorageAudit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val start=android.os.SystemClock.elapsedRealtime()
        val quick=MappingLibraryRepository.list(context,null,includeStorage=false)
        val discovered=android.os.SystemClock.elapsedRealtime()
        assertTrue(quick.all { it.storage==null })
        val full=MappingLibraryRepository.list(context,null)
        val finished=android.os.SystemClock.elapsedRealtime()
        assertEquals(full.map { it.id }.toSet(),quick.map { it.id }.toSet())
        android.util.Log.i("MappingLibraryTiming","missions=${quick.size} discoveryMs=${discovered-start} storageAuditMs=${finished-discovered}")
    }

    @Test fun pendingStorageIsClearlyLabelled() {
        val entry=MappingLibraryEntry("pending","Pending storage","","",true,0,false,false,"Waiting for flight photos",null,null)
        compose.setContent { MaterialTheme { MappingLibraryCard(entry,null,onOpen={}) } }
        compose.onNodeWithText("Storage: calculating…").assertExists()
    }

    @Test fun connectedLibraryReusesPersistentStorageAfterMemoryReset() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val first=MappingLibraryRepository.list(context,null)
        MappingMissionStorage.clearMemoryCache()
        val start=android.os.SystemClock.elapsedRealtime()
        val index=MappingMissionStorage.index(context.filesDir)
        first.forEach { entry ->
            val cached=entry.flight?.let { index.cachedMission(it.id) } ?: entry.layer?.let { MappingMissionStorage.cachedLayer(context.filesDir,it.id) }
            assertEquals(entry.storage,cached)
        }
        val restored=MappingLibraryRepository.list(context,null)
        assertEquals(first,restored)
        android.util.Log.i("MappingLibraryTiming","persistentCacheMissions=${restored.size} restoredMs=${android.os.SystemClock.elapsedRealtime()-start}")
    }

    @Test fun unchangedLibraryStaysQuietPastFormerAuditInterval() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        MappingLibraryRepository.list(context,null) // Seed the persistent stats once.
        compose.setContent { MaterialTheme { MappingLocalLayerDialog(onView={},onDismiss={}) } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Loading saved missions…").fetchSemanticsNodes().isEmpty() }
        val start=android.os.SystemClock.elapsedRealtime()
        while(android.os.SystemClock.elapsedRealtime()-start<65000) {
            compose.onNodeWithTag("mapping-library-progress").assertDoesNotExist()
            android.os.SystemClock.sleep(1000)
        }
    }

}

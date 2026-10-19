package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class MappingFlightStatusTest {
    @get:Rule val temp = TemporaryFolder()
    private fun flight(count: Int = 5): Pair<File, JSONObject> {
        val id = UUID.randomUUID().toString()
        val root = File(temp.root, "mapping-missions/$id").apply { mkdirs() }
        val mission = JSONObject().put("missionId", id).put("assignmentId", "area").put("incidentId", "incident")
            .put("createdAt", "2026-10-12T00:21:58Z").put("title", "Trial").put("profile", "daylight-rgb")
        File(root, "mission.json").writeText(mission.toString())
        File(root, "inventory").mkdirs(); File(root, "originals").mkdirs()
        repeat(count) { index ->
            val digest = "%064x".format(index + 1)
            File(root, "originals/$digest.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "inventory/$digest.json").writeText(JSONObject().put("sha256", digest).put("name", "image$index.jpg")
                .put("complete", true).put("sizeBytes", 3).toString())
        }
        return root to mission
    }
    private fun batch(root: File, count: Int) = File(root, "received-batch.json").writeText(JSONObject()
        .put("sha256", JSONArray((1..count).map { "%064x".format(it) })).toString())

    @Test fun completedBatchDoesNotClaimAdditionalInventoryPhotos() {
        val (root, mission) = flight(); batch(root, 3)
        val status = MappingFlightRepository.snapshot(root, mission)
        assertEquals(5, status.photoCount); assertEquals(3, status.completedDigests.size); assertEquals(2, status.extraCount)
        assertEquals("Completed photo set received", status.stage)
        File(root, "originals/${status.completedDigests.first()}.jpg").delete()
        val incomplete = MappingFlightRepository.snapshot(root, mission)
        assertTrue(incomplete.completedDigests.isEmpty()); assertEquals(1, incomplete.pendingCount)
        assertEquals("Receiving originals", incomplete.stage)
    }
    @Test fun cancelledAndInterruptedJobsRemainHonestAndLiveProgressWins() {
        val (root, mission) = flight()
        val file = File(root, "processing-status.json")
        file.writeText(JSONObject().put("active", false).put("stage", "Processing cancelled; originals retained").toString())
        assertTrue(MappingFlightRepository.snapshot(root, mission).stage.contains("cancelled"))
        file.writeText(JSONObject().put("active", true).put("stage", "Matching photos").put("progress", .2).toString())
        val stale = MappingFlightRepository.snapshot(root, mission)
        assertFalse(stale.active); assertTrue(stale.stage.contains("interrupted"))
        val live = MappingProcessingState(mission.getString("missionId"), "Trial", stage = "Building surface", progress = .4, remainingSeconds = 90)
        val active = MappingFlightRepository.snapshot(root, mission, live)
        assertTrue(active.active); assertEquals("Building surface", active.stage)
        assertEquals(.4, active.progress!!, 0.0); assertEquals(90L, active.remainingSeconds)
    }
    @Test fun completedLayerSurvivesWithoutAControllerBatchAndListIncludesAllFlights() {
        val (root, mission) = flight()
        val layerId = UUID.randomUUID().toString()
        File(temp.root, "mapping-layers/$layerId").mkdirs()
        File(temp.root, "mapping-layers/$layerId/layer.json").writeText("{}")
        File(root, "processing-status.json").writeText(JSONObject().put("active", false).put("stage", "Map ready").put("layerId", layerId).toString())
        val ready = MappingFlightRepository.snapshot(root, mission)
        assertEquals(layerId, ready.layerId); assertEquals("Map ready", ready.stage)
        File(temp.root, "mapping-layers/$layerId/layer.json").delete()
        assertEquals("Result unavailable; originals retained", MappingFlightRepository.snapshot(root, mission).stage)
        repeat(6) { flight() }
        assertEquals(7, MappingFlightRepository.list(temp.root, "area", "incident").size)
        assertTrue(MappingFlightRepository.list(temp.root, "other-area", "incident").isEmpty())
    }
    @Test fun localLibraryIncludesPreparedRoutesAcrossIncidentsWhileScopedQueriesStayScoped() {
        val (first,mission)=flight(0);File(first,"mission.kmz").writeBytes(byteArrayOf(1))
        File(first,"route-summary.json").writeText(JSONObject().put("estimatedPhotos",179).put("estimatedFlightSeconds",510).put("heightMeters",75.37).toString())
        val (second,other)=flight(0);other.put("assignmentId","different-area").put("incidentId","other-incident")
        File(second,"mission.json").writeText(other.toString());File(second,"mission.kmz").writeBytes(byteArrayOf(1))
        val all=MappingFlightRepository.list(temp.root)
        assertEquals(setOf(first.name,second.name),all.map{it.id}.toSet())
        val scoped=MappingFlightRepository.list(temp.root,"area","incident")
        assertEquals(listOf(first.name),scoped.map{it.id});assertEquals(179,scoped.single().plannedPhotos)
        assertEquals(510,scoped.single().plannedSeconds);assertEquals(75.37,scoped.single().plannedHeightMeters!!,0.0)
    }

    @Test fun everyResultVersionBelongsToItsSavedFlight() {
        val (root, mission) = flight()
        val ids = List(2) { UUID.randomUUID().toString() }
        ids.forEach { id ->
            File(temp.root, "mapping-layers/$id/layer.json").apply {
                parentFile!!.mkdirs()
                writeText(JSONObject().put("missionId", root.name).put("layerId", id).toString())
            }
        }
        val status = MappingFlightRepository.snapshot(root, mission)
        assertEquals(ids.toSet(), status.resultIds.toSet())
        assertEquals(5, status.photoCount)
    }

    @Test fun metadataDiscoveryRetainsOwnedResultsWithoutMeasuringStorage() {
        val (root, mission) = flight(0)
        val layerId = UUID.randomUUID().toString()
        File(temp.root, "mapping-layers/$layerId/layer.json").apply {
            parentFile!!.mkdirs()
            writeText(JSONObject().put("missionId", root.name).put("layerId", layerId).toString())
        }
        val quick = MappingFlightRepository.list(temp.root, includeStorage=false).single()
        assertEquals(root.name, quick.id)
        assertEquals(listOf(layerId), quick.resultIds)
        assertNull(quick.storage)
        assertNotNull(MappingFlightRepository.snapshot(root, mission).storage)
    }

}

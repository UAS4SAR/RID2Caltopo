package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class MappingFlightDeletionTest {
    @get:Rule val temp = TemporaryFolder()
    private val id get() = UUID.randomUUID().toString()
    private fun write(path: String, text: String = "keep") = File(temp.root, path).apply { parentFile!!.mkdirs(); writeText(text) }
    private fun mission(): String = id.also { write("mapping-missions/$it/mission.json", JSONObject().put("missionId", it).toString()) }
    private fun result(owner: String): String = id.also { write("mapping-layers/$it/layer.json", JSONObject().put("missionId", owner).toString()) }
    @Test fun resultsOnlyDeletesAllVersionsAndWorkButPreservesOriginalsAndOtherFlights() {
        val flight = mission(); val other = mission(); val a = result(flight); val b = result(flight); val c = result(other)
        val original = write("mapping-missions/$flight/originals/test.jpg")
        val work = id; write("mapping-processing/$work/work-owner.json", JSONObject().put("missionId", flight).toString())
        val unknown = id; write("mapping-processing/$unknown/unknown.bin")
        val plan = MappingFlightDeletion.plan(temp.root, emptyList(), flight, false)
        assertEquals(setOf(a, b), plan.layerIds)
        plan.roots.flatMap { MappingFlightDeletion.physicalRoots(temp.root, emptyList(), it) }.forEach(MappingFlightDeletion::removeTree)
        assertEquals("keep", original.readText())
        assertTrue(File(temp.root, "mapping-missions/$flight/mission.json").exists())
        assertTrue(File(temp.root, "mapping-layers/$c/layer.json").exists())
        assertTrue(File(temp.root, "mapping-processing/$unknown/unknown.bin").exists())
        assertFalse(File(temp.root, "mapping-processing/$work").exists())
    }
    @Test fun entireFlightDeletesSplitCardFilesAndAliasesWithoutFollowingForeignLinks() {
        val flight = mission(); val layer = id; val card = temp.newFolder("card").canonicalFile
        val physicalMission = File(card, "mapping-missions/$flight").apply { mkdirs() }
        val bulk = File(physicalMission, "originals").apply { mkdirs() }
        File(bulk, "photo.jpg").writeText("photo")
        val root = File(temp.root, "mapping-missions/$flight")
        File(root, "mapping-volume.json").writeText(JSONObject().put("path", physicalMission.path).toString())
        Files.createSymbolicLink(File(root, "originals").toPath(), bulk.toPath())
        val physicalLayer = File(card, "mapping-layers/$layer").apply { mkdirs() }
        File(physicalLayer, "layer.json").writeText(JSONObject().put("missionId", flight).toString())
        val alias = File(temp.root, "mapping-layers/$layer").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(alias.toPath(), physicalLayer.toPath())
        val foreign = write("unrelated/keep.txt")
        Files.createSymbolicLink(File(physicalLayer, "foreign").toPath(), foreign.parentFile.toPath())
        val plan = MappingFlightDeletion.plan(temp.root, listOf(card), flight, true)
        val targets = plan.roots.flatMap { MappingFlightDeletion.physicalRoots(temp.root, listOf(card), it) }
        targets.forEach(MappingFlightDeletion::removeTree)
        assertFalse(root.exists()); assertFalse(physicalMission.exists()); assertFalse(physicalLayer.exists())
        assertFalse(Files.isSymbolicLink(alias.toPath())); assertEquals("keep", foreign.readText())
    }
    @Test fun refusesMissingCardOrRedirectedStorageBeforeDeleting() {
        val flight = mission(); val root = File(temp.root, "mapping-missions/$flight")
        val card = temp.newFolder("card").canonicalFile
        File(root, "mapping-volume.json").writeText(JSONObject().put("path", File(card, "mapping-missions/$flight").path).toString())
        assertThrows(IllegalArgumentException::class.java) { MappingFlightDeletion.plan(temp.root, listOf(card), flight, true) }
        File(root, "mapping-volume.json").writeText(JSONObject().put("path", temp.root.path).toString())
        assertThrows(IllegalArgumentException::class.java) { MappingFlightDeletion.plan(temp.root, listOf(card), flight, true) }
        assertTrue(File(root, "mission.json").exists())
    }
    @Test fun preservesResultsReferencedByAnotherFlight() {
        val flight = mission(); val other = mission(); val layer = result(flight)
        write("mapping-missions/$other/processing-status.json", JSONObject().put("layerId", layer).toString())
        val plan = MappingFlightDeletion.plan(temp.root, emptyList(), flight, true)
        assertTrue(plan.layerIds.isEmpty()); assertEquals(1, plan.roots.size)
        assertEquals(flight, plan.roots.single().name)
    }
}

package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class MappingStorageSummaryTest {
    @get:Rule val folder = TemporaryFolder()

    @Before
    fun clearMemory() {
        MappingStorageSummary.invalidate()
    }

    @Test
    fun stampStaleWhenMissionChildAppears() {
        val root = folder.root
        val missions = File(root, "mapping-missions").also { it.mkdirs() }
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()
        val before = MappingStorageSummary.rootsFor(root).stamp()
        val mission = File(missions, UUID.randomUUID().toString()).also { it.mkdirs() }
        File(mission, "mission.json").writeText("{}")
        // Directory mtime/signature must change when a project folder is added.
        Thread.sleep(5)
        mission.setLastModified(System.currentTimeMillis())
        missions.setLastModified(System.currentTimeMillis())
        val after = MappingStorageSummary.rootsFor(root).stamp()
        assertFalse(MappingStorageSummary.isFresh(
            MappingStorageSummary.Report(
                projects = emptyList(),
                sharedLayersBytes = 0,
                statsBytes = 0,
                processingBytes = 0,
                otherBytes = 0,
                totalBytes = 0,
                complete = true,
                stamp = before,
            ),
            after,
        ))
    }

    @Test
    fun stampFreshWhenNothingChanged() {
        val root = folder.root
        File(root, "mapping-missions").mkdirs()
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()
        val stamp = MappingStorageSummary.rootsFor(root).stamp()
        val report = MappingStorageSummary.Report(
            projects = emptyList(),
            sharedLayersBytes = 0,
            statsBytes = 0,
            processingBytes = 0,
            otherBytes = 0,
            totalBytes = 0,
            complete = true,
            stamp = stamp,
        )
        assertTrue(MappingStorageSummary.isFresh(report, MappingStorageSummary.rootsFor(root).stamp()))
    }

    @Test
    fun totalsIncludeLayersStatsProcessingAndOther() {
        val root = folder.root
        val missionId = "2e30e0b4-0000-4000-8000-000000000001"
        File(root, "mapping-missions/$missionId/originals/a.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(1_000))
        }
        File(root, "mapping-layers/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/map.tif").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(500))
        }
        File(root, "mapping-processing/bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee/work.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(40))
        }
        File(root, "mapping-storage-stats/library-index.json").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(20))
        }
        File(root, "mapping-missions/loose.txt").writeBytes(ByteArray(7))

        val report = MappingStorageSummary.measure(root)
        assertEquals(1_000L, report.projects.single().bytes)
        assertEquals(500L, report.sharedLayersBytes)
        assertEquals(40L, report.processingBytes)
        assertEquals(20L, report.statsBytes)
        assertEquals(7L, report.otherBytes)
        assertEquals(1_000L + 500L + 40L + 20L + 7L, report.totalBytes)
        assertEquals(report.totalBytes, report.lineSum())
        assertTrue(report.complete)
    }

    @Test
    fun cacheSurvivesReloadUntilStampChanges() {
        val root = folder.root
        File(root, "mapping-missions").mkdirs()
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()
        File(root, "mapping-layers/x/map.tif").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(64))
        }
        val first = MappingStorageSummary.cachedOrRefresh(root)
        assertEquals(64L, first.sharedLayersBytes)
        MappingStorageSummary.invalidate() // memory only
        val second = MappingStorageSummary.loadCached(root)
        assertNotNull(second)
        assertEquals(64L, second!!.sharedLayersBytes)
        assertTrue(MappingStorageSummary.isFresh(second, MappingStorageSummary.rootsFor(root).stamp()))

        File(root, "mapping-layers/y/map.tif").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10))
        }
        File(root, "mapping-layers").setLastModified(System.currentTimeMillis())
        Thread.sleep(5)
        assertFalse(MappingStorageSummary.isFresh(second, MappingStorageSummary.rootsFor(root).stamp()))
        val third = MappingStorageSummary.cachedOrRefresh(root)
        assertEquals(74L, third.sharedLayersBytes)
    }
}

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

    private fun emptyReport(stamp: MappingStorageSummary.Stamp) = MappingStorageSummary.Report(
        projects = emptyList(),
        sharedLayersBytes = 0,
        statsBytes = 0,
        processingBytes = 0,
        otherBytes = 0,
        totalBytes = 0,
        logicalBytes = 0,
        workingCopyBytes = 0,
        complete = true,
        stamp = stamp,
    )

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
        Thread.sleep(5)
        mission.setLastModified(System.currentTimeMillis())
        missions.setLastModified(System.currentTimeMillis())
        val after = MappingStorageSummary.rootsFor(root).stamp()
        assertFalse(MappingStorageSummary.isFresh(emptyReport(before), after))
    }

    @Test
    fun stampFreshWhenNothingChanged() {
        val root = folder.root
        File(root, "mapping-missions").mkdirs()
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()
        val stamp = MappingStorageSummary.rootsFor(root).stamp()
        assertTrue(MappingStorageSummary.isFresh(emptyReport(stamp), MappingStorageSummary.rootsFor(root).stamp()))
    }

    @Test
    fun stampIgnoresStatsAndPaneSummaryWrites() {
        val root = folder.root
        val missionId = "2e30e0b4-0000-4000-8000-000000000001"
        File(root, "mapping-missions/$missionId").mkdirs()
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        val stats = File(root, "mapping-storage-stats").also { it.mkdirs() }
        val before = MappingStorageSummary.rootsFor(root).stamp()
        File(stats, "pane-summary.json").writeText("{}")
        File(stats, "library-index.json").writeBytes(ByteArray(40))
        File(stats, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json")
            .writeBytes(ByteArray(12))
        File(stats, "phase-costs.json").writeText("{}")
        Thread.sleep(5)
        stats.setLastModified(System.currentTimeMillis())
        val after = MappingStorageSummary.rootsFor(root).stamp()
        assertTrue(MappingStorageSummary.isFresh(emptyReport(before), after))
        assertFalse(after.projects.isEmpty() && before.projects.isEmpty() && false) // keep stamp maps comparable
        assertEquals(before, after)
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

        // blockSize=1 → allocated equals logical for structure assertions
        val report = MappingStorageSummary.measure(root, blockSize = 1L)
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
    fun blockRoundingUsesFilesystemBlockSize() {
        assertEquals(4096L, MappingStorageSummary.allocatedBytes(1L, 4096L))
        assertEquals(4096L, MappingStorageSummary.allocatedBytes(4096L, 4096L))
        assertEquals(8192L, MappingStorageSummary.allocatedBytes(4097L, 4096L))
        assertEquals(512L * 1024L, MappingStorageSummary.allocatedBytes(1L, 512L * 1024L))

        val root = folder.root
        File(root, "mapping-missions/m1/a.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(1))
        }
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()
        val report = MappingStorageSummary.measure(root, blockSize = 4096L)
        assertEquals(4096L, report.projects.single().bytes)
        assertEquals(4096L, report.totalBytes)
    }

    @Test
    fun archiveAndWorkingCopyDifferAndWorkingExcludedFromTotal() {
        val root = folder.newFolder("files")
        val archive = folder.newFolder("archive")
        val working = folder.newFolder("operational-work")
        val missionId = "2e30e0b4-0000-4000-8000-000000000001"

        // Local stamp roots (filesDir projection listing)
        File(root, "mapping-missions/$missionId").mkdirs()
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()

        // Archive has 1000-byte original
        File(archive, "mapping/missions/$missionId/originals/a.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(1_000))
        }
        File(archive, "mapping/layers").mkdirs()
        File(archive, "mapping/processing").mkdirs()
        File(archive, "mapping/stats").mkdirs()

        // Working copy has a larger duplicate (2000 bytes)
        File(working, "missions/$missionId/originals/a.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(2_000))
        }

        val archiveRoots = MappingStorageSummary.fileArchiveRoots(archive)!!
        val report = MappingStorageSummary.measure(
            filesDir = root,
            blockSize = 1L,
            archiveRoots = archiveRoots,
            workingCopyRoot = working,
            reason = "full",
        )
        assertEquals(1_000L, report.totalBytes)
        assertEquals(2_000L, report.workingCopyBytes)
        assertEquals(report.totalBytes, report.lineSum())
        assertTrue(report.workingCopyBytes !in listOf(report.totalBytes) || report.workingCopyBytes != report.totalBytes)
        assertFalse(report.lineSum() == report.totalBytes + report.workingCopyBytes)
    }

    @Test
    fun workingCopyUsesItsOwnBlockSize() {
        val root = folder.newFolder("files-block")
        val archive = folder.newFolder("archive-block")
        val working = folder.newFolder("work-block")
        val missionId = "2e30e0b4-0000-4000-8000-000000000009"
        File(root, "mapping-missions/$missionId").mkdirs()
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()
        File(archive, "mapping/missions/$missionId/originals/a.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(1_000))
        }
        File(archive, "mapping/layers").mkdirs()
        File(archive, "mapping/processing").mkdirs()
        File(archive, "mapping/stats").mkdirs()
        File(working, "missions/$missionId/originals/a.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(2_000))
        }
        val report = MappingStorageSummary.measure(
            filesDir = root,
            blockSize = 1L,
            archiveRoots = MappingStorageSummary.fileArchiveRoots(archive),
            workingCopyRoot = working,
            workingBlockSize = 4096L,
        )
        assertEquals(1_000L, report.totalBytes)
        assertEquals(4_096L, report.workingCopyBytes)
    }

    @Test
    fun incrementalRescanOnlyChangedProject() {
        val root = folder.root
        val a = "aaaaaaaa-0000-4000-8000-000000000001"
        val b = "bbbbbbbb-0000-4000-8000-000000000002"
        File(root, "mapping-missions/$a/originals/a.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(100))
        }
        File(root, "mapping-missions/$b/originals/b.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(200))
        }
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()

        val first = MappingStorageSummary.measure(root, blockSize = 1L, reason = "cold")
        assertEquals(2, first.projects.size)
        assertEquals(2, first.rescannedProjects)

        // Change only mission B (touch the child the DirStamp watches).
        Thread.sleep(20)
        val changed = File(root, "mapping-missions/$b/originals/b.jpg")
        changed.writeBytes(ByteArray(250))
        val now = System.currentTimeMillis()
        changed.setLastModified(now)
        File(root, "mapping-missions/$b/originals").setLastModified(now)

        val second = MappingStorageSummary.measure(
            root,
            previous = first,
            blockSize = 1L,
            reason = "stale",
        )
        assertEquals(1, second.rescannedProjects)
        assertEquals(100L, second.projects.first { it.flightId == a }.bytes)
        assertEquals(250L, second.projects.first { it.flightId == b }.bytes)
        assertEquals(350L, second.totalBytes)
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
        val first = MappingStorageSummary.cachedOrRefresh(root, blockSize = 1L)
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
        val third = MappingStorageSummary.cachedOrRefresh(root, blockSize = 1L)
        assertEquals(74L, third.sharedLayersBytes)
    }

    @Test
    fun scanLogLineIsCompact() {
        val stamp = MappingStorageSummary.Stamp(emptyMap(), MappingStorageSummary.DirStamp(0, 0, ""))
        val report = MappingStorageSummary.Report(
            projects = emptyList(),
            sharedLayersBytes = 0,
            statsBytes = 0,
            processingBytes = 0,
            otherBytes = 0,
            totalBytes = 2_600_000_000L,
            logicalBytes = 1_780_000_000L,
            workingCopyBytes = 2_200_000_000L,
            complete = true,
            stamp = stamp,
            scanReason = "stale",
            scanElapsedMs = 1400L,
            rescannedProjects = 1,
        )
        val line = MappingStorageSummary.scanLogLine(report)
        assertTrue(line.startsWith("Mapping storage scan:"))
        assertTrue(line.contains("total=2.6GB"))
        assertTrue(line.contains("working=2.2GB"))
        assertTrue(line.contains("reason=stale"))
        assertFalse(line.contains("lat="))
    }

    @Test
    fun exFatClusterRoundsAllocatedBytes() {
        assertEquals(512L * 1024L, MappingStorageSummary.allocatedBytes(1L, 512L * 1024L))
        val root = folder.root
        File(root, "mapping-missions/m1/a.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(1))
        }
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()
        val report = MappingStorageSummary.measure(root, blockSize = 512L * 1024L)
        assertEquals(512L * 1024L, report.totalBytes)
    }

    @Test
    fun walkDoesNotFollowNestedSymlinks() {
        val root = folder.root
        val mission = File(root, "mapping-missions/m1")
        mission.mkdirs()
        File(mission, "a.bin").writeBytes(ByteArray(100))
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()
        val outside = folder.newFolder("outside")
        File(outside, "big.bin").writeBytes(ByteArray(8_000))
        java.nio.file.Files.createSymbolicLink(File(mission, "linked").toPath(), outside.toPath())
        val report = MappingStorageSummary.measure(root, blockSize = 1L)
        assertEquals(100L, report.totalBytes)

        val working = folder.newFolder("working")
        File(working, "real.bin").writeBytes(ByteArray(50))
        java.nio.file.Files.createSymbolicLink(File(working, "jump").toPath(), outside.toPath())
        val withWorking = MappingStorageSummary.measure(
            filesDir = root,
            blockSize = 1L,
            workingCopyRoot = working,
        )
        assertEquals(50L, withWorking.workingCopyBytes)
        assertEquals(100L, withWorking.totalBytes)
    }

    @Test
    fun cacheHitLogsReasonCache() {
        val root = folder.root
        File(root, "mapping-missions").mkdirs()
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()
        val lines = mutableListOf<String>()
        MappingStorageSummary.scanLogSink = { lines.add(it) }
        try {
            MappingStorageSummary.cachedOrRefresh(root, blockSize = 1L)
            MappingStorageSummary.invalidate()
            MappingStorageSummary.cachedOrRefresh(root, blockSize = 1L)
            assertTrue(lines.any { it.contains("reason=cache") })
        } finally {
            MappingStorageSummary.scanLogSink = null
        }
    }

    @Test
    fun concurrentScansShareOneFlight() {
        val root = folder.root
        File(root, "mapping-missions").mkdirs()
        File(root, "mapping-layers").mkdirs()
        File(root, "mapping-processing").mkdirs()
        File(root, "mapping-storage-stats").mkdirs()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val runs = java.util.concurrent.atomic.AtomicInteger()
        MappingStorageSummary.beforeScan = {
            runs.incrementAndGet()
            entered.countDown()
            release.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        val results = java.util.Collections.synchronizedList(mutableListOf<MappingStorageSummary.Report>())
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val done = java.util.concurrent.CountDownLatch(2)
        try {
            repeat(2) {
                Thread {
                    try {
                        results.add(MappingStorageSummary.cachedOrRefresh(root, blockSize = 1L))
                    } catch (error: Throwable) {
                        errors.add(error)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals(1, runs.get())
            release.countDown()
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(2, results.size)
        } finally {
            MappingStorageSummary.beforeScan = null
            release.countDown()
        }
    }
}

package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ncssar.rid2caltopo.mapping.MappingStorageUsage
import java.io.File

/** Storage reporting for the Mapping repository row and project list totals. */
class MappingStorageManagementTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun localMapStorageCountsProjectsLayersAndProcessingOnce() {
        val root = folder.root
        File(root, "mapping-missions/2e30e0b4-0000-4000-8000-000000000001/originals/a.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(134))
        }
        File(root, "mapping-layers/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/map.tif").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(50))
        }
        File(root, "mapping-processing/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/work.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(20))
        }
        val groups = listOf("mapping-missions", "mapping-layers", "mapping-processing").map { name ->
            listOf(File(root, name))
        }
        val local = localMapStorage(groups)
        assertEquals(134L, local.projects)
        assertEquals(50L, local.layers)
        assertEquals(20L, local.processing)
        assertEquals(204L, local.total)
        assertTrue(local.complete)
    }

    @Test
    fun mappingSummaryLinesSumToTotalIncludingStats() {
        val root = folder.root
        File(root, "mapping-missions/2e30e0b4-0000-4000-8000-000000000001/originals/a.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(134))
        }
        File(root, "mapping-layers/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/map.tif").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(50))
        }
        File(root, "mapping-processing/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee/work.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(20))
        }
        File(root, "mapping-storage-stats/index.json").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(17))
        }
        val report = org.ncssar.rid2caltopo.mapping.MappingStorageSummary.measure(root, blockSize = 1L)
        assertEquals(134L + 50L + 20L + 17L, report.totalBytes)
        assertEquals(report.totalBytes, report.lineSum())
    }

    @Test
    fun storageUsageLabelsMatchProjectInspectorBreakdown() {
        val usage = MappingStorageUsage(
            originals = 1_000,
            maps2D = 200,
            models3D = 300,
            working = 50,
            planning = 10,
        )
        assertEquals(1_560L, usage.total)
        assertTrue(usage.totalLabel.contains("KiB") || usage.totalLabel.contains("B"))
        assertEquals(500L, usage.maps2D + usage.models3D)
    }
}

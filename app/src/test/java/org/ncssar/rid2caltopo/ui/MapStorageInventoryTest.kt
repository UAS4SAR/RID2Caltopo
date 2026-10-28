package org.ncssar.rid2caltopo.ui

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import org.ncssar.rid2caltopo.video.mapcache.MapCacheUsage

class MapStorageInventoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun linkedAndPhysicalMapRootsCountOnce() {
        val project = temporary.newFolder("project")
        File(project, "original.jpg").writeBytes(ByteArray(17))
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), project.toPath())
        val result = localMapStorage(listOf(listOf(project), listOf(alias, project), emptyList()))
        assertEquals(17L, result.total)
        assertEquals(0L, result.layers)
        assertTrue(result.complete)
    }

    @Test fun detachedMapVolumeIsIncompleteRatherThanReportedEmpty() {
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), File(temporary.root, "missing-card").toPath())
        val result = localMapStorage(listOf(listOf(alias), emptyList(), emptyList()))
        assertFalse(result.complete)
    }

    @Test fun aolIsStoredUsageWhileReservationIsSeparate() {
        val usage = MapCacheUsage(1, 2, 3, 4, reserved = 90, aol = 5)
        assertEquals(15L, usage.total)
        assertEquals(90L, usage.reserved)
    }
}

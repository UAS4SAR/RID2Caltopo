package org.ncssar.rid2caltopo.video.surface

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Mirrors SurfaceSourceReuseTests on iOS.
class SurfaceSourceReuseTest {
    private val sha = "a".repeat(64)
    private val source = SurfaceSource("https://rockyweb.usgs.gov/Projects/A/LAZ/one.laz", "meta", "2020-01-01", 40_000_000L, "A")
    private val other = SurfaceSource("https://rockyweb.usgs.gov/Projects/A/LAZ/two.laz", "meta", "2020-01-01", 30_000_000L, "A")
    private val record = RetainedSourceRecord(source.url, 40_100_000L, sha)
    private val plan = SurfacePreparation.grid(SurfaceBounds(-122.01, 37.0, -122.0, 37.01)).copy(sources = listOf(source, other))

    @Test fun completeVerifiedFileIsReused() {
        assertTrue(SurfaceSourceReuse.reusable(source, record, 40_100_000L, sha))
    }

    @Test fun partialWrongSizeOrChangedFilesAreNotReused() {
        assertFalse("partial", SurfaceSourceReuse.reusable(source, record, 20_000_000L, sha))
        assertFalse("too long", SurfaceSourceReuse.reusable(source, record, 40_100_001L, sha))
        assertFalse("missing file", SurfaceSourceReuse.reusable(source, record, null, null))
        assertFalse("no record", SurfaceSourceReuse.reusable(source, null, 40_100_000L, sha))
        assertFalse("checksum changed", SurfaceSourceReuse.reusable(source, record, 40_100_000L, "b".repeat(64)))
        assertFalse("not hashed", SurfaceSourceReuse.reusable(source, record, 40_100_000L, null))
        assertFalse("different source", SurfaceSourceReuse.reusable(other, record, 40_100_000L, sha))
        assertFalse("empty", SurfaceSourceReuse.reusable(source, record.copy(bytes = 0), 0, sha))
    }

    @Test fun workFolderIsKeyedBySelection() {
        val same = SurfacePreparation.grid(SurfaceBounds(-122.01, 37.0, -122.0, 37.01)).copy(sources = listOf(other, source))
        assertEquals("source order does not matter", SurfaceSourceReuse.workKey(plan), SurfaceSourceReuse.workKey(same))
        val moved = SurfacePreparation.grid(SurfaceBounds(-122.02, 37.0, -122.0, 37.01)).copy(sources = listOf(source, other))
        assertNotEquals("different area", SurfaceSourceReuse.workKey(plan), SurfaceSourceReuse.workKey(moved))
        assertNotEquals("different files", SurfaceSourceReuse.workKey(plan), SurfaceSourceReuse.workKey(plan.copy(sources = listOf(source))))
        assertNotEquals("catalog size changed", SurfaceSourceReuse.workKey(plan),
            SurfaceSourceReuse.workKey(plan.copy(sources = listOf(source.copy(bytes = 41_000_000L), other))))
        assertTrue(SurfaceSourceReuse.workDirectoryName(plan).startsWith("aol-prep-"))
    }

    @Test fun staleFoldersForOtherSelectionsAreCleared() {
        val keep = SurfaceSourceReuse.workDirectoryName(plan)
        val names = listOf(keep, "aol-prep-old", "aol-prep-1234-uuid", "image_manager_disk_cache")
        assertEquals(listOf("aol-prep-old", "aol-prep-1234-uuid"), SurfaceSourceReuse.staleWorkDirectories(names, keep))
        assertEquals(listOf(keep, "aol-prep-old", "aol-prep-1234-uuid"), SurfaceSourceReuse.staleWorkDirectories(names, null))
    }

    @get:Rule val temp = TemporaryFolder()

    @Test fun closeAndStartupCleanupDeletesOnlyAolWorkFolders() {
        val root = temp.newFolder("cache")
        val kept = File(root, SurfaceSourceReuse.workDirectoryName(plan)).apply { mkdirs() }
        File(kept, "source-0.laz").writeText("lidar")
        File(kept, "source-0.record").writeText(record.encode())
        File(kept, "source-1.laz.part").writeText("partial")
        File(root, "aol-prep-1234-uuid/prepared").mkdirs()
        val tiles = File(root, "image_manager_disk_cache").apply { mkdirs() }
        File(tiles, "tile.png").writeText("png")
        val looseFile = File(root, "aol-prep-note.txt").apply { writeText("not a folder") }

        assertEquals(2, SurfaceSourceReuse.deleteWorkDirectories(root))
        assertEquals(setOf("image_manager_disk_cache", "aol-prep-note.txt"), root.list()!!.toSet())
        assertTrue(File(tiles, "tile.png").isFile)
        assertTrue(looseFile.isFile)
        assertEquals(0, SurfaceSourceReuse.deleteWorkDirectories(root))
        assertEquals(0, SurfaceSourceReuse.deleteWorkDirectories(File(root, "missing")))
    }

    @Test fun selectionChangeCleanupKeepsOnlyCurrentSelection() {
        val root = temp.newFolder("cache2")
        val keepName = SurfaceSourceReuse.workDirectoryName(plan)
        File(root, keepName).mkdirs(); File(root, "aol-prep-other").mkdirs()
        assertEquals(1, SurfaceSourceReuse.deleteWorkDirectories(root, keepName))
        assertEquals(setOf(keepName), root.list()!!.toSet())
    }

    @Test fun recordRoundTripsAndRejectsDamage() {
        assertEquals(record, RetainedSourceRecord.decode(record.encode()))
        assertNull(RetainedSourceRecord.decode(null))
        assertNull(RetainedSourceRecord.decode("v1\n${source.url}\n40100000\n"))
        assertNull(RetainedSourceRecord.decode("v1\n${source.url}\nlots\n$sha\n"))
        assertNull(RetainedSourceRecord.decode("v1\n${source.url}\n40100000\nnot-a-hash\n"))
    }
}

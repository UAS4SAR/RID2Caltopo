package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class MissionProjectionGuardTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun stampsSkippedMissionAbortsSwapWhenStagedMissingOriginals() {
        val working = folder.newFolder("working")
        val staged = folder.newFolder("staged")
        val id = UUID.randomUUID().toString()
        val mission = File(working, id).apply { mkdirs() }
        File(mission, "originals").mkdirs()
        File(mission, "originals/${"%064x".format(1)}.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(mission, "mission.json").writeText("{}")
        // Staged has other content but not this stamps-skipped mission.
        File(staged, UUID.randomUUID().toString()).mkdirs()
        val verdict = MissionProjectionGuard.assertStagedPreservesWorking(working, staged)
        assertFalse(verdict.ok)
        assertTrue(verdict.missingMissionIds.contains(id))
        assertTrue(File(mission, "originals/${"%064x".format(1)}.jpg").isFile)
        assertTrue(working.exists())
    }

    @Test fun stagedCopyWithOriginalsAllowsSwap() {
        val working = folder.newFolder("working")
        val staged = folder.newFolder("staged")
        val id = UUID.randomUUID().toString()
        val digest = "%064x".format(7)
        for (root in listOf(working, staged)) {
            val mission = File(root, id).apply { mkdirs() }
            File(mission, "originals").mkdirs()
            File(mission, "originals/$digest.jpg").writeBytes(byteArrayOf(1))
        }
        assertTrue(MissionProjectionGuard.assertStagedPreservesWorking(working, staged).ok)
    }

    @Test fun atomicAliasInstallReplacesRealDirectory() {
        val dest = folder.newFolder("dest")
        File(dest, "hydrated.txt").writeText("ready")
        val alias = folder.newFolder("operational-state")
        File(alias, "legacy.txt").writeText("old")
        assertTrue(alias.isDirectory)
        assertFalse(Files.isSymbolicLink(alias.toPath()))
        MissionProjectionGuard.installAliasAtomically(alias, dest)
        assertTrue(MissionProjectionGuard.aliasPresent(alias))
        assertTrue(Files.isSymbolicLink(alias.toPath()))
        assertEquals(dest.canonicalFile, alias.canonicalFile)
        assertEquals("ready", File(alias, "hydrated.txt").readText())
        assertFalse(File(alias, "legacy.txt").exists())
    }

    @Test fun atomicAliasInstallNeverLeavesAliasAbsent() {
        val dest = folder.newFolder("dest")
        val alias = File(folder.root, "mapping-missions")
        Files.createSymbolicLink(alias.toPath(), dest.toPath())
        assertTrue(MissionProjectionGuard.aliasPresent(alias))
        val next = folder.newFolder("dest2")
        MissionProjectionGuard.installAliasAtomically(alias, next)
        assertTrue(MissionProjectionGuard.aliasPresent(alias))
        assertTrue(Files.isSymbolicLink(alias.toPath()))
        assertEquals(next.canonicalFile, alias.canonicalFile)
    }

    @Test fun orphanCleanupRefusesDirsWithOriginals() {
        val dir = folder.newFolder(UUID.randomUUID().toString())
        File(dir, "originals").mkdirs()
        File(dir, "originals/a.jpg").writeBytes(byteArrayOf(1))
        assertTrue(MissionProjectionGuard.hasOriginals(dir))
        try {
            require(!MissionProjectionGuard.hasOriginals(dir)) { "orphan cleanup refuses dirs with originals" }
            fail("expected require failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("originals"))
        }
        assertTrue(dir.exists())
        assertTrue(File(dir, "originals/a.jpg").isFile)
    }
}

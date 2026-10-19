package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class MappingLibraryFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun deletionBetweenDirectoryCheckAndListingIsEmpty() {
        val root = temp.newFolder("result")
        File(root, "tile.png").writeText("tile")
        assertFalse(MappingLibraryFiles.contains(root, "png") { directory ->
            directory.deleteRecursively()
            directory.listFiles()
        })
        assertFalse(MappingLibraryFiles.contains(root, "png"))
    }

    @Test fun disappearingChildDoesNotHideSurvivingResult() {
        val root = temp.newFolder("results")
        val removed = File(root, "removed").apply { mkdirs() }
        File(removed, "surface.glb").writeText("model")
        val kept = File(root, "kept").apply { mkdirs() }
        File(kept, "tile.png").writeText("tile")
        assertTrue(MappingLibraryFiles.contains(root, "png") { directory ->
            val snapshot = directory.listFiles()
            if (directory == root) removed.deleteRecursively()
            snapshot
        })
        assertFalse(MappingLibraryFiles.contains(root, "glb"))
    }

    @Test fun followsCardAliasWithoutLoopingAndReflectsDeletion() {
        val card = temp.newFolder("card")
        val tile = File(card, "tile.png").apply { writeText("tile") }
        val alias = File(temp.root, "alias")
        Files.createSymbolicLink(alias.toPath(), card.toPath())
        Files.createSymbolicLink(File(card, "loop").toPath(), card.toPath())
        assertTrue(MappingLibraryFiles.contains(alias, "png"))
        assertFalse(MappingLibraryFiles.contains(alias, "glb"))
        tile.delete()
        assertFalse(MappingLibraryFiles.contains(alias, "png"))
    }
}

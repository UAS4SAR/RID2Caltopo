package org.ncssar.rid2caltopo.mapping

import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MappingAutomaticReconstructionTest {
    @Test fun priorStatusDoesNotBlockNewCompletedSelectionAndRepeatedCallbacksDoNotRestartIt() {
        val root=Files.createTempDirectory("mapping-auto").toFile()
        try {
            File(root,"processing-status.json").writeText("{\"active\":false}")
            val selection=listOf("a","b","c")
            assertTrue(MappingAutomaticReconstruction.isNewSelection(root,selection))
            MappingAutomaticReconstruction.record(root,selection)
            assertFalse(MappingAutomaticReconstruction.isNewSelection(root,selection.reversed()))
            assertTrue(MappingAutomaticReconstruction.isNewSelection(root,selection+"d"))
        } finally { root.deleteRecursively() }
    }
}

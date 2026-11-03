package org.ncssar.rid2caltopo.mapping

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MappingStorageBudgetTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun missingPathIsProgrammingErrorNotFull() {
        val missing = File(folder.root, "gone/deeper/missing")
        val error = assertThrows(IllegalStateException::class.java) {
            MappingStorageBudget.freeBytes(missing)
        }
        assertTrue(error.message!!.contains("programming error"))
        assertFalse(error.message!!.contains("full", ignoreCase = true))
        assertFalse(error.message!!.contains("need "))
    }

    @Test fun usableSpaceZeroOnMissingPathIsNotFull() {
        val missing = File("/definitely/not/a/real/mapping/path-${System.nanoTime()}")
        val error = assertThrows(IllegalStateException::class.java) {
            MappingStorageBudget.requireFree(missing, needBytes = 1L, bucket = "Mapping work space")
        }
        assertTrue(error.message!!.contains("programming error"))
        assertFalse(error.message!!.lowercase().contains("full"))
    }

    @Test fun requireFreeMessageIncludesNeedFreeBucketAndLabel() {
        val dir = folder.newFolder("mapping-processing")
        val need = dir.usableSpace + MappingPhotoStore.FREE_SPACE_RESERVE
        val error = assertThrows(IllegalStateException::class.java) {
            MappingStorageBudget.requireFree(dir, needBytes = need, bucket = "Mapping work space")
        }
        val message = error.message!!
        assertTrue(message.startsWith("Mapping work space ("))
        assertTrue(message.contains("need "))
        assertTrue(message.contains("free "))
        assertTrue(message.contains("reserve "))
        assertTrue(message.contains(" GB"))
        assertTrue(Regex("""need \d+\.\d{2} GB""").containsMatchIn(message))
        assertTrue(Regex("""free \d+\.\d{2} GB""").containsMatchIn(message))
        assertTrue(Regex("""reserve \d+\.\d{2} GB""").containsMatchIn(message))
    }

    @Test fun labelNeverClaimsTabletForSdPath() {
        val sd = File("/storage/4A21-BEEF/Android/data/org.ncssar.rid2caltopo/files/mapping-processing")
        assertEquals("SD 4A21", MappingStorageBudget.label(sd))
        assertEquals("Tablet", MappingStorageBudget.label(File("/data/user/0/org.ncssar.rid2caltopo/files")))
    }

    @Test fun checkExistingAgainstDestinationAfterScratchRename() {
        val destination = folder.newFolder("mapping-layers", "e80815fa-0000-0000-0000-000000000001")
        // Simulates post-publish: scratch is gone; budget must use the published destination.
        val missingScratch = File(folder.root, "mapping-processing/dead-scratch")
        assertThrows(IllegalStateException::class.java) { MappingStorageBudget.freeBytes(missingScratch) }
        MappingStorageBudget.checkExisting(destination, bucket = "Mapping work space")
    }
}

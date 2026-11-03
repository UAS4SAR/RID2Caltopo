package org.ncssar.rid2caltopo.mapping

import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MappingStorageEstimateTest {
    @get:Rule val folder = TemporaryFolder()

    private class FilesContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext() = this
        override fun getSystemService(name: String): Any? = null
    }

    @Test fun warningUsesProjectedVolumeOccupancyNotArbitraryGigabyteBudget() {
        val estimate = MappingStorageEstimate(100, 50, 50)
        assertFalse(estimate.warn(1000, 401))
        assertTrue(estimate.warn(1000, 400))
        assertTrue(estimate.warn(1000, 300))
        assertFalse(estimate.warn(1000000, 900000))
    }

    @Test fun peakIncludesBoundedRawWorkingSetAndGrowingOriginalStorage() {
        val mission = DjiCloudTestFixture.mission()
        val a = MappingStorageEstimate.plan(mission, 250); val b = MappingStorageEstimate.plan(mission, 1000)
        assertEquals(a.temporary, b.temporary)
        assertEquals(a.originals * 4, b.originals)
        assertTrue(a.peakAdditionalBytes > a.originals + a.products)
    }

    @Test fun assessReportsPerBucketNeedFreeAndIncludesShareZipInProducts() {
        val originals = folder.newFolder("mapping-missions")
        val scratch = folder.newFolder("mapping-processing")
        val layers = folder.newFolder("mapping-layers")
        val estimate = MappingStorageEstimate(originals = 1000, temporary = 2000, products = 3000)
        assertTrue(estimate.needs(originals, scratch, layers).any { it.bucket == "Map products" && it.needBytes == 3000L })
        val assessment = MappingStorageEstimate.assess(FilesContext(folder.root), estimate, originals, scratch, layers)
        assertEquals(3, assessment.buckets.size)
        assertEquals(setOf("Original photos", "Mapping work space", "Map products"), assessment.buckets.map { it.bucket }.toSet())
        assessment.buckets.forEach { bucket ->
            assertTrue(bucket.freeBytes > 0)
            assertTrue(bucket.message().contains("need "))
            assertTrue(bucket.message().contains("free "))
            assertTrue(bucket.message().contains(bucket.bucket))
        }
        val json = assessment.json()
        assertTrue(json.has("buckets"))
        assertTrue(json.has("summary"))
        assertFalse(assessment.summary().contains("Tablet storage is full", ignoreCase = true))
    }

    @Test fun assessShortageMessageNamesBucketNeedAndFree() {
        val originals = folder.newFolder("m")
        val scratch = folder.newFolder("p")
        val layers = folder.newFolder("l")
        val huge = MappingStorageEstimate(originals = Long.MAX_VALUE / 8, temporary = Long.MAX_VALUE / 8, products = Long.MAX_VALUE / 8)
        val assessment = MappingStorageEstimate.assess(FilesContext(folder.root), huge, originals, scratch, layers)
        assertTrue(assessment.shortages.isNotEmpty())
        val text = assessment.summary()
        assertTrue(text.contains("need "))
        assertTrue(text.contains("free "))
        assertTrue(text.contains("reserve "))
        assertTrue(assessment.shortages.any { text.contains(it.bucket) })
    }
}

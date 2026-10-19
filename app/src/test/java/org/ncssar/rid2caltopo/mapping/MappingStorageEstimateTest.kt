package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test

class MappingStorageEstimateTest {
    @Test fun warningUsesProjectedVolumeOccupancyNotArbitraryGigabyteBudget() {
        val estimate=MappingStorageEstimate(100,50,50)
        assertFalse(estimate.warn(1000,401))
        assertTrue(estimate.warn(1000,400))
        assertTrue(estimate.warn(1000,300))
        assertFalse(estimate.warn(1000000,900000))
    }
    @Test fun peakIncludesBoundedRawWorkingSetAndGrowingOriginalStorage() {
        val mission=DjiCloudTestFixture.mission()
        val a=MappingStorageEstimate.plan(mission,250);val b=MappingStorageEstimate.plan(mission,1000)
        assertEquals(a.temporary,b.temporary)
        assertEquals(a.originals*4,b.originals)
        assertTrue(a.peakAdditionalBytes>a.originals+a.products)
    }
}

package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test

class MappingRegionCoverageTest {
    @Test fun regionalCoveragePreservesAreaAndSkipsEmptyConcaveCorner() {
        val shape=MappingSurfaceCoverage(listOf(0.0 to 0.0,100.0 to 0.0,100.0 to 40.0,40.0 to 40.0,40.0 to 100.0,0.0 to 100.0))
        val left=shape.clipped(MappingRegionPlan.Bounds(0.0,0.0,50.0,100.0))!!
        val right=shape.clipped(MappingRegionPlan.Bounds(50.0,0.0,100.0,100.0))!!
        assertEquals(shape.area,left.area+right.area,1e-6)
        assertNull(shape.clipped(MappingRegionPlan.Bounds(50.0,50.0,100.0,100.0)))
        assertTrue(left.contains(20.0,80.0));assertFalse(right.contains(80.0,80.0))
    }
    @Test fun coverageForLargeAssignmentIsBoundedToOneRegion() {
        val large=MappingSurfaceCoverage(listOf(0.0 to 0.0,1000.0 to 0.0,1000.0 to 1000.0,0.0 to 1000.0))
        val region=large.clipped(MappingRegionPlan.Bounds(0.0,0.0,70.0,80.0))!!
        val cells=region.cells(FloatArray(401*401){1f},401,401,0.0,0.0,.2)
        assertEquals(cells.total,cells.known)
    }
}

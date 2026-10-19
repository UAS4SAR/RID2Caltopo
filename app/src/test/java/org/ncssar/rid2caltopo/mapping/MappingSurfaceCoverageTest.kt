package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test

class MappingSurfaceCoverageTest {
    private val assignment=MappingSurfaceCoverage(listOf(0.0 to 0.0,10.0 to 0.0,10.0 to 10.0,0.0 to 10.0))
    @Test fun outerUnknownRectangleDoesNotDiluteAssignmentCoverage() {
        val grid=FloatArray(30*30){Float.NaN}
        for(y in 10..19)for(x in 10..19)grid[y*30+x]=0f
        val coverage=assignment.cells(grid,30,30,-10.0,-10.0,1.0)
        assertEquals(100,coverage.total);assertEquals(100,coverage.known);assertEquals(1.0,coverage.fraction,0.0)
        assertEquals(800,grid.count(Float::isNaN))
    }
    @Test fun missingAssignmentBeyondGridAndInteriorHolesRemainUnknown() {
        val coverage=assignment.cells(FloatArray(25),5,5,5.0,0.0,1.0)
        assertEquals(100,coverage.total);assertEquals(25,coverage.known);assertEquals(.25,coverage.fraction,0.0)
        val half=assignment.cells(FloatArray(100){if(it%10<5)0f else Float.NaN},10,10,0.0,0.0,1.0)
        assertEquals(.5,half.fraction,0.0);assertFalse(half.fraction>.5)
    }
    @Test fun imageCoverageCountsOnlyActualColoredAssignmentPixels() {
        val pixels=IntArray(256*256){-1}
        assertEquals(100,assignment.imagePixels(pixels,-10.0,20.0,1.0))
        assertEquals(0,assignment.imagePixels(pixels,100.0,20.0,1.0))
        pixels.fill(0);assertEquals(0,assignment.imagePixels(pixels,-10.0,20.0,1.0))
    }
}

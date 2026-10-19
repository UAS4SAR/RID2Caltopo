package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingSurfaceMeshTest {
    @Test fun largeUnknownPaddingDoesNotCoarsenTheSupportedPreview() {
        val width=1200;val height=1300;val heights=FloatArray(width*height){Float.NaN}
        for(row in 300 until 1000)for(col in 300 until 900)heights[row*width+col]=10f
        assertEquals(2,MappingSurfaceMesh.stride(heights,width,height,.2))
    }
    @Test fun realSupportedVertexLimitStillBoundsThePreview() {
        val width=1200;val height=1300;val stride=MappingSurfaceMesh.stride(FloatArray(width*height){10f},width,height,.2)
        assertEquals(3,stride)
        assertTrue(((width-1)/stride+1)*((height-1)/stride+1)<=MappingSurfaceMesh.MAX_VERTICES)
    }
    @Test fun strideExpressesTheSamePhysicalSpacing() {
        assertEquals(2,MappingSurfaceMesh.stride(FloatArray(100),10,10,.2))
        assertEquals(4,MappingSurfaceMesh.stride(FloatArray(100),10,10,.1))
    }
}

package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.assertEquals
import org.junit.Test

class MappingSurfaceNativeFiniteTest {
    @Test fun percentIntGuardsNonFiniteAndClamps() {
        assertEquals(0, MappingSurfaceNative.percentInt(Double.NaN))
        assertEquals(0, MappingSurfaceNative.percentInt(Double.NEGATIVE_INFINITY))
        assertEquals(0, MappingSurfaceNative.percentInt(Double.POSITIVE_INFINITY))
        assertEquals(0, MappingSurfaceNative.percentInt(-5.0))
        assertEquals(100, MappingSurfaceNative.percentInt(2.0))
        assertEquals(42, MappingSurfaceNative.percentInt(0.42))
    }

    @Test fun finiteCountGuardsNonFiniteAndClamps() {
        assertEquals(0, MappingSurfaceNative.finiteCount(Double.NaN))
        assertEquals(0, MappingSurfaceNative.finiteCount(Double.NEGATIVE_INFINITY))
        assertEquals(0, MappingSurfaceNative.finiteCount(-1.0))
        assertEquals(12, MappingSurfaceNative.finiteCount(12.7))
        val fill = MappingSurfaceNative.OrthoFill(FloatArray(0), ByteArray(0),
            doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, Double.NaN))
        assertEquals(0, fill.estimatedCells)
        assertEquals(0, fill.elapsedMs)
        assertEquals(0.0, fill.fillRadiusMeters, 0.0)
    }
}

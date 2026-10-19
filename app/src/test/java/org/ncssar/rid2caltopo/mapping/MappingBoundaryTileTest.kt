package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToLong

class MappingBoundaryTileTest {
    @Test fun fractionalZoomAndPixelRoundingKeepTheSameGeographicTile() {
        val expected=MappingBoundaryTile(21,342920,800320)
        for(zoom in listOf(21.0,21.1,21.4,21.8,21.99)) {
            val size=256*2.0.pow(zoom-21)
            assertEquals(expected,MappingBoundaryTile.projected(zoom,(expected.x*size).roundToLong(),(expected.y*size).roundToLong(),size))
        }
    }
    @Test fun mapWrappingPreservesTileIdentity() {
        assertEquals(MappingBoundaryTile(3,7,0),MappingBoundaryTile.projected(3.0,-256,2048,256.0))
    }
    @Test fun crossingIntegerZoomSelectsANewTileResolution() {
        assertNotEquals(MappingBoundaryTile.projected(21.9,2560,5120,477.7),MappingBoundaryTile.projected(22.0,2560,5120,256.0))
    }
}

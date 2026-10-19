package org.ncssar.rid2caltopo.mapping
import org.junit.Assert.*
import org.junit.Test
class MappingPositionQualityTest {
    @Test fun nonFixedFlightUsesConservativePriorsInsteadOfRejectingEveryPhoto(){
        assertFalse(MappingPositionQuality.fixed(16,.09553,.08448,.15153))
        assertEquals(1.0,MappingPositionQuality.sigma(.09553,false,false),0.0)
        assertEquals(2.0,MappingPositionQuality.sigma(.15153,false,true),0.0)
        assertEquals(1.0,MappingPositionQuality.sigma(null,false,false),0.0)
        assertEquals(4.0,MappingPositionQuality.sigma(4.0,false,true),0.0)
        assertThrows(IllegalArgumentException::class.java){MappingPositionQuality.sigma(11.0,false,true)}
    }
    @Test fun fixedRtkRetainsMeasuredUncertainty(){
        assertTrue(MappingPositionQuality.fixed(50,.09553,.08448,.15153))
        assertFalse(MappingPositionQuality.fixed(50,null,.08448,.15153))
        assertEquals(.09553,MappingPositionQuality.sigma(.09553,true,false),0.0)
        assertEquals(.03,MappingPositionQuality.sigma(.01,true,true),0.0)
    }
}

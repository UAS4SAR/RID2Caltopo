package org.ncssar.rid2caltopo.mapping
import org.junit.Assert.*
import org.junit.Test
class MappingHeightConsensusTest {
    @Test fun supportedLowerSurfaceBeatsEarlyHighModes() {
        val votes=MappingHeightConsensus(2)
        listOf(10f,10.2f,5f,5.1f,0f,.1f,0f,.2f,0f).forEach{votes.add(0,it)}
        assertEquals(.06f,votes.height(0),.00001f)
        votes.add(1,3f);assertTrue(votes.height(1).isNaN())
    }
    @Test fun SupportedCanopyRemainsAboveGround() {
        val votes=MappingHeightConsensus(1)
        listOf(0f,.1f,10f,10.1f,10.2f,10f).forEach{votes.add(0,it)}
        assertEquals(10.075f,votes.height(0),.00001f)
    }
}

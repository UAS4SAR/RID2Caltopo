package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

class BtrxMakersTest {
    @Test
    fun clueNamesTheMakerAndKind() {
        assertEquals("Apple · wearable", BtrxMakers.clue(0x004C, P.DEV_WEARABLE))
        assertEquals("Samsung · phone", BtrxMakers.clue(0x0075, P.DEV_PHONE))
        assertEquals("GN Hearing · audio", BtrxMakers.clue(0x0089, P.DEV_AUDIO))
        assertEquals("Tile · tracker", BtrxMakers.clue(0x067C, P.DEV_TRACKER))
        assertEquals("Microsoft · computer", BtrxMakers.clue(0x0006, P.DEV_COMPUTER))
    }

    @Test
    fun unknownMakerShowsHexAndUnknownPartsSayUnknown() {
        assertEquals("0x1234 · audio", BtrxMakers.clue(0x1234, P.DEV_AUDIO))
        assertEquals("unknown · phone", BtrxMakers.clue(0, P.DEV_PHONE))
        assertEquals("Garmin · unknown", BtrxMakers.clue(0x0087, P.DEV_UNKNOWN))
        assertEquals("unknown device", BtrxMakers.clue(0, P.DEV_UNKNOWN))
    }

    @Test
    fun tableMatchesTheSigList() {
        // Spot checks against company_identifiers.yaml (Nov 10 2026).
        val expected = mapOf(0x004C to "Apple", 0x0075 to "Samsung", 0x00E0 to "Google", 0x0006 to "Microsoft",
            0x0087 to "Garmin", 0x009E to "Bose", 0x012D to "Sony", 0x0089 to "GN Hearing", 0x0282 to "Sonova",
            0x0107 to "Demant", 0x00BA to "Starkey", 0x067C to "Tile")
        expected.forEach { (id, name) -> assertEquals(name, BtrxMakers.NAMES[id]) }
        assertEquals(null, BtrxMakers.NAMES[0x0224]) // Comarch SA, not Fitbit
    }

    @Test
    fun steeringViewCarriesTheTargetClue() {
        val st = BtrxSteering()
        val s = P.Steer(1, P.STEER_F_TARGET or P.STEER_F_IMU_OK, 1000L, 50, 9, -60, -70, -58, 3, 3, 0x004C, P.DEV_WEARABLE, 1,
            ByteArray(6), 0, 0)
        st.onSteer(s, 10_000L)
        assertEquals("Apple · wearable", st.view(10_000L)!!.clue)
    }
}

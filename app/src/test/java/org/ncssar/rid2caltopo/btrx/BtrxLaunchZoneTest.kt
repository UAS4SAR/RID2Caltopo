package org.ncssar.rid2caltopo.btrx

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ken, Nov 23: strong sources near the takeoff point are muted on the ground and through the
 * initial climb. The engine applies the rule (tools/btrx_bearing_test test_launch_zone); the app
 * only states it, with the same numbers as btrx_bearing.h and test-fixtures/btrx/launch-zone.tsv.
 * Same as the iPad's BtrxLaunchZoneTests.
 */
class BtrxLaunchZoneTest {
    private fun fixture(name: String) = sequenceOf(File(name), File("../$name")).first { it.isFile }

    @Test fun constantsMatchEngineAndFixture() {
        val header = fixture("apple/Sources/CBtrxBearing/include/btrx_bearing.h").readText()
        assertTrue(header.contains("#define BTRX_BEARING_LAUNCH_RSSI_DBM (${BtrxLaunchZone.RSSI_DBM})\n"))
        assertTrue(header.contains("#define BTRX_BEARING_LAUNCH_RADIUS_M ${BtrxLaunchZone.RADIUS_M}\n"))
        assertTrue(header.contains("#define BTRX_BEARING_LAUNCH_ZONE_MAX_AGL_M ${BtrxLaunchZone.ZONE_MAX_AGL_M}\n"))
        assertTrue(header.contains("#define BTRX_BEARING_LAUNCH_WINDOW_S ${BtrxLaunchZone.WINDOW_S}\n"))
        val c = fixture("test-fixtures/btrx/launch-zone.tsv").readLines().map { it.split('\t') }
            .filter { it[0] == "const" }.associate { it[1] to it[2].toInt() }
        assertEquals(BtrxLaunchZone.RSSI_DBM, c["rssi_dbm"])
        assertEquals(BtrxLaunchZone.RADIUS_M, c["radius_m"])
        assertEquals(BtrxLaunchZone.ZONE_MAX_AGL_M, c["zone_max_agl_m"])
        assertEquals(BtrxLaunchZone.WINDOW_S, c["window_s"])
        // The engine's defaults come from the shared constants.
        val engine = fixture("apple/Sources/CBtrxBearing/btrx_bearing.c").readText()
        assertTrue(engine.contains("c->launch_rssi_dbm = BTRX_BEARING_LAUNCH_RSSI_DBM; c->launch_radius_m = BTRX_BEARING_LAUNCH_RADIUS_M;"))
        assertTrue(engine.contains("c->launch_zone_max_agl_m = BTRX_BEARING_LAUNCH_ZONE_MAX_AGL_M;"))
    }

    @Test fun settingTextStatesTheZone() {
        assertEquals("Mute strong sources near the takeoff spot", BtrxBearingView.SETTING_LAUNCH_MUTE)
        assertEquals(
            "Devices heard -70 dBm or stronger within 30 m of the takeoff spot in the first 5 min, on the ground and through the " +
                "initial climb (up to 40 m AGL, until the drone first leaves that area), are muted: usually the team at the launch " +
                "site. Show them from the map chip.",
            BtrxBearingView.SETTING_LAUNCH_MUTE_HINT,
        )
    }
}

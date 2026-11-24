package org.ncssar.rid2caltopo.btrx

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ken, Nov 23 (Timberland flight): stale sources fade and leave within 60 s, new ones come
 * first, the Nearby Sources panel sorts by signal and freshness at most every 12 s, and the
 * bearing layer goes on with BTRX. Same cases as the iPad's BtrxStalenessTests, from the shared
 * test-fixtures/btrx/staleness.tsv.
 */
class BtrxStalenessTest {
    private fun fixture(name: String) = sequenceOf(File(name), File("../$name")).first { it.isFile }
    private val rows = fixture("test-fixtures/btrx/staleness.tsv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

    private fun src(key: Long, rssi: Int?, firstUs: Long, lastUs: Long, est: Boolean = false) = BtrxBearingSource(
        index = 0, key = key, shortId = "S$key", provisional = false, devClass = 1, rgb = 0x2196F3, flags = 0,
        firstHeardUs = firstUs, lastHeardUs = lastUs, reportsIn = 10, reportsUsed = 10, reportsInWindow = 10, peakAlpha = 0.5f,
        estimate = if (est) BtrxBearingEstimate(39.0, -121.0, 9f, 4f, 30f, false, 0.0, 0.0, 0f) else null,
        latestRay = null, lastRssiDbm = rssi,
    )

    private fun us(s: String) = (s.toDouble() * 1e6).toLong()

    /** key:rssi:a:b; ages ([agesAt] = now) or absolute seconds. */
    private fun sources(spec: String, agesAt: Long? = null) = spec.split(',').map { e ->
        val f = e.split(':')
        val rssi = f[1].takeIf { it != "-" }?.toInt()
        if (agesAt != null) src(f[0].toLong(), rssi, agesAt - us(f[2]), agesAt - us(f[3]))
        else src(f[0].toLong(), rssi, us(f[2]), us(f[3]))
    }

    @Test fun constantsMatchFixtureAndEngine() {
        val c = rows.filter { it[0] == "const" }.associate { it[1] to it[2].toFloat() }
        assertEquals(c["fade_start_s"], BtrxStaleness.FADE_START_S)
        assertEquals(c["hide_s"], BtrxStaleness.HIDE_S)
        assertEquals(c["new_s"], BtrxStaleness.NEW_S)
        assertEquals(c["panel_resort_s"], BtrxStaleness.PANEL_RESORT_S)
        assertEquals(c["age_penalty_db_per_s"], BtrxStaleness.AGE_PENALTY_DB_PER_S)
        val header = fixture("apple/Sources/CBtrxBearing/include/btrx_bearing.h").readText()
        assertTrue(header.contains("#define BTRX_BEARING_STALE_FADE_START_S ${BtrxStaleness.FADE_START_S.toInt()}\n"))
        assertTrue(header.contains("#define BTRX_BEARING_STALE_HIDE_S ${BtrxStaleness.HIDE_S.toInt()}\n"))
        // The app's engine runs with them (the engine default is off for its own tests).
        val jni = File("src/main/cpp/btrx_bearing_jni.c").readText()
        assertTrue(jni.contains("cfg.stale_fade_start_s = BTRX_BEARING_STALE_FADE_START_S;"))
        assertTrue(jni.contains("cfg.stale_hide_s = BTRX_BEARING_STALE_HIDE_S;"))
    }

    @Test fun presenceFromFixture() {
        val ps = rows.filter { it[0] == "presence" }
        assertEquals(7, ps.size)
        for (r in ps) assertEquals("age ${r[1]}", r[2].toFloat(), BtrxStaleness.presence(r[1].toFloat()), 1e-5f)
    }

    @Test fun rankFromFixture() {
        val rs = rows.filter { it[0] == "rank" }
        assertEquals(2, rs.size)
        val now = 1_000_000_000L
        for (r in rs) assertEquals(r[1], r[3], BtrxStaleness.rank(sources(r[2], agesAt = now), now).joinToString(",") { it.key.toString() })
    }

    @Test fun panelOrderFromFixture() {
        val steps = rows.filter { it[0] == "panel" }
        assertEquals(7, steps.size)
        for ((case, rs) in steps.groupBy { it[1] }) {
            val order = BtrxPanelOrder()
            for (r in rs) {
                val got = order.arrange(sources(r[5]), us(r[2]), holding = r[3] == "1", showOlder = r[4] == "1")
                assertEquals("$case t=${r[2]}", r[6], got.joinToString(",") { it.key.toString() })
            }
        }
    }

    @Test fun mapDrawsFadesAndHidesStaleSources() {
        val now = 100_000_000L
        val fresh = src(1, -60, 0, now - 5_000_000, est = true)
        val fading = src(2, -60, 0, now - 40_000_000, est = true)
        val stale = src(3, -60, 0, now - 61_000_000)
        val fresh10 = src(4, -60, now - 3_000_000, now - 1_000_000)
        val snap = BtrxBearingSnapshot(1L, now, 0, 0, 0.0, 0.0, 0.0, 0.0, ByteArray(0), listOf(fresh, fading, stale, fresh10),
            BtrxBearingStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0L, 0L, 0L, 0, 0, 0f, 0f, 0, 0, 0, 0L, 0, emptyList()))
        assertEquals(BtrxBearingDrawMode.FULL, BtrxBearingView.drawMode(fresh, snap))
        assertEquals(BtrxBearingDrawMode.FULL, BtrxBearingView.drawMode(fading, snap))
        assertEquals(BtrxBearingDrawMode.HIDDEN, BtrxBearingView.drawMode(stale, snap))
        assertTrue(BtrxStaleness.presence(fading, now) in 0.5f..0.6f)
        // New first (with the focus matches), fading last.
        assertTrue(BtrxBearingView.labelPriority(fresh10, snap) > BtrxBearingView.labelPriority(fresh, snap))
        assertTrue(BtrxBearingView.labelPriority(fading, snap) < BtrxBearingView.labelPriority(fresh, snap))
        assertTrue(BtrxBearingView.mapLabel(fresh10, now).startsWith("new · "))
        assertFalse(BtrxBearingView.mapLabel(fresh, now).startsWith("new"))
    }

    @Test fun bearingLayerGoesOnWithBtrx() {
        assertTrue(BtrxBearingAutoOn.shouldTurnOn(btrxEnabled = true, bearingOn = false))
        assertFalse(BtrxBearingAutoOn.shouldTurnOn(btrxEnabled = true, bearingOn = true))
        assertFalse(BtrxBearingAutoOn.shouldTurnOn(btrxEnabled = false, bearingOn = false))
        val m = File("src/main/java/org/ncssar/rid2caltopo/btrx/BtrxManager.kt").readText()
        assertTrue(m.contains("if (enabled || usbDebug) autoBearingOn(\"BTRX enabled at launch\")"))
        assertTrue(m.contains("if (enabled) autoBearingOn(\"Desk Pod Bluetooth link turned on\")"))
        assertTrue(m.contains("if (enabled) autoBearingOn(\"Desk Pod USB debug link turned on\")"))
        assertFalse(BtrxBearingText.LAYER_SWITCH_HINT.contains("Off at every launch"))
    }
}

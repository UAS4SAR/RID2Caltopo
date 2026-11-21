package org.ncssar.rid2caltopo.btrx

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nov 20 A5 test: the tap panel ran off the screen and under the stream PiP. Same cases as the
 * iPad's BtrxPopupPlacementTests, from the shared test-fixtures/btrx/popup-placement.tsv.
 */
class BtrxPopupPlacementTest {
    @Test
    fun sharedFixtureMatchesIos() {
        val fixture = sequenceOf(File("test-fixtures/btrx/popup-placement.tsv"), File("../test-fixtures/btrx/popup-placement.tsv")).first { it.isFile }
        val rows = fixture.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(10, rows.size)
        for (r in rows) {
            val f = { i: Int -> r[i].toFloat() }
            val obstacle = if (r[8] == "-") null else BtrxPopupPlacement.Box(f(8), f(9), f(10), f(11))
            val plan = if (r[1] == "point") BtrxPopupPlacement.atPoint(f(2), f(3), f(4), f(5), f(6), f(7), obstacle, 8f)
            else BtrxPopupPlacement.atBottomStart(f(2), f(3), f(4), f(5), f(6), f(7), obstacle, 8f)
            val name = r[0]
            assertEquals(name, r[12].toInt(), plan.choice)
            assertEquals(name, f(13), plan.x, 0.5f)
            assertEquals(name, f(14), plan.y, 0.5f)
            assertEquals(name, f(15), plan.maxHeight, 0.5f)
            assertEquals(name, r[16] == "1", plan.clear)
            // Always on the map; clear of the PiP whenever it says so.
            val w = minOf(f(4), f(2) - 16f)
            val h = minOf(f(5), plan.maxHeight)
            assertTrue(name, plan.x >= 7.99f && plan.y >= 7.99f && plan.x + w <= f(2) - 7.99f && plan.y + h <= f(3) - 7.99f)
            if (plan.clear && obstacle != null) {
                assertFalse(name, BtrxPopupPlacement.overlaps(BtrxPopupPlacement.Box(plan.x, plan.y, plan.x + w, plan.y + h), obstacle, 8f))
            }
        }
    }
}

package org.ncssar.rid2caltopo.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ken, Nov 23: the drone telemetry label on the map jittered as ATO/AGL digits came and went.
 * Same cases as the iPad's FixedWidthStatusLabelTests (test-fixtures/map-label/fixed-width-status.tsv).
 */
class FixedWidthStatusLabelTest {
    @Test fun sharedFixture() {
        val f = sequenceOf(File("test-fixtures/map-label/fixed-width-status.tsv"), File("../test-fixtures/map-label/fixed-width-status.tsv")).first { it.isFile }
        val rows = f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(9, rows.size)
        for (r in rows) assertEquals(r[0], r[1].replace('_', FIGURE_SPACE), fixedWidthStatusLabel(r[0]))
    }

    @Test fun sameWidthForOneTwoAndThreeDigits() {
        val a = fixedWidthStatusLabel("ATO:6' AGL:9' AOL:-- RNG:42' HDG:7°")
        val b = fixedWidthStatusLabel("ATO:66' AGL:99' AOL:-- RNG:420' HDG:77°")
        val c = fixedWidthStatusLabel("ATO:-66' AGL:999' AOL:-- RNG:4200' HDG:277°")
        // Every field before RNG stays put; RNG (last) may grow (Ken, Nov 23).
        assertEquals(a.indexOf(" RNG:"), b.indexOf(" RNG:"))
        assertEquals(a.indexOf(" RNG:"), c.indexOf(" RNG:"))
        assertTrue(c.endsWith("RNG:4200'"))
        // The negative-AOL highlight still finds its value behind the padding.
        assertEquals("AOL:${FIGURE_SPACE}-5'", fixedWidthStatusLabel("ATO:5' AOL:-5'").let { t -> negativeAolRange(t)!!.let { t.substring(it.first, it.last + 1) } })
        val src = File("src/main/java/org/ncssar/rid2caltopo/video/MapPaneDroneOverlays.kt").readText()
        assertTrue(src.contains("val text = fixedWidthStatusLabel(rawText)"))
        assertTrue(src.contains("fontFeatureSettings = \"tnum\""))
    }
}

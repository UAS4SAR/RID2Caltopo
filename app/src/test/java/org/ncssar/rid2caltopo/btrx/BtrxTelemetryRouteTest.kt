package org.ncssar.rid2caltopo.btrx

import StreamsLayoutMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.video.btrxPaneAreas

/**
 * Ken, Nov 23: BTRX telemetry is shown while BTRX is enabled and the Desk Pod is READY, in
 * the primary window only (the larger pane in a split, 5 % hysteresis, an exact tie goes to
 * the map), and there is no way to turn it off. Same cases as the iPad's BtrxTelemetryRouteTests, from the shared
 * test-fixtures/btrx/telemetry-route.tsv.
 */
class BtrxTelemetryRouteTest {
    private fun fixture(name: String) = sequenceOf(File(name), File("../$name")).first { it.isFile }
    private val rows = fixture("test-fixtures/btrx/telemetry-route.tsv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
    private fun window(s: String) = when (s) { "map" -> BtrxTelemetryWindow.MAP; "stream" -> BtrxTelemetryWindow.STREAM; else -> null }
    private fun src(path: String) = File(path).readText()

    @Test fun constantsMatchFixture() {
        val c = rows.filter { it[0] == "const" }.associate { it[1] to it[2] }
        assertEquals(c.getValue("hysteresis").toDouble(), BtrxTelemetryRoute.HYSTERESIS, 0.0)
        assertEquals(window(c.getValue("tie")), BtrxTelemetryRoute.TIE)
    }

    @Test fun primaryIsTheLargerPaneWithHysteresis() {
        val cases = rows.filter { it[0] == "area" }
        assertTrue(cases.size >= 20)
        for (r in cases) {
            assertEquals(r.joinToString(" "), window(r[4]),
                BtrxTelemetryRoute.primary(r[1].toDouble(), r[2].toDouble(), window(r[3])))
        }
    }

    @Test fun draggingTheDividerAboutTheMiddleDoesNotFlicker() {
        val drags = rows.filter { it[0] == "drag" }
        assertEquals(2, drags.size)
        for (r in drags) {
            var w = window(r[1])!!
            val fractions = r[2].split(',').map { it.toDouble() }
            val expected = r[3].split(',').map { window(it) }
            val got = fractions.map { f -> BtrxTelemetryRoute.forStreamFraction(f, w).also { w = it } }
            assertEquals(r.joinToString(" "), expected, got)
        }
    }

    @Test fun layoutsMapToPaneAreas() {
        // Full map (stream PiP or not) and full streams (map PiP or not): the inset never counts.
        assertEquals(1.0 to 0.0, btrxPaneAreas(StreamsLayoutMode.Map, 0.5f))
        assertEquals(0.0 to 1.0, btrxPaneAreas(StreamsLayoutMode.Streams, 0.5f))
        // Split: the streams pane's share of the width or height.
        assertEquals(0.5 to 0.5, btrxPaneAreas(StreamsLayoutMode.Both, 0.5f))
        val (m, s) = btrxPaneAreas(StreamsLayoutMode.Both, 0.7f)
        assertEquals(0.3, m, 1e-6); assertEquals(0.7, s, 1e-6)
        fun route(mode: StreamsLayoutMode, f: Float, prev: BtrxTelemetryWindow?) =
            btrxPaneAreas(mode, f).let { (a, b) -> BtrxTelemetryRoute.primary(a, b, prev) }
        assertEquals(BtrxTelemetryWindow.MAP, route(StreamsLayoutMode.Map, 0.9f, BtrxTelemetryWindow.STREAM))
        assertEquals(BtrxTelemetryWindow.STREAM, route(StreamsLayoutMode.Streams, 0.1f, BtrxTelemetryWindow.MAP))
        assertEquals(BtrxTelemetryWindow.MAP, route(StreamsLayoutMode.Both, 0.5f, null))
        assertEquals(BtrxTelemetryWindow.STREAM, route(StreamsLayoutMode.Both, 0.6f, BtrxTelemetryWindow.MAP))
        assertEquals(BtrxTelemetryWindow.MAP, route(StreamsLayoutMode.Both, 0.4f, BtrxTelemetryWindow.STREAM))
        assertEquals(BtrxTelemetryWindow.STREAM, route(StreamsLayoutMode.Both, 0.52f, BtrxTelemetryWindow.STREAM))
        // The live view reports every layout change and divider drag, on the device's own screen only.
        val screen = src("src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt")
        assertTrue(screen.contains("if (externalContentMode == null) {\n        LaunchedEffect(layoutMode, splitFraction) {"))
        assertTrue(screen.contains("BtrxManager.reportPaneAreas(mapArea, streamArea)"))
    }

    @Test fun telemetryIsAlwaysInExactlyOneWindow() {
        for (w in BtrxTelemetryWindow.entries) {
            assertNotEquals(BtrxTelemetryRoute.mapShows(true, w, true), BtrxTelemetryRoute.streamShows(w, true))
            assertFalse(BtrxTelemetryRoute.mapShows(false, w, true))
        }
        assertEquals("Telemetry shown on: Map (primary view)", BtrxTelemetryRoute.shownOnLine(BtrxTelemetryWindow.MAP))
        assertEquals("Telemetry shown on: Stream (primary view)", BtrxTelemetryRoute.shownOnLine(BtrxTelemetryWindow.STREAM))
        // The steering card has no hidden state left.
        assertEquals(listOf("NONE", "CARD", "PICK"), BtrxSteeringTarget.TileOverlay.entries.map { it.name })
    }

    @Test fun bearingLayerAndSteeringStayHiddenUntilTheDeskPodIsReady() {
        // Ken, Nov 25 A5: "no reports from the paired Drone Pod" appeared with no Desk Pod on.
        assertFalse(BtrxTelemetryRoute.mapShows(true, BtrxTelemetryWindow.MAP, false))
        assertTrue(BtrxTelemetryRoute.mapShows(true, BtrxTelemetryWindow.MAP, true))
        assertFalse(BtrxTelemetryRoute.mapShows(true, BtrxTelemetryWindow.STREAM, true))
        assertFalse(BtrxTelemetryRoute.streamShows(BtrxTelemetryWindow.STREAM, false))
        assertTrue(BtrxTelemetryRoute.streamShows(BtrxTelemetryWindow.STREAM, true))
        for (w in BtrxTelemetryWindow.entries) {
            assertFalse(BtrxTelemetryRoute.mapShows(true, w, false))
            assertFalse(BtrxTelemetryRoute.streamShows(w, false))
        }
        val pane = src("src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt")
        assertTrue(pane.contains("btrxMapStatus?.active == true"))
        assertTrue(pane.contains("BtrxTelemetryRoute.mapShows("))
        // The map no longer treats a missing status chip (no session link) as "show the notice".
        assertFalse(pane.contains("btrxMapStatus?.active != false"))
    }

    @Test fun thereIsNoWayToTurnBtrxTelemetryOff() {
        val manager = src("src/main/java/org/ncssar/rid2caltopo/btrx/BtrxManager.kt")
        assertFalse(manager.contains("fun setOverlayShown"))
        assertFalse(manager.contains("val overlayShown"))
        assertFalse(manager.contains("fun setBearingOn"))
        // The old saved "hidden" choice is dropped, never read back.
        assertTrue(manager.contains("p.edit().remove(KEY_OVERLAY_SHOWN).apply()"))
        assertFalse(manager.contains("getBoolean(KEY_OVERLAY_SHOWN, true)\n"))
        val ui = listOf(
            "src/main/java/org/ncssar/rid2caltopo/ui/DeskPodScreen.kt",
            "src/main/java/org/ncssar/rid2caltopo/ui/BtrxBearingUi.kt",
            "src/main/java/org/ncssar/rid2caltopo/ui/BtrxSteeringOverlay.kt",
            "src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt",
            "src/main/java/org/ncssar/rid2caltopo/video/StreamsScreen.kt",
        ).joinToString("\n") { src(it) }
        for (gone in listOf("setOverlayShown", "setBearingOn", "OVERLAY_TOGGLE", "OVERLAY_HIDDEN", "OVERLAY_HIDE", "LAYER_SWITCH")) {
            assertFalse(gone, ui.contains(gone))
        }
        // The Desk Pod screen (double tap on the map) says where telemetry is, read only.
        val desk = src("src/main/java/org/ncssar/rid2caltopo/ui/DeskPodScreen.kt")
        assertTrue(desk.contains("Text(BtrxTelemetryRoute.shownOnLine(telemetryWindow)"))
        // A failed start offers only "Try again" (turns the layer on).
        assertTrue(src("src/main/java/org/ncssar/rid2caltopo/ui/BtrxBearingUi.kt").contains("BtrxManager.retryBearingLayer()"))
    }
}

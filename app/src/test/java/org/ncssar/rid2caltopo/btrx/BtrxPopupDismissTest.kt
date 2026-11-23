package org.ncssar.rid2caltopo.btrx

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.ui.BtrxPopupBack
import org.ncssar.rid2caltopo.video.BtrxPopupDismissOverlay

/** Ken, Nov 23 field test: Back offered Quit, map taps re-opened the panel, popups under the attribution. */
class BtrxPopupDismissTest {
    @Test fun mapTapWithAPopupOpenOnlyClosesIt() {
        var open = true
        var closes = 0
        val overlay = BtrxPopupDismissOverlay(popupOpen = { open }, dismiss = { closes++; open = false })
        assertTrue("consumed, so nothing below sees the tap", overlay.consumeTap())
        assertEquals(1, closes)
        assertFalse("with nothing open the tap goes on to the map", overlay.consumeTap())
        assertEquals(1, closes)
    }

    @Test fun backClosesAnOpenPopupBeforeAnythingElse() {
        var open = true
        val registration = BtrxPopupBack.register { val was = open; open = false; was }
        try {
            assertTrue(BtrxPopupBack.closeOpenPopup())
            assertFalse(open)
            assertFalse("nothing open: Back goes on to the Quit prompt", BtrxPopupBack.closeOpenPopup())
        } finally {
            registration.close()
        }
        assertFalse(BtrxPopupBack.closeOpenPopup())
    }

    @Test fun popupsUseOnlyTheMapAboveTheAttributionStrip() {
        assertEquals(1000f, BtrxPopupPlacement.usableHeight(1000f, 0f))
        assertEquals(960f, BtrxPopupPlacement.usableHeight(1000f, 40f))
        assertEquals(1000f, BtrxPopupPlacement.usableHeight(1000f, -5f))
        assertEquals(1f, BtrxPopupPlacement.usableHeight(30f, 40f))
        // A tall panel tapped near the bottom ends above the strip.
        val usable = BtrxPopupPlacement.usableHeight(800f, 48f)
        val plan = BtrxPopupPlacement.atPoint(1200f, usable, 300f, 400f, 500f, 700f, null, 8f)
        assertTrue(plan.y + 400f <= 800f - 48f - 8f + 0.01f)
        // The inspect card keeps its old spot when the strip is shorter than its offset.
        assertEquals(24f, BtrxPopupPlacement.bottomAboveReserved(72f, 48f))
        assertEquals(0f, BtrxPopupPlacement.bottomAboveReserved(72f, 100f))
    }

    private val mapPane = File("src/main/java/org/ncssar/rid2caltopo/video/MapPane.kt").readText()

    @Test fun mapWiresBackTapAndAttributionHandling() {
        assertTrue(mapPane.contains("BackHandler(enabled = btrxPanel != null || btrxInspect != null)"))
        assertTrue(mapPane.contains("BtrxPopupBack.register"))
        assertEquals(2, Regex("keepAboveInRoot = btrxAttributionTopInRoot").findAll(mapPane).count())
        assertTrue(mapPane.contains("btrxAttributionTopInRoot = it.positionInRoot().y"))
        // The dismiss overlay is added after every other overlay of the map update.
        val update = mapPane.substringAfter("val tapOverlay = MapEventsOverlay(")
        val dismissAt = update.indexOf("mapView.overlays.add(btrxPopupDismissOverlay)")
        assertTrue(dismissAt > 0)
        val after = update.substring(dismissAt + 1).substringBefore("val limitAglM")
        assertFalse("no overlay added above it", after.contains("mapView.overlays.add("))
        val activity = File("src/main/java/org/ncssar/rid2caltopo/app/R2CActivity.kt").readText()
        val back = activity.substringAfter("BackHandler(enabled = pendingAppExitSource == null").substringBefore("AppBackAction.RETURN_TO_MAIN")
        assertTrue(back.indexOf("BtrxPopupBack.closeOpenPopup()") in 0 until back.indexOf("requestAppExit("))
    }
}

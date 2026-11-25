package org.ncssar.rid2caltopo.btrx

/** The window that shows BTRX telemetry: the map (bearing layer) or the stream (steering overlay). */
enum class BtrxTelemetryWindow { MAP, STREAM }

/**
 * Where BTRX telemetry is drawn (Ken, Nov 23): shown while BTRX is enabled and the Desk Pod
 * link is READY, with no on/off, in the primary window only: the bearing layer when the map
 * is primary, the steering overlay when the stream is. Full-screen map or stream (with or without the other as a
 * picture-in-picture inset) is primary; in a split (side by side or top and bottom) the pane
 * with the larger on-screen area is. The compact stream PiP never shows telemetry, and an
 * inset map draws none either (the stream is primary then).
 *
 * Near 50/50 the choice holds ([HYSTERESIS]): the other pane has to reach 55 % of the two
 * panes' area to take over, so dragging the divider about the middle does not flicker. An exact
 * tie with nothing to hold ([TIE]) goes to the map. Same as the iPad's BtrxTelemetryRoute;
 * cases in test-fixtures/btrx/telemetry-route.tsv.
 */
object BtrxTelemetryRoute {
    /** Share past 50 % the other pane needs before telemetry moves to it. */
    const val HYSTERESIS = 0.05
    /** An exact tie with no current window: the map. */
    val TIE = BtrxTelemetryWindow.MAP

    /** Areas in any unit (0 = pane not shown); [previous] = the window telemetry is on now, if any. */
    fun primary(mapArea: Double, streamArea: Double, previous: BtrxTelemetryWindow?): BtrxTelemetryWindow {
        val m = mapArea.coerceAtLeast(0.0)
        val s = streamArea.coerceAtLeast(0.0)
        if (s <= 0.0) return BtrxTelemetryWindow.MAP
        if (m <= 0.0) return BtrxTelemetryWindow.STREAM
        val share = s / (m + s)
        return when {
            share >= 0.5 + HYSTERESIS - 1e-9 -> BtrxTelemetryWindow.STREAM
            share <= 0.5 - HYSTERESIS + 1e-9 -> BtrxTelemetryWindow.MAP
            previous != null -> previous
            share > 0.5 -> BtrxTelemetryWindow.STREAM
            share < 0.5 -> BtrxTelemetryWindow.MAP
            else -> TIE
        }
    }

    /** One-dimensional split: [streamFraction] of the width (side by side) or height (top and bottom) is the stream pane. */
    fun forStreamFraction(streamFraction: Double, previous: BtrxTelemetryWindow?): BtrxTelemetryWindow =
        primary(1.0 - streamFraction.coerceIn(0.0, 1.0), streamFraction.coerceIn(0.0, 1.0), previous)

    /**
     * The map draws the bearing layer, its chips and popups: the setting may already be on
     * (it auto-enables with BTRX), the map is primary, and the Desk Pod link is READY.
     * With no Desk Pod connected, nothing is drawn and no "no reports" status appears
     * (Ken, Nov 25 2026 field test on the A5).
     */
    fun mapShows(bearingOn: Boolean, window: BtrxTelemetryWindow, deskPodReady: Boolean): Boolean =
        bearingOn && deskPodReady && window == BtrxTelemetryWindow.MAP

    /** The stream tile may show the steering card: the stream is primary and the Desk Pod is READY. */
    fun streamShows(window: BtrxTelemetryWindow, deskPodReady: Boolean): Boolean =
        deskPodReady && window == BtrxTelemetryWindow.STREAM

    fun windowName(w: BtrxTelemetryWindow): String = when (w) {
        BtrxTelemetryWindow.MAP -> "Map"
        BtrxTelemetryWindow.STREAM -> "Stream"
    }

    /** Read-only line in the Desk Pod screen (double tap on the map); there is no toggle. */
    fun shownOnLine(w: BtrxTelemetryWindow): String = "Telemetry shown on: ${windowName(w)} (primary view)"

    const val HINT = "BTRX telemetry is shown while the Desk Pod is connected: the bearing layer on the map when the map is the primary view, the steering overlay on the stream when the stream is. In a split the larger pane is primary (the map on an even split); the picture-in-picture inset never shows it. With no Desk Pod connected, nothing is drawn."
}

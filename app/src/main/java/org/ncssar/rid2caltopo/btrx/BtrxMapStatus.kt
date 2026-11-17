package org.ncssar.rid2caltopo.btrx

import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkKind
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkPhase
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkStatus

/**
 * The BTRX status chip at the top of the map (Ken, Nov 16). It follows the Desk Pod link:
 * "BTRX: Active" (green) while the link is READY, or "BTRX: Active - No Drone Pod reports"
 * (orange) while READY with no frame from the paired Drone Pod for [DRONE_POD_REPORT_MS];
 * otherwise "BTRX: Inactive - " and why. Shown only once BTRX is set up (Bluetooth link on,
 * a Desk Pod remembered, or a USB link). Same strings, window and rules on the iPad.
 */
object BtrxMapStatus {
    const val ACTIVE = "BTRX: Active"
    const val ACTIVE_NO_REPORTS = "BTRX: Active - No Drone Pod reports"
    const val ACTIVE_PREFIX = "BTRX: Active - "
    const val INACTIVE_PREFIX = "BTRX: Inactive - "
    const val BT_DOWN = "BT Down"
    const val CONNECTING = "Connecting"
    const val BLUETOOTH_OFF = "Bluetooth off"
    /** The reason in [ACTIVE_NO_REPORTS]. */
    const val NO_DRONE_POD_REPORTS = "No Drone Pod reports"
    const val PAUSED_USB = "Paused (USB)"
    const val LINK_OFF = "Link off"
    const val NO_PERMISSION = "No permission"
    const val UNAVAILABLE = "Bluetooth unavailable"
    /** The last Bluetooth session ended with the Desk Pod silent (Nov 17): most likely its power. */
    const val DESK_POD_SILENT = BtrxDeskPodPower.SILENT

    /** No frame from the paired Drone Pod for this long while READY: the orange Active chip (iPad: 15 s too). */
    const val DRONE_POD_REPORT_MS = 15_000L

    /**
     * [active]: the Desk Pod link is READY (green or orange chip). [warning]: READY but the
     * paired Drone Pod is quiet (orange).
     */
    data class Status(val text: String, val active: Boolean, val warning: Boolean = false)

    /**
     * A frame that shows the paired Drone Pod is reporting: live (not the connect backlog),
     * from the paired pod, and not PAIR_REQ (a pod asking to pair is not reporting).
     */
    fun isDronePodReport(frameType: Int, backlog: Boolean, fromPairedPod: Boolean): Boolean =
        fromPairedPod && !backlog && frameType != BtrxProtocol.PAIR_REQ

    /** [lastReportMs] 0 = none since the link came up. */
    fun dronePodReporting(lastReportMs: Long, nowMs: Long): Boolean =
        lastReportMs != 0L && nowMs - lastReportMs in 0..DRONE_POD_REPORT_MS

    /** BTRX is set up on this tablet: the chip shows. */
    fun setUp(bluetoothLinkEnabled: Boolean, deskPodRemembered: Boolean, usbLink: Boolean, pausedForUsb: Boolean): Boolean =
        bluetoothLinkEnabled || deskPodRemembered || usbLink || pausedForUsb

    /**
     * [dronePodReporting]: a Drone Pod report ([isDronePodReport]) in the last
     * [DRONE_POD_REPORT_MS]. [bluetoothOff]: the tablet's Bluetooth is turned off.
     * [deskPodSilent]: the last Bluetooth session ended with the Desk Pod silent and none has
     * started since ([DESK_POD_SILENT] replaces "BT Down" / "Connecting"). [deskPodNotice]:
     * "Desk Pod restarted (power-on)" while fresh; orange "BTRX: Active - <notice>" while READY.
     */
    fun status(
        setUp: Boolean, link: LinkStatus, bluetoothOff: Boolean, pausedForUsb: Boolean, dronePodReporting: Boolean,
        deskPodSilent: Boolean = false, deskPodNotice: String? = null,
    ): Status? {
        if (!setUp) return null
        if (link.phase == LinkPhase.READY && !pausedForUsb) {
            return when {
                deskPodNotice != null -> Status(ACTIVE_PREFIX + deskPodNotice, active = true, warning = true)
                dronePodReporting -> Status(ACTIVE, active = true)
                else -> Status(ACTIVE_NO_REPORTS, active = true, warning = true)
            }
        }
        return Status(INACTIVE_PREFIX + reason(link, bluetoothOff, pausedForUsb, deskPodSilent), false)
    }

    private fun reason(link: LinkStatus, bluetoothOff: Boolean, pausedForUsb: Boolean, deskPodSilent: Boolean): String = when {
        pausedForUsb || link.phase == LinkPhase.PAUSED_FOR_USB -> PAUSED_USB
        link.kind == LinkKind.USB -> CONNECTING
        link.phase == LinkPhase.OFF -> LINK_OFF
        bluetoothOff -> BLUETOOTH_OFF
        link.phase == LinkPhase.NO_PERMISSION -> NO_PERMISSION
        link.phase == LinkPhase.UNAVAILABLE -> UNAVAILABLE
        link.phase == LinkPhase.CONFIGURING -> CONNECTING
        // RETRY_WAIT, SCANNING, BONDING, CONNECTING after the Desk Pod stopped answering.
        deskPodSilent && link.kind == LinkKind.BLUETOOTH -> DESK_POD_SILENT
        link.phase == LinkPhase.RETRY_WAIT -> BT_DOWN
        // SCANNING, BONDING, CONNECTING: the first connect, or reconnecting after a drop.
        link.failedAttempts > 0 -> BT_DOWN
        else -> CONNECTING
    }
}

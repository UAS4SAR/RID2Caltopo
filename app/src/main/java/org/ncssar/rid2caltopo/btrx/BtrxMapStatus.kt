package org.ncssar.rid2caltopo.btrx

import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkKind
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkPhase
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkStatus

/**
 * The BTRX status chip at the top of the map (Ken, Nov 16): "BTRX: Active" while the
 * Desk Pod link is up and talking (the map double-tap rule), else "BTRX: Inactive - "
 * and why. Shown only once BTRX is set up (Bluetooth link on, a Desk Pod remembered,
 * or a USB link). Same strings and rules on the iPad.
 */
object BtrxMapStatus {
    const val ACTIVE = "BTRX: Active"
    const val INACTIVE_PREFIX = "BTRX: Inactive - "
    const val BT_DOWN = "BT Down"
    const val CONNECTING = "Connecting"
    const val BLUETOOTH_OFF = "Bluetooth off"
    const val NO_DRONE_POD_REPORTS = "No Drone Pod reports"
    const val PAUSED_USB = "Paused (USB)"
    const val LINK_OFF = "Link off"
    const val NO_PERMISSION = "No permission"
    const val UNAVAILABLE = "Bluetooth unavailable"

    data class Status(val text: String, val active: Boolean)

    /** BTRX is set up on this tablet: the chip shows. */
    fun setUp(bluetoothLinkEnabled: Boolean, deskPodRemembered: Boolean, usbLink: Boolean, pausedForUsb: Boolean): Boolean =
        bluetoothLinkEnabled || deskPodRemembered || usbLink || pausedForUsb

    /**
     * [talking]: link READY and a Desk Pod frame within the talking window (10 s, as the
     * map double tap). [bluetoothOff]: the tablet's Bluetooth is turned off.
     */
    fun status(setUp: Boolean, link: LinkStatus, bluetoothOff: Boolean, pausedForUsb: Boolean, talking: Boolean): Status? {
        if (!setUp) return null
        if (link.phase == LinkPhase.READY && talking) return Status(ACTIVE, true)
        return Status(INACTIVE_PREFIX + reason(link, bluetoothOff, pausedForUsb), false)
    }

    private fun reason(link: LinkStatus, bluetoothOff: Boolean, pausedForUsb: Boolean): String = when {
        pausedForUsb || link.phase == LinkPhase.PAUSED_FOR_USB -> PAUSED_USB
        link.kind == LinkKind.USB -> if (link.phase == LinkPhase.READY) NO_DRONE_POD_REPORTS else CONNECTING
        link.phase == LinkPhase.OFF -> LINK_OFF
        bluetoothOff -> BLUETOOTH_OFF
        link.phase == LinkPhase.NO_PERMISSION -> NO_PERMISSION
        link.phase == LinkPhase.UNAVAILABLE -> UNAVAILABLE
        link.phase == LinkPhase.READY -> NO_DRONE_POD_REPORTS
        link.phase == LinkPhase.CONFIGURING -> CONNECTING
        link.phase == LinkPhase.RETRY_WAIT -> BT_DOWN
        // SCANNING, BONDING, CONNECTING: the first connect, or reconnecting after a drop.
        link.failedAttempts > 0 -> BT_DOWN
        else -> CONNECTING
    }
}

package org.ncssar.rid2caltopo.btrx

import android.bluetooth.le.ScanSettings
import java.util.concurrent.CopyOnWriteArrayList
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkPhase

/**
 * Shares the tablet's Bluetooth radio between the Remote ID scan
 * (org.opendroneid.android.bluetooth.BluetoothScanner) and the Desk Pod link ([BtrxBleLink]).
 *
 * [BtrxBleLink] reports its phase here. While it is CONNECTING, CONFIGURING or READY
 * ("busy"), the Remote ID scan skips its 120 s periodic restart, for at most
 * [MAX_RESTART_DEFERRAL_MS] (Android downgrades a scan that has run for 30 minutes,
 * which the periodic restart exists to prevent).
 *
 * Adapter resets: R2C never disables, enables or resets the Bluetooth adapter or stack.
 * Any future code that does MUST first check [adapterResetAllowed] and refuse while it
 * returns false: a reset drops the Desk Pod's GATT session (and a pending connect).
 */
object BtrxRadioCoordinator {
    /**
     * Remote ID scan mode while the Desk Pod link is READY. Off: the scan stays
     * SCAN_MODE_LOW_LATENCY (100% duty). SCAN_MODE_BALANCED listens about a quarter of
     * the time (Android: 1 s window every 4 s), so Remote ID position updates would
     * arrive in bursts with gaps of up to 3 s, and the Nov 16 field logs did not tie the
     * Desk Pod drops to the scan. Flip to true to try BALANCED while linked; the scan then
     * restarts once when the link becomes READY and once when it leaves READY.
     */
    const val RELAX_RID_SCAN_WHILE_LINKED = false

    /** Longest the periodic Remote ID scan restart may be skipped while the link is busy. */
    const val MAX_RESTART_DEFERRAL_MS = 25 * 60_000L

    fun interface Listener {
        /** The Desk Pod link entered (true) or left (false) READY. Called on the link's thread. */
        fun onDeskPodLinkReadyChanged(ready: Boolean)
    }

    @Volatile private var phase = LinkPhase.OFF
    @Volatile private var gattOpen = false
    private val listeners = CopyOnWriteArrayList<Listener>()

    @JvmStatic
    fun isBusy(phase: LinkPhase): Boolean =
        phase == LinkPhase.CONNECTING || phase == LinkPhase.CONFIGURING || phase == LinkPhase.READY

    /** [BtrxBleLink]: every phase it reports. */
    fun onLinkPhase(next: LinkPhase) {
        val wasReady = phase == LinkPhase.READY
        phase = next
        val ready = next == LinkPhase.READY
        if (wasReady != ready) listeners.forEach { runCatching { it.onDeskPodLinkReadyChanged(ready) } }
    }

    /** [BtrxBleLink]: a BluetoothGatt (session or pending connect) is open (true) or closed. */
    fun onGattOpen(open: Boolean) { gattOpen = open }

    @JvmStatic fun deskPodLinkBusy(): Boolean = isBusy(phase)
    @JvmStatic fun deskPodLinkReady(): Boolean = phase == LinkPhase.READY
    @JvmStatic fun deskPodGattOpen(): Boolean = gattOpen

    @JvmStatic fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    @JvmStatic fun removeListener(l: Listener) { listeners.remove(l) }

    /** Skip the periodic Remote ID scan restart? [scanRunningMs]: since the scan last started. */
    @JvmStatic
    fun deferPeriodicRestart(linkBusy: Boolean, scanRunningMs: Long): Boolean =
        linkBusy && scanRunningMs < MAX_RESTART_DEFERRAL_MS

    @JvmStatic
    fun deferPeriodicRestart(scanRunningMs: Long): Boolean = deferPeriodicRestart(deskPodLinkBusy(), scanRunningMs)

    /** Remote ID scan mode (ScanSettings.SCAN_MODE_*). */
    @JvmStatic
    @JvmOverloads
    fun ridScanMode(linkReady: Boolean, relax: Boolean = RELAX_RID_SCAN_WHILE_LINKED): Int =
        if (relax && linkReady) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY

    @JvmStatic
    fun ridScanMode(): Int = ridScanMode(deskPodLinkReady())

    @JvmStatic
    fun scanModeName(mode: Int): String = when (mode) {
        ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
        ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
        ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
        else -> "mode $mode"
    }

    /**
     * Guard for any future Bluetooth adapter or stack reset (disable/enable, or anything
     * else that drops connections): false while a Desk Pod GATT session or pending connect
     * exists. Nothing in R2C resets the adapter today; keep it that way while linked.
     */
    @JvmStatic
    fun adapterResetAllowed(): Boolean = adapterResetAllowed(gattOpen)

    @JvmStatic
    fun adapterResetAllowed(deskPodGattOpen: Boolean): Boolean = !deskPodGattOpen

    /** Tests only. */
    internal fun resetForTest() { phase = LinkPhase.OFF; gattOpen = false; listeners.clear() }
}

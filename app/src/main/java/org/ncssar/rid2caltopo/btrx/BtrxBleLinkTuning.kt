package org.ncssar.rid2caltopo.btrx

import java.util.Locale

/**
 * Link-layer settings for the Desk Pod GATT link, and the log text for the link-layer
 * callbacks (debug, so they reach the session log).
 *
 * Field test 16 Nov 2026: the link dropped (Desk Pod 0x28 "LL instant passed", A5 0x08)
 * 13-30 s after the switch to BALANCED, and once 25 s after a PHY change neither side's
 * app asked for (tx 2M / rx 1M). So the link stays on HIGH priority for its whole life
 * and asks for the 1M PHY on both directions right after connecting, which leaves fewer
 * link-layer procedures (each with an instant) to collide.
 *
 * iOS has no equivalent: CoreBluetooth exposes neither connection priority nor PHY.
 */
object BtrxBleLinkTuning {
    /** BluetoothGatt.CONNECTION_PRIORITY_HIGH. Requested at connect and never changed. */
    const val CONNECTION_PRIORITY = 1
    /** No priority change once the Desk Pod has answered ROLE_GET (was BALANCED until 16 Nov 2026). */
    val priorityAfterRole: Int? = null

    /** BluetoothDevice.PHY_LE_1M_MASK, for both tx and rx. */
    const val PREFERRED_PHY_MASK = 1
    /** BluetoothDevice.PHY_OPTION_NO_PREFERRED. */
    const val PHY_OPTIONS = 0

    fun phyName(phy: Int): String = when (phy) {
        1 -> "1M"
        2 -> "2M"
        3 -> "Coded"
        else -> phy.toString()
    }

    fun phyText(event: String, txPhy: Int, rxPhy: Int, status: Int): String =
        "ble: $event tx=${phyName(txPhy)} rx=${phyName(rxPhy)} status=$status" +
            if (txPhy != PREFERRED_PHY_MASK || rxPhy != PREFERRED_PHY_MASK) " (not the requested 1M)" else ""

    /** interval in 1.25 ms units, timeout in 10 ms units (as the stack reports them). */
    fun connectionUpdateText(interval: Int, latency: Int, timeout: Int, status: Int): String =
        "ble: connection parameters interval=$interval (${"%.2f".format(Locale.US, interval * 1.25)} ms) " +
            "latency=$latency timeout=$timeout (${timeout * 10} ms) status=$status"
}

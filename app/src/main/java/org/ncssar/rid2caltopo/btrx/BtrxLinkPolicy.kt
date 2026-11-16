package org.ncssar.rid2caltopo.btrx

/**
 * Which Desk Pod link may be open. Any USB client makes the Desk Pod firmware turn
 * Bluetooth off and drop the Bluetooth client (back within seconds after a clean USB
 * close; up to 15 min if the USB client vanishes), so R2C never holds both: USB
 * (debug, Android only) wins while a Desk Pod is attached and the operator turned
 * on the USB debug link, otherwise Bluetooth runs if the operator turned it on. With
 * USB debug off a plugged-in Desk Pod is only drawing power and changes nothing. [update] returns the steps in order: the old link is
 * always stopped before the new one starts.
 */
class BtrxLinkArbiter {
    enum class Link { NONE, BLUETOOTH, USB }
    enum class Action { STOP_BLUETOOTH, CLOSE_USB, START_BLUETOOTH, OPEN_USB }

    var active: Link = Link.NONE
        private set
    var bluetoothEnabled = false
        private set
    var usbAttached = false
        private set
    var usbDebugEnabled = false
        private set

    /** True when the operator wants Bluetooth but a USB Desk Pod holds the link. */
    val bluetoothPausedForUsb: Boolean get() = bluetoothEnabled && active == Link.USB

    @Synchronized
    fun update(
        bluetoothEnabled: Boolean = this.bluetoothEnabled,
        usbAttached: Boolean = this.usbAttached,
        usbDebugEnabled: Boolean = this.usbDebugEnabled,
    ): List<Action> {
        this.bluetoothEnabled = bluetoothEnabled
        this.usbAttached = usbAttached
        this.usbDebugEnabled = usbDebugEnabled
        val want = when {
            usbAttached && usbDebugEnabled -> Link.USB
            bluetoothEnabled -> Link.BLUETOOTH
            else -> Link.NONE
        }
        if (want == active) return emptyList()
        val steps = mutableListOf<Action>()
        when (active) {
            Link.BLUETOOTH -> steps += Action.STOP_BLUETOOTH
            Link.USB -> steps += Action.CLOSE_USB
            Link.NONE -> Unit
        }
        when (want) {
            Link.BLUETOOTH -> steps += Action.START_BLUETOOTH
            Link.USB -> steps += Action.OPEN_USB
            Link.NONE -> Unit
        }
        active = want
        return steps
    }
}

/**
 * Bluetooth reconnect delays. The firmware never reconnects by itself, so the app
 * retries: 2, 4, 8, 16, then every 30 s, back to 2 s once a session reaches READY.
 */
class BtrxReconnectBackoff(
    private val firstMs: Long = 2_000,
    private val maxMs: Long = 30_000,
) {
    var failures = 0
        private set
    /** Why the latest failed attempt failed (shown on the Desk Pod screen); null after [reset]. */
    var lastFailure: String? = null
        private set

    fun nextDelayMs(failure: String? = null): Long {
        if (failure != null) lastFailure = failure
        val d = (firstMs shl minOf(failures, 10)).coerceAtMost(maxMs)
        failures++
        return d
    }

    fun reset() { failures = 0; lastFailure = null }
}

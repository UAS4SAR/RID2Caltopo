package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxLinkArbiter.Action
import org.ncssar.rid2caltopo.btrx.BtrxLinkArbiter.Link

class BtrxLinkPolicyTest {
    private fun usbDebugArbiter() = BtrxLinkArbiter().apply { assertEquals(emptyList<Action>(), update(usbDebugEnabled = true)) }

    @Test
    fun withUsbDebugOffAPluggedInDeskPodIsPowerOnly() {
        val a = BtrxLinkArbiter()
        assertFalse(a.usbDebugEnabled) // off by default
        assertEquals(listOf(Action.START_BLUETOOTH), a.update(bluetoothEnabled = true))
        assertEquals(emptyList<Action>(), a.update(usbAttached = true))
        assertEquals(Link.BLUETOOTH, a.active)
        assertFalse(a.bluetoothPausedForUsb)
        // Turning the debug switch on with the Desk Pod plugged in hands over to USB.
        assertEquals(listOf(Action.STOP_BLUETOOTH, Action.OPEN_USB), a.update(usbDebugEnabled = true))
        // And off again gives the link back to Bluetooth.
        assertEquals(listOf(Action.CLOSE_USB, Action.START_BLUETOOTH), a.update(usbDebugEnabled = false))
    }

    @Test
    fun usbAttachStopsBluetoothBeforeOpeningUsb() {
        val a = usbDebugArbiter()
        assertEquals(listOf(Action.START_BLUETOOTH), a.update(bluetoothEnabled = true))
        assertEquals(listOf(Action.STOP_BLUETOOTH, Action.OPEN_USB), a.update(usbAttached = true))
        assertEquals(Link.USB, a.active)
        assertTrue(a.bluetoothPausedForUsb)
        assertEquals(listOf(Action.CLOSE_USB, Action.START_BLUETOOTH), a.update(usbAttached = false))
        assertFalse(a.bluetoothPausedForUsb)
    }

    @Test
    fun neverBothLinksOpenInAnySequence() {
        val a = usbDebugArbiter()
        var ble = false
        var usb = false
        val inputs = listOf(true to false, true to true, false to true, false to false, true to true, true to false, false to false, true to true)
        for ((bt, u) in inputs) {
            for (step in a.update(bluetoothEnabled = bt, usbAttached = u)) {
                when (step) {
                    Action.STOP_BLUETOOTH -> ble = false
                    Action.CLOSE_USB -> usb = false
                    Action.START_BLUETOOTH -> ble = true
                    Action.OPEN_USB -> usb = true
                }
                assertFalse("both open after $step", ble && usb)
            }
            assertEquals(if (u) Link.USB else if (bt) Link.BLUETOOTH else Link.NONE, a.active)
        }
    }

    @Test
    fun turningBluetoothOffWhileUsbIsAttachedChangesNothing() {
        val a = usbDebugArbiter()
        a.update(bluetoothEnabled = true, usbAttached = true)
        assertEquals(emptyList<Action>(), a.update(bluetoothEnabled = false))
        assertFalse(a.bluetoothPausedForUsb)
        assertEquals(listOf(Action.CLOSE_USB), a.update(usbAttached = false))
        assertEquals(Link.NONE, a.active)
    }

    @Test
    fun backoffDoublesToThirtySecondsAndResets() {
        val b = BtrxReconnectBackoff()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(6) { b.nextDelayMs() })
        b.reset()
        assertEquals(2_000L, b.nextDelayMs())
    }

    @Test
    fun backoffRemembersTheLatestFailureUntilReset() {
        val b = BtrxReconnectBackoff()
        b.nextDelayMs("connection failed (status 8)")
        b.nextDelayMs("connection failed (status 147)")
        b.nextDelayMs()
        assertEquals(3, b.failures)
        assertEquals("connection failed (status 147)", b.lastFailure)
        b.reset()
        assertEquals(0, b.failures)
        assertEquals(null, b.lastFailure)
    }
}

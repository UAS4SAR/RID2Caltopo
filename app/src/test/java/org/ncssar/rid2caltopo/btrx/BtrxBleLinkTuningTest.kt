package org.ncssar.rid2caltopo.btrx

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BtrxBleLinkTuningTest {
    @Test
    fun priorityIsHighAndNeverSwitchedToBalanced() {
        assertEquals(BluetoothGatt.CONNECTION_PRIORITY_HIGH, BtrxBleLinkTuning.CONNECTION_PRIORITY)
        assertNull(BtrxBleLinkTuning.priorityAfterRole)
    }

    @Test
    fun phyIsPinnedTo1mBothWaysWithNoCodedPreference() {
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK, BtrxBleLinkTuning.PREFERRED_PHY_MASK)
        assertEquals(BluetoothDevice.PHY_OPTION_NO_PREFERRED, BtrxBleLinkTuning.PHY_OPTIONS)
    }

    @Test
    fun phyTextNamesBothDirectionsAndFlagsAnythingBut1m() {
        assertEquals("ble: phy update tx=1M rx=1M status=0", BtrxBleLinkTuning.phyText("phy update", 1, 1, 0))
        val asym = BtrxBleLinkTuning.phyText("phy update", 2, 1, 0)
        assertTrue(asym, asym.startsWith("ble: phy update tx=2M rx=1M status=0"))
        assertTrue(asym.endsWith("(not the requested 1M)"))
        assertEquals("Coded", BtrxBleLinkTuning.phyName(3))
        assertEquals("7", BtrxBleLinkTuning.phyName(7))
        assertFalse(BtrxBleLinkTuning.phyText("phy read", 1, 1, 0).contains("not the requested"))
    }

    @Test
    fun connectionUpdateTextConvertsUnits() {
        assertEquals(
            "ble: connection parameters interval=12 (15.00 ms) latency=0 timeout=500 (5000 ms) status=0",
            BtrxBleLinkTuning.connectionUpdateText(12, 0, 500, 0),
        )
        assertEquals(
            "ble: connection parameters interval=24 (30.00 ms) latency=0 timeout=500 (5000 ms) status=0",
            BtrxBleLinkTuning.connectionUpdateText(24, 0, 500, 0),
        )
    }
}

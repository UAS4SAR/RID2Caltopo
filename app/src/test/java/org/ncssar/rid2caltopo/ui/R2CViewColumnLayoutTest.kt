package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class R2CViewColumnLayoutTest {
    @Test
    fun ridmapHeaderAndDroneItemUseTheSameColumnWidths() {
        assertEquals(
            R2CViewColumnLayout.headerColumnWidthsDp,
            R2CViewColumnLayout.droneItemColumnWidthsDp
        )
    }

    @Test
    fun waypointsReceivedHeaderSpansTheTransportColumnsAndTotal() {
        assertEquals(
            R2CViewColumnLayout.transportColumnWidthsDp.sum() +
                R2CViewColumnLayout.r2cWaypointColumnWidthDp +
                R2CViewColumnLayout.seiColumnWidthDp +
                R2CViewColumnLayout.totalColumnWidthDp,
            R2CViewColumnLayout.waypointsReceivedHeaderWidthDp
        )
    }

    @Test
    fun ridStatusShowsOnlyAvailableDroneToBridgeRssi() {
        assertEquals("D→Bridge -74 dBm", droneToBridgeRssiText(-74))
        assertEquals(null, droneToBridgeRssiText(0))
        assertEquals(null, droneToBridgeRssiText(-128))
    }
}

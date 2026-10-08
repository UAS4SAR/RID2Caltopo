package org.ncssar.rid2caltopo.video

import org.junit.Assert.*
import org.junit.Test

class DownloadAreaSummaryTest {
    @Test fun equatorialRectangleHasExpectedDimensionsAndCenter() {
        val area = DownloadAreaSummary(0.5, -0.5, -0.5, 0.5)
        assertEquals(69.0934, area.widthMiles, 0.001)
        assertEquals(69.0934, area.heightMiles, 0.001)
        assertEquals(4773.84, area.squareMiles, 0.1)
        assertEquals(0.0, area.latitude, 0.0)
        assertEquals(0.0, area.longitude, 0.0)
    }
    @Test fun highLatitudeWidthShrinksAndZeroAreaIsValid() {
        val area = DownloadAreaSummary(60.5, 59.5, -121.0, -120.0)
        assertEquals(34.5467, area.widthMiles, 0.001)
        assertEquals(-120.5, area.longitude, 0.0)
        assertEquals(0.0, DownloadAreaSummary(40.0, 40.0, -120.0, -120.0).squareMiles, 0.0)
    }
}

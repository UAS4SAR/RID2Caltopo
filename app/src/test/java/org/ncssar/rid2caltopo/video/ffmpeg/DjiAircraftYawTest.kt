package org.ncssar.rid2caltopo.video.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Values from the 2026-11-12 20:18 PT indoor M4TD yaw test (shared C decoder tests use the same raw words). */
class DjiAircraftYawTest {
    @Test
    fun startHeadingFromYawTestIsCameraYawMinus90() {
        assertEquals(308.7905055, DjiCameraOrientation.aircraftYawTrueNorthDeg(38.7905055)!!, 1e-7)
    }

    @Test
    fun crossingPlusMinus180StaysContinuous() {
        // Raw 1782200987 then -1794660618 (deg x 1e7); the decoder normalizes the second to 180.53.
        val before = DjiCameraOrientation.aircraftYawTrueNorthDeg(178.2200987)!!
        val after = DjiCameraOrientation.aircraftYawTrueNorthDeg(180.5339382)!!
        assertEquals(88.2200987, before, 1e-7)
        assertEquals(90.5339382, after, 1e-7)
        assertEquals(90.5339382, DjiCameraOrientation.aircraftYawTrueNorthDeg(-179.4660618)!!, 1e-7)
        assertEquals(2.3138395, after - before, 1e-7)
    }

    @Test
    fun wrapsAtTrueNorthAndMatchesTheExistingHelper() {
        assertEquals(0.0, DjiCameraOrientation.aircraftYawTrueNorthDeg(90.0)!!, 0.0)
        assertEquals(359.9999, DjiCameraOrientation.aircraftYawTrueNorthDeg(89.9999)!!, 1e-7)
        for (raw in listOf(0.0, 38.79, 127.6, 213.4, 309.8, 397.5, -146.62)) {
            assertEquals(DjiCameraOrientation.controllerAzimuthDeg(raw), DjiCameraOrientation.aircraftYawTrueNorthDeg(raw))
        }
        assertNull(DjiCameraOrientation.aircraftYawTrueNorthDeg(null))
        assertNull(DjiCameraOrientation.aircraftYawTrueNorthDeg(Double.NaN))
    }
}

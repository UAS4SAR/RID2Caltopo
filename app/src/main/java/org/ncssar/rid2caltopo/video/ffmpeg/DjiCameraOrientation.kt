package org.ncssar.rid2caltopo.video.ffmpeg

/** Converts DJI's private SEI camera attitude fields into CalTopo's conventions. */
object DjiCameraOrientation {
    private const val RAW_HORIZONTAL_REFERENCE_DEG = -14.5625
    private const val RAW_DOWN_REFERENCE_DEG = -90.0

    @JvmStatic
    fun controllerAzimuthDeg(
        cameraAzimuthDeg: Double?,
        magneticDeclinationDeg: Double? = 0.0,
    ): Double? {
        val finite = cameraAzimuthDeg?.takeIf { it.isFinite() } ?: return null
        val declination = magneticDeclinationDeg?.takeIf { it.isFinite() } ?: 0.0
        // Tag-4 offset 3 increases clockwise, but its north reference is magnetic.
        // CalTopo bearings are true north, so preserve the direction and add declination.
        return (((finite - 90.0 + declination) % 360.0) + 360.0) % 360.0
    }

    /**
     * Matrice 4TD field calibration from the controlled flight observed on 2026-08-19:
     * raw -90 = straight down (-90), and raw -14.5625 = the controller's horizontal 0.
     */
    @JvmStatic
    fun calibratedTiltDeg(rawTiltDeg: Double?): Double? {
        val raw = rawTiltDeg?.takeIf { it.isFinite() } ?: return null
        val scale = 90.0 / (RAW_HORIZONTAL_REFERENCE_DEG - RAW_DOWN_REFERENCE_DEG)
        return ((raw - RAW_HORIZONTAL_REFERENCE_DEG) * scale).coerceIn(-90.0, 90.0)
    }

}

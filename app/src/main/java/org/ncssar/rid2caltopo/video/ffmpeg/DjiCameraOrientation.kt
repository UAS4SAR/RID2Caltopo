package org.ncssar.rid2caltopo.video.ffmpeg

/** Converts DJI's private SEI camera attitude fields into CalTopo's conventions. */
object DjiCameraOrientation {
    private const val RAW_HORIZONTAL_REFERENCE_DEG = -14.5625
    private const val RAW_DOWN_REFERENCE_DEG = -90.0
    @JvmStatic
    fun controllerAzimuthDeg(cameraAzimuthDeg: Double?): Double? {
        val finite = cameraAzimuthDeg?.takeIf { it.isFinite() } ?: return null
        // Tag-4 offset 3 increases clockwise and already references true north
        // (2026-10-06 circular flights: adding declination put bearings ~12.9 deg off).
        // CalTopo bearings are true north too, so no magnetic declination is applied.
        return normalize(finite - 90.0)
    }

    /**
     * True-north bearing of the aircraft nose. On the Matrice 4TD the camera's yaw only
     * changes by yawing the aircraft (Ken, 2026-11-12), so this is the same value as
     * [controllerAzimuthDeg]: tag-4 bytes 3-6 (`FfmpegTelemetry.cameraYawDeg`) minus 90,
     * normalized to [0, 360). Read-only alias for BTRX heading; existing callers keep
     * using [controllerAzimuthDeg].
     */
    @JvmStatic
    fun aircraftYawTrueNorthDeg(cameraAzimuthDeg: Double?): Double? = controllerAzimuthDeg(cameraAzimuthDeg)

    private fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

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

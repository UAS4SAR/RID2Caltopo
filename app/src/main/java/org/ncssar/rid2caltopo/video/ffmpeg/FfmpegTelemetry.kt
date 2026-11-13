package org.ncssar.rid2caltopo.video.ffmpeg

data class FfmpegTelemetry(
    val sourceTag: String? = null,
    val confidence: Double? = null,
    val remoteId: String? = null,
    val sourceTimestampUs: Long? = null,
    val renderLatencyMs: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val gimbalPitchDeg: Double? = null,
    /**
     * Raw DJI camera yaw (tag-4 bytes 3-6, signed degrees x 1e7, normalized 0..360). On the
     * Matrice 4TD it equals the aircraft nose yaw; use DjiCameraOrientation to get a
     * true-north bearing (raw - 90).
     */
    val cameraYawDeg: Double? = null,
    val headingDeg: Double? = null,
    val horizontalFovDeg: Double? = null,
    val verticalFovDeg: Double? = null,
    /** Diagnostic-only tag-4 binary angles, captured from the same DJI SEI frame. */
    val djiAttitudeAnglesDeg: List<Double> = emptyList(),
    val djiNorthMm: Int? = null,
    val djiEastMm: Int? = null,
    val djiDownMm: Int? = null,
)

package org.ncssar.rid2caltopo.video.ffmpeg

import android.hardware.GeomagneticField
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

data class StreamCameraTelemetrySample(
    /** Clockwise camera azimuth relative to true north. */
    val azimuthDeg: Double?,
    /** Course from recent SEI position motion; not camera azimuth. */
    val courseDeg: Double?,
    val tiltDeg: Double,
    val horizontalFovDeg: Double,
    val verticalFovDeg: Double,
    /** Aircraft coordinate reconstructed from the reference plus full-width N/E displacement. */
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    /** MSL altitude is supplied by the paired RID state. */
    val altitudeMeters: Double?,
    val relativeUpMeters: Double?,
    val referenceLatitudeDeg: Double?,
    val referenceLongitudeDeg: Double?,
    val referenceAltitudeMeters: Double?,
    val northMeters: Double?,
    val eastMeters: Double?,
    val sourceTimestampUs: Long?,
    val receivedAtMs: Long,
    /** Raw tag-4 azimuth encoder retained for diagnostics and calibration evidence. */
    val rawCameraAzimuthDeg: Double,
    val rawTiltDeg: Double,
    val attitudeAnglesDeg: List<Double>,
    /** Clockwise true-north camera bearing reserved for the Map Pane FOV wedge. */
    val fovAzimuthDeg: Double? = null,
    /** Magnetic-to-true correction applied to the raw DJI camera azimuth. */
    val magneticDeclinationDeg: Double = 0.0,
)

internal fun shouldBlockRidClueFallback(
    seiPositionAuthorityEstablished: Boolean,
    freshRawSeiPositionAvailable: Boolean,
    validatedSeiPositionAvailable: Boolean,
): Boolean = !validatedSeiPositionAvailable &&
    (seiPositionAuthorityEstablished || freshRawSeiPositionAvailable)

object StreamCameraTelemetryRegistry {
    const val DEFAULT_MAX_AGE_MS = 3_000L
    private const val MAX_FRAME_TELEMETRY_DELTA_US = 250_000L
    private const val HISTORY_LIMIT = 120
    private const val EARTH_RADIUS_METERS = 6_378_137.0
    private const val MAX_RID_ANCHOR_RESIDUAL_METERS = 30.0
    private const val MAX_RID_VERTICAL_RESIDUAL_METERS = 20.0
    private const val COURSE_BASELINE_METERS = 3.0
    private val lock = Any()
    private val samples = mutableMapOf<String, StreamCameraTelemetrySample>()
    private val sampleHistory = mutableMapOf<String, MutableList<StreamCameraTelemetrySample>>()
    private val courseStates = mutableMapOf<String, CourseState>()
    private val positionValidated = mutableSetOf<String>()
    private val relativeUpValidated = mutableSetOf<String>()

    private data class CourseState(
        var anchorNorthMm: Int,
        var anchorEastMm: Int,
        var lastSourceTimestampUs: Long?,
        var courseDeg: Double? = null,
    )

    private fun updateCourse(state: CourseState, northMm: Int, eastMm: Int) {
        val deltaNorthMm = northMm.toLong() - state.anchorNorthMm
        val deltaEastMm = eastMm.toLong() - state.anchorEastMm
        if (hypot(deltaNorthMm.toDouble(), deltaEastMm.toDouble()) >= COURSE_BASELINE_METERS * 1_000.0) {
            state.courseDeg = ((Math.toDegrees(atan2(deltaEastMm.toDouble(), deltaNorthMm.toDouble())) % 360.0) + 360.0) % 360.0
            state.anchorNorthMm = northMm
            state.anchorEastMm = eastMm
        }
    }

    fun update(designator: String, telemetry: FfmpegTelemetry, nowMs: Long = System.currentTimeMillis()) {
        if (telemetry.sourceTag != "dji-sei-245") return
        val rawAzimuth = telemetry.cameraYawDeg?.takeIf { it.isFinite() } ?: return
        val rawTilt = telemetry.gimbalPitchDeg?.takeIf { it.isFinite() } ?: return
        val tilt = DjiCameraOrientation.calibratedTiltDeg(rawTilt) ?: return
        val width = telemetry.horizontalFovDeg?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val height = telemetry.verticalFovDeg?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val referenceLatitude = telemetry.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
        val referenceLongitude = telemetry.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
        val referenceAltitude = telemetry.altitudeMeters?.takeIf { it.isFinite() && it in -1000.0..30000.0 }
        val declination = if (referenceLatitude != null && referenceLongitude != null) {
            GeomagneticField(
                referenceLatitude.toFloat(),
                referenceLongitude.toFloat(),
                (referenceAltitude ?: 0.0).toFloat(),
                nowMs,
            ).declination.toDouble()
        } else {
            0.0
        }
        val controllerAzimuth = DjiCameraOrientation.controllerAzimuthDeg(rawAzimuth, declination) ?: return
        val northMm = telemetry.djiNorthMm
        val eastMm = telemetry.djiEastMm
        val downMm = telemetry.djiDownMm
        val key = designator.trim().uppercase()
        if (key.isEmpty()) return
        synchronized(lock) {
            val courseState = if (northMm != null && eastMm != null) {
                val prior = courseStates[key]
                val sourceRestarted = prior?.lastSourceTimestampUs?.let { previous ->
                    telemetry.sourceTimestampUs?.let { current -> current + 1_000_000L < previous } ?: false
                } ?: false
                if (prior == null || sourceRestarted) {
                    if (sourceRestarted) {
                        positionValidated.remove(key)
                        relativeUpValidated.remove(key)
                        sampleHistory.remove(key)
                    }
                    CourseState(northMm, eastMm, telemetry.sourceTimestampUs).also {
                        courseStates[key] = it
                    }
                } else {
                    updateCourse(prior, northMm, eastMm)
                    prior.lastSourceTimestampUs = telemetry.sourceTimestampUs
                    prior
                }
            } else {
                null
            }
            val northMeters = northMm?.div(1_000.0)
            val eastMeters = eastMm?.div(1_000.0)
            val aircraftLatitude = if (referenceLatitude != null && northMeters != null) {
                referenceLatitude + Math.toDegrees(northMeters / EARTH_RADIUS_METERS)
            } else null
            val aircraftLongitude = if (referenceLatitude != null && referenceLongitude != null && eastMeters != null) {
                referenceLongitude + Math.toDegrees(eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(referenceLatitude))))
            } else null
            val sample = StreamCameraTelemetrySample(
                azimuthDeg = controllerAzimuth,
                courseDeg = courseState?.courseDeg,
                tiltDeg = tilt,
                horizontalFovDeg = width,
                verticalFovDeg = height,
                latitudeDeg = aircraftLatitude,
                longitudeDeg = aircraftLongitude,
                altitudeMeters = null,
                relativeUpMeters = if (downMm != null && referenceAltitude != null) {
                    -downMm.toDouble() / 1_000.0 - referenceAltitude
                } else null,
                referenceLatitudeDeg = referenceLatitude,
                referenceLongitudeDeg = referenceLongitude,
                referenceAltitudeMeters = referenceAltitude,
                northMeters = northMeters,
                eastMeters = eastMeters,
                sourceTimestampUs = telemetry.sourceTimestampUs,
                receivedAtMs = nowMs,
                rawCameraAzimuthDeg = rawAzimuth,
                magneticDeclinationDeg = declination,
                rawTiltDeg = rawTilt,
                attitudeAnglesDeg = telemetry.djiAttitudeAnglesDeg.takeIf { it.size == 9 }
                    ?: List(9) { Double.NaN },
                fovAzimuthDeg = controllerAzimuth,
            )
            samples[key] = sample
            sample.sourceTimestampUs?.let { timestampUs ->
                val history = sampleHistory.getOrPut(key) { mutableListOf() }
                history.removeAll { it.sourceTimestampUs == timestampUs }
                history += sample
                while (history.size > HISTORY_LIMIT) history.removeAt(0)
            }
        }
    }

    fun fresh(
        designator: String,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): StreamCameraTelemetrySample? = synchronized(lock) {
        samples[designator.trim().uppercase()]?.takeIf {
            nowMs >= it.receivedAtMs && nowMs - it.receivedAtMs <= maxAgeMs
        }
    }

    /** Selects telemetry carried by the rendered frame instead of a later registry update. */
    fun freshForFrame(
        designator: String,
        frameTimestampUs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): StreamCameraTelemetrySample? = synchronized(lock) {
        val key = designator.trim().uppercase()
        if (frameTimestampUs == null || frameTimestampUs <= 0L) {
            return@synchronized samples[key]?.takeIf {
                nowMs >= it.receivedAtMs && nowMs - it.receivedAtMs <= maxAgeMs
            }
        }
        sampleHistory[key]
            ?.asSequence()
            ?.filter { nowMs >= it.receivedAtMs && nowMs - it.receivedAtMs <= maxAgeMs }
            ?.minByOrNull { kotlin.math.abs(checkNotNull(it.sourceTimestampUs) - frameTimestampUs) }
            ?.takeIf {
                kotlin.math.abs(checkNotNull(it.sourceTimestampUs) - frameTimestampUs) <=
                    MAX_FRAME_TELEMETRY_DELTA_US
            }
    }

    fun lastReceivedAtMs(designator: String): Long = synchronized(lock) {
        samples[designator.trim().uppercase()]?.receivedAtMs ?: 0L
    }

    /** Once true, operational consumers must not silently downgrade this stream to RID. */
    fun isPositionAuthorityEstablished(designator: String): Boolean = synchronized(lock) {
        positionValidated.contains(designator.trim().uppercase())
    }

    /**
     * Returns fresh DJI telemetry, allowing its independently reconstructed position to
     * continue after RID reception stops only after the two sources have agreed once during
     * the current stream session. Camera orientation remains available before validation.
     */
    @JvmStatic
    fun freshPositionAfterRidValidation(
        designator: String,
        anchorLatitudeDeg: Double,
        anchorLongitudeDeg: Double,
        anchorAltitudeMeters: Double? = null,
        takeoffReportedAltitudeMeters: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): StreamCameraTelemetrySample? {
        val key = designator.trim().uppercase()
        val sample = fresh(key, nowMs, maxAgeMs) ?: return null
        if (sample.latitudeDeg == null || sample.longitudeDeg == null) return sample

        synchronized(lock) {
            if (!positionValidated.contains(key) &&
                anchorLatitudeDeg.isFinite() && anchorLatitudeDeg in -90.0..90.0 &&
                anchorLongitudeDeg.isFinite() && anchorLongitudeDeg in -180.0..180.0 &&
                !(anchorLatitudeDeg == 0.0 && anchorLongitudeDeg == 0.0)
            ) {
                val latitudeRadians = Math.toRadians(sample.latitudeDeg)
                val residualNorth = Math.toRadians(anchorLatitudeDeg - sample.latitudeDeg) * EARTH_RADIUS_METERS
                val residualEast = Math.toRadians(anchorLongitudeDeg - sample.longitudeDeg) *
                    EARTH_RADIUS_METERS * cos(latitudeRadians)
                if (hypot(residualNorth, residualEast) <= MAX_RID_ANCHOR_RESIDUAL_METERS) {
                    positionValidated.add(key)
                }
            }
            val relativeUp = sample.relativeUpMeters
            if (!relativeUpValidated.contains(key) && relativeUp?.isFinite() == true &&
                anchorAltitudeMeters?.isFinite() == true &&
                takeoffReportedAltitudeMeters?.isFinite() == true &&
                kotlin.math.abs(
                    relativeUp - (anchorAltitudeMeters - takeoffReportedAltitudeMeters)
                ) <= MAX_RID_VERTICAL_RESIDUAL_METERS
            ) {
                relativeUpValidated.add(key)
            }
            return sample.copy(
                latitudeDeg = sample.latitudeDeg.takeIf { positionValidated.contains(key) },
                longitudeDeg = sample.longitudeDeg.takeIf { positionValidated.contains(key) },
                northMeters = sample.northMeters.takeIf { positionValidated.contains(key) },
                eastMeters = sample.eastMeters.takeIf { positionValidated.contains(key) },
                relativeUpMeters = relativeUp.takeIf { relativeUpValidated.contains(key) },
            )
        }
    }

    fun clear(designator: String) {
        synchronized(lock) {
            val key = designator.trim().uppercase()
            samples.remove(key)
            sampleHistory.remove(key)
            courseStates.remove(key)
            positionValidated.remove(key)
            relativeUpValidated.remove(key)
        }
    }
}

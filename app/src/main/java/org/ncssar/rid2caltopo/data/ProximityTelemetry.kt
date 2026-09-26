package org.ncssar.rid2caltopo.data

/** Accuracy belongs to this position sample, never to a previously selected telemetry source. */
data class ProximityTelemetry(
    val horizontalAccuracyMeters: Double = UNKNOWN_HORIZONTAL_METERS,
    val absoluteAltitudeMeters: Double? = null,
    val altitudeReference: Reference = Reference.UNKNOWN,
    val verticalAccuracyMeters: Double? = null
) {
    enum class Reference { UNKNOWN, GEODETIC, PRESSURE }

    fun hasUsableAltitude(): Boolean = altitudeReference != Reference.UNKNOWN &&
        absoluteAltitudeMeters?.let { it.isFinite() && it > -999.0 } == true &&
        verticalAccuracyMeters?.let { it.isFinite() && it > 0.0 } == true

    companion object {
        // Provisional policy allowance, not a measured DJI SEI accuracy guarantee.
        const val UNKNOWN_HORIZONTAL_METERS = 15.24 // 50 feet per aircraft
        const val MAX_POSITION_AGE_MS = 5_000L
        const val MAX_ALTITUDE_AGE_SECONDS = 5.0

        @JvmStatic fun horizontalAccuracyMeters(code: Int): Double = when (code) {
            1 -> 18520.0; 2 -> 7408.0; 3 -> 3704.0; 4 -> 1852.0
            5 -> 926.0; 6 -> 555.6; 7 -> 185.2; 8 -> 92.6
            9 -> 30.0; 10 -> 10.0; 11 -> 3.0; 12 -> 1.0
            else -> UNKNOWN_HORIZONTAL_METERS
        }
        @JvmStatic fun verticalAccuracyMeters(code: Int): Double? = when (code) {
            1 -> 150.0; 2 -> 45.0; 3 -> 25.0; 4 -> 10.0; 5 -> 3.0; 6 -> 1.0
            else -> null
        }
        @JvmStatic fun fromRid(horizontalCode: Int, geodetic: Double, pressure: Double,
                               verticalCode: Int, barometerCode: Int): ProximityTelemetry {
            val geoError = verticalAccuracyMeters(verticalCode)
            val baroError = verticalAccuracyMeters(barometerCode)
            val horizontal = horizontalAccuracyMeters(horizontalCode)
            return when {
                geodetic.isFinite() && geodetic > -999.0 && geoError != null ->
                    ProximityTelemetry(horizontal, geodetic, Reference.GEODETIC, geoError)
                pressure.isFinite() && pressure > -999.0 && baroError != null ->
                    ProximityTelemetry(horizontal, pressure, Reference.PRESSURE, baroError)
                else -> ProximityTelemetry(horizontal)
            }
        }
    }
}

/** Atomic position/quality snapshot used by the proximity worker. */
data class ProximityPosition(val latitude: Double, val longitude: Double,
    val receivedAtMillis: Long, val telemetry: ProximityTelemetry)

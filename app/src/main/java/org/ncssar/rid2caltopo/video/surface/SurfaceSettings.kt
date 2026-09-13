package org.ncssar.rid2caltopo.video.surface

/** Caller confines this gate to the UI thread. Busy requests are dropped, never queued. */
internal class SurfaceWorkGate {
    private var busy = false
    private val lastStarted = HashMap<String, Long>()
    fun begin(aircraft: String, enabled: Boolean, nowMillis: Long): Boolean {
        if (!enabled || busy) return false
        val last = lastStarted[aircraft]
        if (last != null && nowMillis - last < 1000L) return false
        busy = true
        lastStarted[aircraft] = nowMillis
        return true
    }
    fun finish() { busy = false }
    fun forget(aircraft: String) { lastStarted.remove(aircraft) }
}

/** Ground-typed zero is a valid launch observation; in-flight height still must be ATO. */
internal fun canObserveAolLaunch(grounded: Boolean, height: Double?, heightFresh: Boolean, positionFresh: Boolean): Boolean =
    grounded && heightFresh && positionFresh && height != null && height.isFinite() && kotlin.math.abs(height)<=1.0

/** No unit suffix is attached to a status word. */
enum class MeasurementStatus { Available, Unknown, Pending, Stale }
fun measurementLabel(value: Double?, status: MeasurementStatus = MeasurementStatus.Available, suffix: String = "", maxAbs: Double = Double.POSITIVE_INFINITY, showPendingValue: Boolean = false): String = when {
    status == MeasurementStatus.Stale -> "POS?"
    status == MeasurementStatus.Pending -> if (showPendingValue && value != null && value.isFinite() && kotlin.math.abs(value) <= maxAbs)
        measurementLabel(value, MeasurementStatus.Available, suffix, maxAbs) + "?" else "--"
    status == MeasurementStatus.Unknown || value == null || !value.isFinite() || kotlin.math.abs(value)>maxAbs -> "Unk"
    else -> String.format(java.util.Locale.US,"%.0f",value).let { if (it == "-0") "0" else it }+suffix
}

/** Missing prerequisites are unavailable immediately, never queued surface work. */
internal fun aolPrerequisiteState(hasLaunchReference: Boolean, hasHeight: Boolean, heightStale: Boolean): AolState? = when {
    heightStale -> AolState(reason = "Aircraft height stale", status = MeasurementStatus.Stale)
    !hasLaunchReference -> AolState(reason = "Takeoff ground reference not observed; receive ground status and near-zero height before flight")
    !hasHeight -> AolState(reason = "Takeoff-relative altitude unavailable")
    else -> null
}

/** Explicit reference paired with DJI relative height; never use aircraft position here. */
internal fun videoAolReference(latitude: Double?, longitude: Double?, height: Double?, fresh: Boolean): Pair<Double, Double>? {
    if (!fresh || latitude == null || longitude == null || height == null ||
        !latitude.isFinite() || !longitude.isFinite() || !height.isFinite() ||
        latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
        (latitude == 0.0 && longitude == 0.0) || height !in -999.0..30000.0) return null
    return latitude to longitude
}

package org.ncssar.rid2caltopo.video.surface

/** Briefly retains a completed measurement during refresh, never across flight or calibration changes. */
internal class AolRefreshWindow {
    private var reference: String? = null
    private var previous: AolState? = null
    private var refreshStarted: Long? = null

    /** Deliver a completed sample even if a newer position arrived during calculation.
     * Its grace is measured from request time, not completion, so slow work cannot look fresh. */
    fun completed(reference: String, result: AolState, startedAt: Long, now: Long) {
        if (now - startedAt !in 0 until 1_500L) return
        this.reference = reference
        previous = result.takeIf { it.status == MeasurementStatus.Available }
        refreshStarted = startedAt
    }

    fun display(reference: String, result: AolState?, hasHeight: Boolean, now: Long): AolState {
        if (this.reference != reference || !hasHeight) {
            previous = null
            refreshStarted = null
        }
        this.reference = reference
        if (result != null) {
            previous = result.takeIf { it.status == MeasurementStatus.Available }
            refreshStarted = null
            return result
        }
        if (previous != null) {
            val started = refreshStarted ?: now.also { refreshStarted = it }
            if (now - started in 0 until 1_500L) return previous!!
        }
        return AolState(reason = "Surface calculation pending for current position", status = MeasurementStatus.Pending)
    }
}

/** Stream reference refinements still recalculate AOL, but share the bounded display grace. */
internal fun aolRefreshReference(flight: String, anchor: Pair<Double, Double>?, automaticVideo: Boolean,
                                 manualAltitude: Double?, surfaceGeneration: String): String =
    "$flight|${if (automaticVideo && manualAltitude == null) "video" else anchor}|$manualAltitude|$surfaceGeneration"

/** A validated DJI launch reference belongs to the flight, not one decoder callback. */
internal class AolVideoReferenceContinuity {
    private var flight: Long? = null
    private var reference: Pair<Double, Double>? = null

    fun observe(flight: Long, validatedReference: Pair<Double, Double>?): Pair<Double, Double>? {
        if (this.flight != flight) reference = null
        this.flight = flight
        if (validatedReference != null) reference = validatedReference
        return reference
    }
}

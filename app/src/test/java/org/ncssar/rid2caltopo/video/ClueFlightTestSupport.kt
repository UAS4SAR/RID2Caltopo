package org.ncssar.rid2caltopo.video

import org.ncssar.rid2caltopo.data.ClueBinding

/** Minimal flight binding: every stored clue belongs to a flight (per-flight folder layout). */
internal fun testFlightBinding(
    captureTimeMs: Long,
    flightId: String = "flight-test",
    aircraftId: String = "AC1",
) = ClueBinding(
    aircraftId = aircraftId,
    flightId = flightId,
    flightStartMs = null,
    captureTimeMs = captureTimeMs,
    captureTimeSource = "app-receive",
    captureReceivedAtMs = captureTimeMs,
)

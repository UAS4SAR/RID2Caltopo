package org.ncssar.rid2caltopo.data

/**
 * Shared recovery policy for unfinished waypoint journals: decline / suppress / published /
 * already-finished awaiting-map rows must never re-enter the inbox.
 */
internal object FlightPublicationRecoveryGate {
    @JvmStatic
    fun shouldRetireWithoutPromotion(flightId: String): Boolean {
        if (FlightPublicationLedger.blocksAwaitingMapPromotion(flightId)) return true
        for (entry in AwaitingMapFlights.allEntries()) {
            if (entry.optString("id") != flightId) continue
            val decision = entry.optString("decision")
            if (TrackArchiveConfirmation.isConfirmed(
                    decision = decision,
                    archiveConfirmed = entry.optBoolean("archiveConfirmed"),
                    hasArchiveId = entry.optString("caltopoArchiveId").isNotEmpty(),
                )
            ) {
                FlightPublicationLedger.remember(flightId, FlightPublicationLedger.Disposition.PUBLISHED)
                return true
            }
            if (decision.equals("local", ignoreCase = true)) {
                FlightPublicationLedger.remember(flightId, FlightPublicationLedger.Disposition.DECLINED)
                return true
            }
            // Already finished in awaiting-map (including a prior recovery): do not re-promote.
            if (entry.optBoolean("finished")) return true
        }
        return false
    }
}

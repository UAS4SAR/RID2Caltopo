package org.ncssar.rid2caltopo.data

/**
 * Map and tracker publication require an explicit current-flight Confirmed decision.
 * Ignore keeps local recording but never publishes. Unconfirmed flights publish nothing.
 */
enum class CurrentFlightPublicationGate {
    ALLOWED,
    GATED_UNCONFIRMED,
    GATED_IGNORED;

    val allowsPublication: Boolean get() = this == ALLOWED

    val logLabel: String
        get() = when (this) {
            ALLOWED -> "allowed"
            GATED_UNCONFIRMED -> "gated_unconfirmed"
            GATED_IGNORED -> "gated_ignored"
        }

    companion object {
        @JvmStatic
        fun decide(confirmed: Boolean, ignored: Boolean): CurrentFlightPublicationGate = when {
            ignored -> GATED_IGNORED
            confirmed -> ALLOWED
            else -> GATED_UNCONFIRMED
        }
    }
}

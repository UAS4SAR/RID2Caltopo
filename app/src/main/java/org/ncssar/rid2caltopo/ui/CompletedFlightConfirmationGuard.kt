package org.ncssar.rid2caltopo.ui

/** A retained LIVE flag is not evidence of a new flight. Times are receive times. */
internal class CompletedFlightConfirmationGuard(private val log: (String) -> Unit = {}) {
    private data class Ended(val atMs: Long, val sessions: Set<String>, var suppressionLogged: Boolean = false)
    private val ended = mutableMapOf<String, Ended>()
    private var sessions: Map<String, Set<String>> = emptyMap()

    private val lastObservedSessions = mutableMapOf<String, Set<String>>()

    fun updateSessions(current: Map<String, Set<String>>) {
        sessions = current
        current.forEach { (id, tokens) -> if (tokens.isNotEmpty()) lastObservedSessions[id] = tokens }
    }

    fun end(remoteId: String, atMs: Long) {
        val retired = ended[remoteId]?.sessions.orEmpty() + lastObservedSessions[remoteId].orEmpty()
        ended[remoteId] = Ended(atMs, retired)
        log("Confirmation retired remoteId=$remoteId endedAtMs=$atMs publisherSessions=${retired.sorted()}")
    }

    fun allows(remoteId: String, receivedAtMs: Long = 0L): Boolean {
        val completion = ended[remoteId] ?: return true
        val freshAircraft = receivedAtMs > completion.atMs
        val newPublisher = sessions[remoteId].orEmpty().any {
            !it.endsWith("|unknown") && it !in completion.sessions &&
                "${it.substringBeforeLast('|')}|unknown" !in completion.sessions
        }
        if (!freshAircraft && !newPublisher) {
            if (!completion.suppressionLogged) {
                completion.suppressionLogged = true
                log("Confirmation suppressed remoteId=$remoteId endedAtMs=${completion.atMs} aircraftReceivedAtMs=$receivedAtMs publisherSessions=${sessions[remoteId].orEmpty().sorted()} reason=no_new_flight_evidence")
            }
            return false
        }
        log("Confirmation rearmed remoteId=$remoteId aircraftReceivedAtMs=$receivedAtMs publisherSessions=${sessions[remoteId].orEmpty().sorted()} reason=${if (freshAircraft) "fresh_aircraft" else "new_publisher"}")
        ended.remove(remoteId)
        return true
    }
}

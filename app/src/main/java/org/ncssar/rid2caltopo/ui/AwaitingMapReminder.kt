package org.ncssar.rid2caltopo.ui

/** Remind once per eligible flight during this app session; Dismiss never changes consent. */
internal class AwaitingMapReminder {
    private val reminded = mutableSetOf<String>()
    fun shouldPresent(eligibleFlightIds: Set<String>, hasMap: Boolean): Boolean {
        if (hasMap) return false
        val unseen = eligibleFlightIds - reminded
        reminded.addAll(eligibleFlightIds)
        return unseen.isNotEmpty()
    }
}

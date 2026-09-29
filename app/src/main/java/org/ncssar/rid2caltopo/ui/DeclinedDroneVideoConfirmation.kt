package org.ncssar.rid2caltopo.ui

/** Declining RID publication stays remembered; a new local publisher may ask once. */
internal class DeclinedDroneVideoConfirmation {
    private var current: Map<String, Set<String>> = emptyMap()
    private val handled = mutableMapOf<String, Set<String>>()
    fun update(sessions: Map<String, Set<String>>) {
        for ((id, previous) in handled.toMap()) {
            val next = previous.toMutableSet()
            for (token in previous.filter { it.endsWith("|unknown") }) {
                val resolved = sessions[id].orEmpty().filter {
                    !it.endsWith("|unknown") && it.substringBeforeLast('|') == token.substringBeforeLast('|')
                }
                if (resolved.isNotEmpty() || sessions[id].isNullOrEmpty()) {
                    next.remove(token)
                    next.addAll(resolved)
                }
            }
            handled[id] = next
        }
        current = sessions
    }
    fun markHandled(remoteId: String) {
        handled[remoteId] = handled[remoteId].orEmpty() + current[remoteId].orEmpty()
    }
    fun hasNewPublisher(remoteId: String): Boolean = current[remoteId].orEmpty().any {
        val previous = handled[remoteId].orEmpty()
        !it.endsWith("|unknown") && it !in previous &&
            "${it.substringBeforeLast('|')}|unknown" !in previous
    }
}

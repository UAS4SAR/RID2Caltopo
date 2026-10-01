package org.ncssar.rid2caltopo.data

import java.net.URI
import java.util.UUID

/** Browser-process authorization ledger. Every publication and revocation has one lock. */
class PersonalSessionGrants {
    data class Identity(val accountID: String?, val generation: Long)
    private data class Grant(val identity: Identity, val mapID: String, val media: MutableSet<String> = mutableSetOf())
    private var identity = Identity(null, 0)
    private var observation = 0L
    private var observing = false
    private val grants = mutableMapOf<String, Grant>()

    @Synchronized fun snapshot(): Identity = identity
    @Synchronized fun current(expected: Identity, ticket: Long = observation): Boolean = !observing && expected == identity && ticket == observation
    @Synchronized fun beginObservation(): Long { observing = true; return ++observation }
    @Synchronized fun observationTicket(): Long = observation
    @Synchronized fun observe(accountID: String?, expected: Identity, ticket: Long = observation): Boolean {
        if (identity != expected || ticket != observation) return false
        observing = false
        if (identity.accountID != accountID) {
            grants.clear()
            identity = Identity(accountID, identity.generation + 1)
        }
        return true
    }
    @Synchronized fun clear() {
        observation++
        observing = false
        grants.clear()
        identity = Identity(null, identity.generation + 1)
    }
    @Synchronized fun publish(expected: Identity, mapIDs: List<String>, ticket: Long = observation): Map<String, String> {
        check(expected.accountID != null && current(expected, ticket)) { "Personal account changed during loading." }
        require(mapIDs.all { it.matches(Regex("[A-Za-z0-9]+")) })
        return mapIDs.associateWith { mapID ->
            UUID.randomUUID().toString().also { grants[it] = Grant(expected, mapID) }
        }
    }
    @Synchronized fun authorizeMedia(token: String?, mediaID: String?): Boolean {
        val grant = grants[token]?.takeIf { current(it.identity) } ?: return false
        val id = mediaID?.let { CaltopoPersonalSession.mediaID("/api/v1/media/$it") } ?: return false
        grant.media.add(id)
        return true
    }
    @Synchronized fun valid(token: String?, url: String?): Boolean {
        val grant = grants[token]?.takeIf { current(it.identity) } ?: return false
        val uri = runCatching { URI(url ?: return false) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.host != "caltopo.com" || uri.port != -1 || uri.userInfo != null ||
            uri.fragment != null || uri.path != uri.rawPath || uri.normalize().path != uri.path) return false
        return uri.path.startsWith("/api/v1/map/${grant.mapID}/") ||
            CaltopoPersonalSession.mediaID(uri.path)?.let { it in grant.media } == true
    }
    /** Cookie lookup may suspend while the browser changes accounts. Never return that stale result. */
    fun cookie(token: String?, url: String?, lookup: (String) -> String?): String? {
        val ticket = synchronized(this) {
            if (!valid(token, url)) return null
            observation
        }
        val cookie = lookup(url!!)
        return synchronized(this) { cookie.takeIf { ticket == observation && valid(token, url) } }
    }
}

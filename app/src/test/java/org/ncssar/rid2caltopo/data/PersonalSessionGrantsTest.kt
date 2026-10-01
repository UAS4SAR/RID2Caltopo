package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class PersonalSessionGrantsTest {
    private val url = "https://caltopo.com/api/v1/map/ABC123/since/0"
    private fun signedIn(): PersonalSessionGrants = PersonalSessionGrants().also { it.observe("A", it.snapshot()) }
    @Test fun clearWhileCatalogIsLoadingRejectsLatePublication() {
        val ledger = signedIn(); val snapshot = ledger.snapshot()
        ledger.clear()
        assertThrows(IllegalStateException::class.java) { ledger.publish(snapshot, listOf("ABC123")) }
    }
    @Test fun accountReplacementAndAbaCannotReuseMapOrMediaGrant() {
        val ledger = signedIn(); val snapshot = ledger.snapshot()
        val token = ledger.publish(snapshot, listOf("ABC123")).getValue("ABC123")
        val media = "11111111-2222-3333-4444-555555555555"
        assertTrue(ledger.authorizeMedia(token, media))
        ledger.observe("B", ledger.snapshot()); ledger.observe("A", ledger.snapshot())
        assertNull(ledger.cookie(token, url) { error("Must not fetch cookie") })
        assertFalse(ledger.authorizeMedia(token, media))
        assertThrows(IllegalStateException::class.java) { ledger.publish(snapshot, listOf("ABC123")) }
    }
    @Test fun clearDuringCookieLookupDiscardsAlreadyCapturedCookie() {
        for (replacement in listOf(false, true)) {
            val ledger = signedIn()
            val token = ledger.publish(ledger.snapshot(), listOf("ABC123")).getValue("ABC123")
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<String?> { ledger.cookie(token, url) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "fake-session-secret" } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (replacement) ledger.observe("B", ledger.snapshot()) else ledger.clear()
                release.countDown()
                assertNull(pending.get(5, TimeUnit.SECONDS))
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }
    @Test fun browserNavigationSuspendsThenResumesSameAccountGrants() {
        val ledger = signedIn(); val snapshot = ledger.snapshot()
        val token = ledger.publish(snapshot, listOf("ABC123")).getValue("ABC123")
        val ticket = ledger.beginObservation()
        assertFalse(ledger.valid(token, url))
        assertFalse(ledger.authorizeMedia(token, "11111111-2222-3333-4444-555555555555"))
        assertTrue(ledger.observe("A", snapshot, ticket))
        assertTrue(ledger.valid(token, url))
        val obsolete = ledger.beginObservation(); val current = ledger.beginObservation()
        assertFalse(ledger.observe("A", snapshot, obsolete))
        assertFalse(ledger.valid(token, url))
        assertTrue(ledger.observe("B", snapshot, current))
        assertFalse(ledger.valid(token, url))
    }
    @Test fun cookieAndCatalogSpanningNavigationCannotSurviveEvenWhenAccountReturns() {
        val ledger = signedIn(); val snapshot = ledger.snapshot(); val initialTicket = ledger.observationTicket()
        val token = ledger.publish(snapshot, listOf("ABC123")).getValue("ABC123")
        assertNull(ledger.cookie(token, url) {
            val ticket = ledger.beginObservation()
            ledger.observe("A", snapshot, ticket)
            "fake-cookie-during-navigation"
        })
        assertTrue(ledger.valid(token, url)) // Fresh requests for the same verified account still work.
        assertThrows(IllegalStateException::class.java) { ledger.publish(snapshot, listOf("ABC123"), initialTicket) }
    }
    @Test fun identityCallbackPendingAtClearCannotResurrectLogin() {
        val ledger = signedIn(); val snapshot = ledger.snapshot(); val ticket = ledger.beginObservation()
        ledger.clear()
        assertFalse(ledger.observe("A", snapshot, ticket))
        assertNull(ledger.snapshot().accountID)
    }
    @Test fun urlsCannotEscapeGrantedMap() {
        val ledger = signedIn(); val token = ledger.publish(ledger.snapshot(), listOf("ABC123")).getValue("ABC123")
        for (bad in listOf("http://caltopo.com/api/v1/map/ABC123/x", "$url/../../../../other", "https://other.test/api/v1/map/ABC123/x", "https://caltopo.com/api/v1/map/OTHER/x", "https://caltopo.com/api/v1/map/ABC123/%2e%2e/OTHER")) assertFalse(bad, ledger.valid(token, bad))
        assertTrue(ledger.valid(token, url))
    }
}

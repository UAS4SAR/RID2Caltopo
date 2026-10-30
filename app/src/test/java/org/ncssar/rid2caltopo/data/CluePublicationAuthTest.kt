package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CluePublicationAuthTest {
    @After fun reset() {
        CaltopoPersonalSession.resetRuntimeState()
        CaltopoPersonalAccountConnection.resetForTests()
    }

    @Test fun signedPersonalCredentialSkipsCookieMediaProbe() {
        CaltopoPersonalSession.acceptCatalog(
            CaltopoPersonalSession.PreparedCatalog("BCL66J", "user", mapOf("4J0LF02" to "BCL66J"), emptyList())
        )
        CaltopoPersonalSession.activate(CaltopoPersonalAccountConnection.SIGNED_ACCOUNT_GRANT, "4J0LF02")
        val personal = CaltopoPersonalSession.capture("")
        assertTrue(CluePublicationAuth.usesSignedPersonalCredential(personal))
        assertFalse(CluePublicationAuth.requiresCookieMediaAuthorization(personal))
        assertEquals("personal:BCL66J", personal!!.credentialKey)
    }

    @Test fun browserSessionStillRequiresCookieMediaAuthorization() {
        CaltopoPersonalSession.acceptCatalog(
            CaltopoPersonalSession.PreparedCatalog("BCL66J", "user", mapOf("4J0LF02" to "BCL66J"), emptyList())
        )
        CaltopoPersonalSession.activate("session-token-uuid", "4J0LF02")
        val personal = CaltopoPersonalSession.capture("")
        assertFalse(CluePublicationAuth.usesSignedPersonalCredential(personal))
        assertTrue(CluePublicationAuth.requiresCookieMediaAuthorization(personal))
    }

    @Test fun loginExpiredMessageOnlyForHttpAuthFailures() {
        assertEquals("Personal login expired; photo remains queued.", CluePublicationAuth.loginExpiredMessage(401))
        assertEquals("Personal login expired; photo remains queued.", CluePublicationAuth.loginExpiredMessage(403))
        assertNull(CluePublicationAuth.loginExpiredMessage(0))
        assertNull(CluePublicationAuth.loginExpiredMessage(500))
        assertNull(CluePublicationAuth.loginExpiredMessage(404))
    }
}

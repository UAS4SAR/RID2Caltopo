package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class CaltopoEmbeddedSignInPolicyTest {
    @Test fun googleAuthorizationAndProviderEntryAreBlocked() {
        for (url in listOf(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=test",
            "https://ACCOUNTS.GOOGLE.COM./v3/signin/identifier",
            "https://user@accounts.google.com/ServiceLogin",
            "https://accounts.google.com:443/gsi/iframe", "https://oauth2.googleapis.com/token",
            "https://www.googleapis.com/oauth2/v4/token", "https://www.google.com/accounts/ServiceLogin",
            "https://google.com/o/oauth2/auth", "https://caltopo.com/account/oidclogin?domain=google",
            "https://caltopo.com/account/oidclogin?url=%2F&domain=%67oogle",
            "https://maps.agency.example/base/account/oidclogin?domain=google"
        )) {
            assertTrue(url, CaltopoEmbeddedSignInPolicy.isGoogleSignIn(url))
            assertFalse(url, CaltopoEmbeddedSignInPolicy.allowsEmbedded(url))
        }
    }
    @Test fun emailPasswordPrivateAgencyAndOrdinaryGooglePagesRemainAvailable() {
        for (url in listOf("https://caltopo.com/account/login", "https://caltopo.com/join", "https://caltopo.com/api/v0/acct/login",
            "https://caltopo.com/map?id=ABC123", "https://caltopo.com/account/oidclogin?domain=apple",
            "https://caltopo.com/account/oidclogin?domain=msft", "https://maps.agency.example:8443/private/incident",
            "https://www.google.com/maps", "https://fonts.googleapis.com/css?family=test", "https://accounts.google.com.example.org/agency", "about:blank")) {
            assertFalse(url, CaltopoEmbeddedSignInPolicy.isGoogleSignIn(url))
            assertTrue(url, CaltopoEmbeddedSignInPolicy.allowsEmbedded(url))
        }
    }
    @Test fun unsafeSchemesRemainBlocked() {
        for (url in listOf("http://agency.example", "file:///tmp/page", "javascript:alert(1)", "r2cmapkg1:test", "https://bad host/"))
            assertFalse(url, CaltopoEmbeddedSignInPolicy.allowsEmbedded(url))
    }
    @Test fun blockingAndSetupPolicyPreserveExistingNativeGrant() {
        val grants = PersonalSessionGrants().also { it.observe("existing-account", it.snapshot()) }
        val token = grants.publish(grants.snapshot(), listOf("ABC123")).getValue("ABC123")
        val destination = "https://caltopo.com/api/v1/map/ABC123/since/0"
        assertFalse(CaltopoEmbeddedSignInPolicy.allowsEmbedded("https://accounts.google.com/o/oauth2/auth"))
        assertTrue(CaltopoEmbeddedSignInPolicy.allowsEmbedded(CaltopoEmbeddedSignInPolicy.LOGIN_URL))
        assertTrue(grants.valid(token, destination))
        assertEquals("fake-existing-cookie", grants.cookie(token, destination) { "fake-existing-cookie" })
    }
    @Test fun setupUsesOfficialPagesAndExplainsExistingAccountLinking() {
        assertEquals("https://caltopo.com/join", CaltopoEmbeddedSignInPolicy.SETUP_URL)
        assertTrue(CaltopoEmbeddedSignInPolicy.SETUP_INSTRUCTIONS.contains("exact email"))
        assertTrue(CaltopoEmbeddedSignInPolicy.SETUP_INSTRUCTIONS.contains("does not ask for your Google password"))
    }
}

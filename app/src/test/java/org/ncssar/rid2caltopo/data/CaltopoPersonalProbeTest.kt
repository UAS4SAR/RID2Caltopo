package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class CaltopoPersonalProbeTest {
    private val probe = CaltopoPersonalProbe
    @Test fun onlyCaltopoInvitationAndMapLinksAreAccepted() {
        assertNotNull(probe.browserURL("https://caltopo.com/group/ABC123/signup/EXAMPLE"))
        assertEquals("ABC123", probe.mapID("https://caltopo.com/m/ABC123#ll=1,2"))
        listOf("http://caltopo.com/m/ABC123", "https://caltopo.com.evil.test/m/ABC123",
            "https://user@caltopo.com/m/ABC123", "https://caltopo.com:443/m/ABC123",
            "https://caltopo.com/api/v1/map/ABC123", "javascript:alert(1)",
            "https://caltopo.com/m/ABC123/../../account/login").forEach { assertNull(probe.browserURL(it)) }
        assertNull(probe.mapID("../account/login"))
        assertNull(probe.mapID("https://caltopo.com/group/ABC123/signup/EXAMPLE"))
    }
    @Test fun htmlLoginAndMalformedSuccessNeverCountAsAccess() {
        assertFalse(probe.result(200, "<html>Log in</html>").readable)
        assertFalse(probe.result(200, "{\"status\":\"error\"}").readable)
        assertFalse(probe.result(302, "").readable)
        assertFalse(probe.result(200, "{\"status\":\"ok\",\"result\":{}}").readable)
    }
    @Test fun emptyPrivateMapIsValidButPublicSuccessDoesNotProveAuthentication() {
        val valid = probe.result(200, "{\"status\":\"ok\",\"result\":{\"state\":{\"features\":[]}}}")
        assertTrue(valid.readable)
        assertEquals(0, valid.featureCount)
        assertTrue(probe.comparison(valid, valid).contains("Both requests"))
        assertTrue(probe.comparison(valid, probe.result(403, "")).contains("anonymous access was denied"))
        assertTrue(probe.comparison(valid, probe.result(500, "")).contains("inconclusive"))
    }
}

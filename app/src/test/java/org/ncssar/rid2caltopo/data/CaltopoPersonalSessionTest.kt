package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CaltopoPersonalSessionTest {
    @Test fun mediaScopeAcceptsOnlyExplicitUuidMediaRoutes() {
        val id = "11111111-2222-3333-4444-555555555555"
        for (suffix in listOf("", "/data", "/original")) assertEquals(id, CaltopoPersonalSession.mediaID("/api/v1/media/$id$suffix"))
        for (path in listOf("/api/v1/media/other", "/api/v1/media/$id/../other", "/api/v1/media/$id/permissions", "/api/v1/media/$id?elsewhere=1"))
            assertNull(CaltopoPersonalSession.mediaID(path))
    }
    @After fun reset() { CaltopoPersonalSession.activate(null, "") }
    @Test fun queuedAuthorizationStaysBoundWhenMapOrAccountChanges() {
        CaltopoPersonalSession.activate("test-only-session", "ABC123")
        val captured = CaltopoPersonalSession.capture("/api/v1/map/ABC123/since/0")!!
        CaltopoPersonalSession.activate("another-session", "DEF456")
        assertEquals("test-only-session", captured.token)
        assertEquals("ABC123", captured.mapID)
        CaltopoPersonalSession.activate(null, "")
        assertNull(CaltopoPersonalSession.capture("/api/v1/map/ABC123/since/0"))
        assertEquals("test-only-session", captured.token)
    }
    @Test fun teamBrowserDoesNotInheritPersonalMapAuthorization() {
        CaltopoPersonalSession.activate("test-only-session", "ABC123")
        assertNull(CaltopoPersonalSession.capture("/api/v1/acct/team/since/0"))
        assertNotNull(CaltopoPersonalSession.capture("/api/v1/map/ABC123/since/0"))
    }
    @Test fun missingBrowserSessionFailsClosedAndOtherMapsAreRejected() {
        val authorization = CaltopoPersonalSession.Authorization("missing", "ABC123")
        assertThrows(IllegalArgumentException::class.java) {
            CaltopoPersonalSession.cookie(authorization, "/api/v1/map/DEF456/since/0")
        }
        assertThrows(IllegalStateException::class.java) {
            CaltopoPersonalSession.cookie(authorization, "/api/v1/map/ABC123/since/0")
        }
    }
}

package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogPendingKeyTest {
    @Test fun pendingKeyNamesTheDayFolderAndFile() {
        val key = SessionLogCardSync.pendingKey("tracks-18Nov2026", "Log_18Nov2026_0701.txt")
        assertEquals("pending:tracks-18Nov2026/Log_18Nov2026_0701.txt", key)
        assertTrue(key.startsWith(SessionLogCardSync.PENDING_PREFIX))
    }
}

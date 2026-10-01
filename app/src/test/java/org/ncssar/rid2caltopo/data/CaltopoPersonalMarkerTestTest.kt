package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class CaltopoPersonalMarkerTestTest {
    private val id = "f31f915d-cc63-48ab-854f-9643c0c80c01"
    private val trial = CaltopoPersonalMarkerTest
    private fun snapshot(feature: String = "") = CaltopoPersonalMarkerTest.Reply(200,
        """{"status":"ok","result":{"state":{"features":[$feature]}}}""")

    @Test fun createsReadsDeletesAndVerifiesOnlyItsOwnMarker() {
        val methods = mutableListOf<String>()
        var present = false
        var pending = false
        val result = trial.run(id, false, { method, path, payload ->
            methods += method
            assertTrue(path.startsWith("/api/v1/map/G00CPSS/"))
            when (method) {
                "POST" -> { assertTrue(pending); assertEquals(trial.payload(id), payload); present = true; snapshot() }
                "DELETE" -> { assertEquals(trial.markerPath(id), path); present = false; snapshot() }
                else -> snapshot(if (present) trial.payload(id) else "")
            }
        }, { pending = true }, { pending = false })
        assertEquals(listOf("GET", "POST", "GET", "DELETE", "GET"), methods)
        assertFalse(pending)
        assertTrue(result.contains("verified absent"))
    }
    @Test fun ambiguousCreateKeepsJournalAndNeverRetriesOrDeletes() {
        var pending = false
        val methods = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            trial.run(id, false, { method, _, _ ->
                methods += method
                if (method == "POST") CaltopoPersonalMarkerTest.Reply(503, "") else snapshot()
            }, { pending = true }, { pending = false })
        }
        assertTrue(pending)
        assertEquals(listOf("GET", "POST"), methods)
    }
    @Test fun cleanupRefusesForeignMarkerAndMalformedMap() {
        val foreign = trial.payload(id).replace(trial.title(id), "Existing operator marker")
        var writes = 0
        assertThrows(IllegalStateException::class.java) {
            trial.run(id, true, { method, _, _ -> if (method != "GET") writes++; snapshot(foreign) }, {}, {})
        }
        assertEquals(0, writes)
        assertThrows(IllegalStateException::class.java) { trial.presence(CaltopoPersonalMarkerTest.Reply(200, "<html>Login</html>"), id) }
    }
    @Test fun cleanupAbsentMarkerClearsJournalWithoutWriting() {
        var cleared = false
        trial.run(id, true, { method, _, _ -> assertEquals("GET", method); snapshot() }, {}, { cleared = true })
        assertTrue(cleared)
    }
}

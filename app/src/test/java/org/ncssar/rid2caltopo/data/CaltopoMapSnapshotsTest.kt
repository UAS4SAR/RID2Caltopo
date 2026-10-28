package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.function.LongFunction

class CaltopoMapSnapshotsTest {
    private fun scope(map: String = "ABC123", secret: String = "secret", token: String? = null) =
        CaltopoMapSnapshots.Scope("https://caltopo.com", map, "team", "key", secret, token, 0)
    private fun feature(id: String, title: String = id) = JSONObject().put("id", id).put("properties",
        JSONObject().put("class", "Marker").put("title", title))
    private fun result(vararg features: JSONObject) = JSONObject().put("state",
        JSONObject().put("features", JSONArray(features.toList())))

    @Test fun loadedSnapshotRefreshesWithDeltasWhenSwitchingBack() {
        var now = 0L
        val cache = CaltopoMapSnapshots({ now }, { 1_000L + now })
        val cursors = mutableListOf<Long>()
        val load = LongFunction { cursor -> cursors.add(cursor); result(feature("one")) }
        cache.read(scope(), Runnable {}, load)
        now = 10_000
        cache.read(scope(), Runnable {}, load)
        assertEquals(listOf(0L, 1_000L), cursors)
        now = 21_000
        cache.read(scope(), Runnable {}, load)
        assertEquals(listOf(0L, 1_000L, 11_000L), cursors)
        cache.read(scope("DEF456"), Runnable {}, load)
        assertEquals(listOf(0L, 1_000L, 11_000L, 0L), cursors)
    }
    @Test fun cachedCursorSurvivesReuseSoLaterUpdatesAreNotSkipped() {
        var now = 0L
        val cache = CaltopoMapSnapshots({ now }, { 1_000L + now })
        cache.read(scope(), Runnable {}, LongFunction { result(feature("one")) })
        now = 10_000
        val reused = cache.read(scope(), Runnable {}, LongFunction { cursor -> assertEquals(1_000L, cursor); result() })
        assertEquals(11_000L, reused.getLong("timestamp"))
        reused.getJSONObject("state").getJSONArray("features").getJSONObject(0)
            .getJSONObject("properties").put("title", "mutated by caller")
        val again = cache.read(scope(), Runnable {}, LongFunction { result() })
        assertEquals("one", again.getJSONObject("state").getJSONArray("features").getJSONObject(0)
            .getJSONObject("properties").getString("title"))
    }
    @Test fun deltaDeletesAndAuthoritativeIdsReconcileTheSnapshot() {
        var now = 0L
        val cache = CaltopoMapSnapshots({ now }, { 1_000L + now })
        cache.read(scope(), Runnable {}, LongFunction { result(feature("one"), feature("two"), feature("three")) })
        now = 21_000
        val next = cache.read(scope(), Runnable {}, LongFunction {
            result(JSONObject().put("id", "one").put("deleted", true), feature("two", "updated"))
                .put("ids", JSONObject().put("Marker", JSONArray(listOf("two"))))
        }).getJSONObject("state").getJSONArray("features")
        assertEquals(1, next.length())
        assertEquals("two", next.getJSONObject(0).getString("id"))
        assertEquals("updated", next.getJSONObject(0).getJSONObject("properties").getString("title"))
    }
    @Test fun differentCredentialsOrPersonalGrantsCannotReuseAnotherAccountsSnapshot() {
        val cache = CaltopoMapSnapshots()
        var reads = 0
        val load = LongFunction { cursor -> assertEquals(0L, cursor); reads++; result(feature("one")) }
        cache.read(scope(), Runnable {}, load)
        cache.read(scope(secret = "different-secret"), Runnable {}, load)
        cache.read(scope(token = "personal-one"), Runnable {}, load)
        cache.read(scope(token = "personal-two"), Runnable {}, load)
        assertEquals(4, reads)
        assertThrows(IllegalStateException::class.java) {
            cache.read(scope(), Runnable { error("Login revoked") }, load)
        }
        assertEquals(4, reads)
    }
    @Test fun expiredSnapshotRequiresAFullRead() {
        var now = 0L
        val cache = CaltopoMapSnapshots({ now }, { 1_000L + now })
        val load = LongFunction { cursor -> assertEquals(0L, cursor); result() }
        cache.read(scope(), Runnable {}, load)
        now = 120_000
        cache.read(scope(), Runnable {}, load)
    }
}

package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class CaltopoFullMapReadsTest {
    @Test fun firstReadIsImmediateAndNextReadWaitsNinetySecondsAfterCompletion() {
        val gate = FullMapReadRateGate()
        assertEquals(0L, gate.tryBegin("map-one", "one", 0))
        assertTrue(gate.finish("map-one", "one", 5_000))
        assertEquals(90_000L, gate.tryBegin("map-one", "two", 5_000))
        assertEquals(1L, gate.tryBegin("map-one", "two", 94_999))
        assertEquals(0L, gate.tryBegin("map-one", "two", 95_000))
    }
    @Test fun concurrentReadersCannotOverlapEvenAfterNinetySeconds() {
        val gate = FullMapReadRateGate()
        assertEquals(0L, gate.tryBegin("map-one", "one", 0))
        assertEquals(250L, gate.tryBegin("map-one", "two", 1_000_000))
        assertTrue(gate.finish("map-one", "one", 1_000_000))
        assertEquals(90_000L, gate.tryBegin("map-one", "two", 1_000_000))
    }
    @Test fun staleLeaseCannotReleaseCurrentReadOrResetCooldown() {
        val gate = FullMapReadRateGate()
        gate.tryBegin("map-one", "one", 0)
        assertFalse(gate.finish("map-one", "old", 1))
        assertEquals(250L, gate.tryBegin("map-one", "two", 2))
        assertTrue(gate.finish("map-one", "one", 3))
        assertFalse(gate.finish("map-one", "one", 4))
        assertEquals(89_999L, gate.tryBegin("map-one", "two", 4))
    }
    @Test fun retriesAndDifferentAccountsOnTheSameMapUseTheSameBudget() {
        val gate = FullMapReadRateGate()
        gate.tryBegin("map-one", "failed-attempt", 0); gate.finish("map-one", "failed-attempt", 100)
        assertEquals(89_900L, gate.tryBegin("map-one", "retry", 200))
        assertEquals(89_800L, gate.tryBegin("map-one", "another-account", 300))
        assertEquals(0L, gate.tryBegin("map-one", "retry", 90_100))
    }
    @Test fun monotonicClockRollbackNeverShortensTheCooldown() {
        val gate = FullMapReadRateGate()
        gate.tryBegin("map-one", "one", 1_000); gate.finish("map-one", "one", 2_000)
        assertEquals(91_000L, gate.tryBegin("map-one", "two", 1_000))
    }
    @Test fun providerBrokerSharesBudgetAndReleasesDeadCallerLease() {
        var death: android.os.IBinder.DeathRecipient? = null
        var unlinked = false
        val binder = object : android.os.Binder() {
            override fun linkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int) { death = recipient }
            override fun unlinkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int): Boolean { unlinked = true; return true }
        }
        assertEquals(0L, CaltopoFullMapReadBroker.begin("broker-map-one", "caller-one", binder))
        CaltopoFullMapReadBroker.finish("stale-caller")
        assertEquals(250L, CaltopoFullMapReadBroker.begin("broker-map-one", "caller-two", binder))
        assertNotNull(death)
        death!!.binderDied()
        assertTrue(unlinked)
        assertEquals(90_000L, CaltopoFullMapReadBroker.begin("broker-map-one", "caller-two", binder))
    }
    @Test fun differentMapsCanFetchImmediatelyEvenWhileAnotherMapIsActiveOrCoolingDown() {
        val gate = FullMapReadRateGate()
        assertEquals(0L, gate.tryBegin("map-one", "one", 0))
        assertEquals(0L, gate.tryBegin("map-two", "two", 0))
        assertTrue(gate.finish("map-one", "one", 100))
        assertTrue(gate.finish("map-two", "two", 200))
        assertEquals(0L, gate.tryBegin("map-three", "three", 300))
        assertEquals(89_800L, gate.tryBegin("map-one", "again", 300))
        assertEquals(89_900L, gate.tryBegin("map-two", "again", 300))
    }
    @Test fun mapKeysShareAccountsButSeparateServersAndNormalizeDefaultPort() {
        val path = "/api/v1/map/ABC123/since/0"
        assertEquals(CaltopoFullMapReads.mapKey("https://caltopo.com", path),
            CaltopoFullMapReads.mapKey("https://CALTOPO.COM:443", path))
        assertNotEquals(CaltopoFullMapReads.mapKey("https://caltopo.com", path),
            CaltopoFullMapReads.mapKey("https://another.example", path))
        assertNotEquals(CaltopoFullMapReads.mapKey("https://caltopo.com", path),
            CaltopoFullMapReads.mapKey("https://caltopo.com", "/api/v1/map/DEF456/since/0"))
    }
    @Test fun onlyFullMapReadsConsumeTheBudget() {
        assertTrue(CaltopoFullMapReads.isFullMapRead("GET", "/api/v1/map/ABC123/since/0"))
        for (path in listOf("/api/v1/map/ABC123/since/1234", "/api/v1/acct/USER01/since/0",
            "/api/v1/map/ABC123/Marker/id", "/api/v1/position/report/key", "/api/v1/media/id")) {
            assertFalse(CaltopoFullMapReads.isFullMapRead("GET", path))
        }
        assertFalse(CaltopoFullMapReads.isFullMapRead("POST", "/api/v1/map/ABC123/since/0"))
    }
}

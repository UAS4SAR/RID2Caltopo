package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TrackerStartupCheckInTest {
    @After fun reset() = TrackerStartupGate.resetForTesting()

    // ── Gate ──────────────────────────────────────────────────────────────

    @Test fun teamAccessWaitsWhileTheStartupCheckInIsPending() {
        TrackerStartupGate.beginStartupCheckIn()
        assertEquals(TrackerStartupGate.WAITING_MESSAGE, TrackerStartupGate.teamAccessBlockReason("caltopo.com"))
        assertFalse(TrackerStartupGate.teamAccessRefused("caltopo.com"))
        // Pending also holds a CalTopo Desktop server until the check-in has an outcome.
        assertNotNull(TrackerStartupGate.teamAccessBlockReason("192.168.1.20:8080"))
    }

    @Test fun workerWaitReleasesWhenTheCheckInSucceeds() {
        TrackerStartupGate.beginStartupCheckIn()
        val result = AtomicReference<String?>("unset")
        val done = CountDownLatch(1)
        Thread {
            result.set(TrackerStartupGate.awaitTeamAccess("caltopo.com", 5_000L))
            done.countDown()
        }.start()
        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        TrackerStartupGate.set(TrackerStartupGate.State.CHECKED_IN)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNull(result.get())
    }

    @Test fun unreachableTrackerRefusesTeamCaltopoButNotDesktop() {
        TrackerStartupGate.set(TrackerStartupGate.State.UNREACHABLE)
        assertEquals(TrackerStartupGate.UNREACHABLE_MESSAGE, TrackerStartupGate.awaitTeamAccess("caltopo.com", 10L))
        assertTrue(TrackerStartupGate.teamAccessRefused("caltopo.com"))
        assertTrue(TrackerStartupGate.teamAccessRefused(""))
        assertNull(TrackerStartupGate.awaitTeamAccess("10.0.0.5:8080", 10L))
    }

    @Test fun rejectedDeviceRefusesTeamCaltopo() {
        TrackerStartupGate.set(TrackerStartupGate.State.REJECTED)
        assertEquals(TrackerStartupGate.REJECTED_MESSAGE, TrackerStartupGate.teamAccessBlockReason("caltopo.com"))
    }

    @Test fun nonOrganizationDevicesAreNeverGated() {
        TrackerStartupGate.set(TrackerStartupGate.State.NOT_REQUIRED)
        assertNull(TrackerStartupGate.awaitTeamAccess("caltopo.com", 10L))
    }

    @Test fun deferredWorkRunsOnceWhenTheCheckInIsDecided() {
        TrackerStartupGate.beginStartupCheckIn()
        var runs = 0
        TrackerStartupGate.runWhenDecided { runs++ }
        assertEquals(0, runs)
        TrackerStartupGate.set(TrackerStartupGate.State.UNREACHABLE)
        TrackerStartupGate.set(TrackerStartupGate.State.CHECKED_IN)
        assertEquals(1, runs)
        TrackerStartupGate.runWhenDecided { runs++ }
        assertEquals(2, runs)
    }

    // ── Retry, ordering and fallback ───────────────────────────────────────

    @Test fun coldTrackerIsRetriedWithBackoffUntilItAnswers() {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val outcome = TrackerStartupSync.checkInWithRetries(
            fetch = { attempt ->
                calls = attempt
                if (attempt < 3) ManagedConfigFetchOutcome.Unreachable("timeout") else ManagedConfigFetchOutcome.CheckedIn
            },
            sleep = { sleeps += it }
        )
        assertEquals(ManagedConfigFetchOutcome.CheckedIn, outcome)
        assertEquals(3, calls)
        assertEquals(TrackerStartupSync.ATTEMPT_BACKOFF_MS.toList(), sleeps)
    }

    @Test fun unreachableAfterAllAttemptsFallsBackToPersonal() {
        var calls = 0
        val outcome = TrackerStartupSync.checkInWithRetries(
            fetch = { calls++; ManagedConfigFetchOutcome.Unreachable("offline") },
            sleep = {}
        )
        assertEquals(TrackerStartupSync.ATTEMPT_BACKOFF_MS.size + 1, calls)
        assertEquals(TrackerStartupGate.State.UNREACHABLE, TrackerStartupSync.gateStateFor(outcome))
    }

    @Test fun rejectionStopsRetryingAtOnce() {
        var calls = 0
        val outcome = TrackerStartupSync.checkInWithRetries(
            fetch = { calls++; ManagedConfigFetchOutcome.Rejected },
            sleep = {}
        )
        assertEquals(1, calls)
        assertEquals(TrackerStartupGate.State.REJECTED, TrackerStartupSync.gateStateFor(outcome))
    }

    @Test fun laterRefreshFailureDoesNotRevokeAnEarlierCheckIn() {
        val failed = ManagedConfigFetchOutcome.Unreachable("offline")
        assertEquals(TrackerStartupGate.State.CHECKED_IN,
            TrackerStartupSync.nextGateState(TrackerStartupGate.State.CHECKED_IN, failed))
        assertEquals(TrackerStartupGate.State.UNREACHABLE,
            TrackerStartupSync.nextGateState(TrackerStartupGate.State.PENDING, failed))
        // Background retry that succeeds switches to team access.
        assertEquals(TrackerStartupGate.State.CHECKED_IN,
            TrackerStartupSync.nextGateState(TrackerStartupGate.State.UNREACHABLE, ManagedConfigFetchOutcome.CheckedIn))
    }

    @Test fun trackerClientIsSeparateFromTheCaltopoClientThatConnectivityRecoveryCancels() {
        assertTrue(TrackerHttp.client.dispatcher !== CaltopoSession.MyOkHttpClient.dispatcher)
        assertTrue(TrackerHttp.client.connectionPool !== CaltopoSession.MyOkHttpClient.connectionPool)
        assertTrue(TrackerHttp.client.callTimeoutMillis >= 45_000)
    }

    @Test fun connectivityRecoveryCancelsCaltopoBeforeStartingTheTrackerCheckIn() {
        val source = source("app/NetworkCheckRecovery.kt")
        val cancel = source.indexOf("CaltopoSession.cancelInFlightRequestsForConnectivityRestore()")
        val checkIn = source.indexOf("TrackerStartupSync.onNetworkAvailable(")
        assertTrue(cancel >= 0 && checkIn > cancel)
        assertFalse(source.contains("retryManagedConfigurationBootstrap"))
        val manager = source("data/OrgConfigManager.kt")
        assertFalse(manager.contains("CaltopoSession.MyOkHttpClient.newCall"))
    }

    @Test fun teamSignedCaltopoRequestsAndMapInitPassThroughTheGate() {
        assertTrue(source("data/CaltopoSession.java").contains("TrackerStartupGate.awaitTeamAccess("))
        assertTrue(source("data/CaltopoMap.java").contains("TrackerStartupGate.teamAccessBlockReason(DomainAndPort)"))
        val application = source("app/R2CApplication.kt")
        assertTrue(application.indexOf("TrackerStartupGate.beginStartupCheckIn()") <
            application.indexOf("SettingsStartup.start"))
    }

    // ── Upgrade re-apply ─────────────────────────────────────────────────

    @Test fun sameVersionIsReappliedAfterAnAppUpgrade() {
        assertTrue(ManagedConfigApplyPolicy.needsApply(
            remoteVersionMs = 1794325364660L, localVersionMs = 1794325364660L,
            readinessSchemaVersion = 1, hasTeamCredentials = true,
            appliedByAppVersion = null, currentAppVersion = "2.5.9(445)"))
        assertTrue(ManagedConfigApplyPolicy.needsApply(
            1794325364660L, 1794325364660L, 1, true, "2.5.8(444)", "2.5.9(445)"))
    }

    @Test fun sameVersionAppliedByThisAppVersionIsSkipped() {
        assertFalse(ManagedConfigApplyPolicy.needsApply(
            1794325364660L, 1794325364660L, 1, true, "2.5.9(445)", "2.5.9(445)"))
        assertTrue(ManagedConfigApplyPolicy.needsApply(2L, 1L, 1, true, "2.5.9(445)", "2.5.9(445)"))
        assertTrue(ManagedConfigApplyPolicy.needsApply(1L, 1L, 1, false, "2.5.9(445)", "2.5.9(445)"))
    }

    @Test fun reappliedSnapshotPublishesTheTeamDroneOwnership() {
        val upgraded = DroneOwnershipPolicy.receive(DroneOwnershipPolicy.Record.initial, DroneOwnershipPolicy.STREAMING_DEVICE)
        assertEquals(DroneOwnershipPolicy.STREAMING_DEVICE, upgraded.mode)
    }

    private fun source(path: String): String {
        val working = File(System.getProperty("user.dir") ?: ".")
        val root = if (File(working, "src/main").isDirectory) working else File(working, "app")
        return File(root, "src/main/java/org/ncssar/rid2caltopo/$path").readText()
    }
}

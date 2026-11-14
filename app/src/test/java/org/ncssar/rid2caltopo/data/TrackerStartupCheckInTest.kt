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
        assertEquals(TrackerStartupGate.WAITING_MESSAGE, TrackerStartupGate.teamAccessBlockReason())
        assertFalse(TrackerStartupGate.teamAccessRefused())
    }

    @Test fun workerWaitReleasesWhenTheTrackerAuthorizesTheDevice() {
        TrackerStartupGate.beginStartupCheckIn()
        val result = AtomicReference<String?>("unset")
        val done = CountDownLatch(1)
        Thread {
            result.set(TrackerStartupGate.awaitTeamAccess(5_000L))
            done.countDown()
        }.start()
        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        TrackerStartupGate.set(TrackerStartupGate.State.CHECKED_IN)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNull(result.get())
    }

    @Test fun pendingWaitIsBoundedAndThenRefusesTeamAccess() {
        TrackerStartupGate.beginStartupCheckIn()
        assertEquals(TrackerStartupGate.WAITING_MESSAGE, TrackerStartupGate.awaitTeamAccess(20L))
    }

    @Test fun unreachableTrackerRefusesTeamAccessOnEveryServer() {
        // No CalTopo Desktop exception: the Tracker regulates org team access everywhere.
        TrackerStartupGate.set(TrackerStartupGate.State.UNREACHABLE)
        assertEquals(TrackerStartupGate.UNREACHABLE_MESSAGE, TrackerStartupGate.awaitTeamAccess(10L))
        assertTrue(TrackerStartupGate.teamAccessRefused())
        assertTrue(TrackerStartupGate.UNREACHABLE_MESSAGE.contains("personal CalTopo account"))
    }

    @Test fun rejectedDeviceIsHandledLikeADownTracker() {
        TrackerStartupGate.set(TrackerStartupGate.State.REJECTED)
        assertEquals(TrackerStartupGate.REJECTED_MESSAGE, TrackerStartupGate.awaitTeamAccess(10L))
        assertTrue(TrackerStartupGate.teamAccessRefused())
    }

    @Test fun nonOrganizationDevicesAreNeverGated() {
        TrackerStartupGate.set(TrackerStartupGate.State.NOT_REQUIRED)
        assertNull(TrackerStartupGate.awaitTeamAccess(10L))
        assertFalse(TrackerStartupGate.teamAccessRefused())
    }

    @Test fun teamAccessReturnsOnlyAfterASuccessfulAuthorization() {
        val unreachable = ManagedConfigFetchOutcome.Unreachable("timeout")
        var state = TrackerStartupSync.nextGateState(TrackerStartupGate.State.PENDING, unreachable)
        assertEquals(TrackerStartupGate.State.UNREACHABLE, state)
        state = TrackerStartupSync.nextGateState(state, unreachable)
        assertEquals(TrackerStartupGate.State.UNREACHABLE, state)
        state = TrackerStartupSync.nextGateState(state, ManagedConfigFetchOutcome.CheckedIn)
        assertEquals(TrackerStartupGate.State.CHECKED_IN, state)
        // The Tracker withdrawing authorization revokes team access mid-session.
        state = TrackerStartupSync.nextGateState(state, ManagedConfigFetchOutcome.Rejected)
        assertEquals(TrackerStartupGate.State.REJECTED, state)
        state = TrackerStartupSync.nextGateState(state, unreachable)
        assertEquals(TrackerStartupGate.State.UNREACHABLE, state)
    }

    @Test fun personalAccountRequestsAreNeverGated() {
        // Only team-signed operations (not naked, no personal authorization) reach the gate.
        val session = source("data/CaltopoSession.java")
        val guard = session.indexOf("if (!op.goNaked && op.personalAuthorization == null) {\n            // Team-signed")
        assertTrue(guard >= 0)
        assertTrue(session.indexOf("TrackerStartupGate.awaitTeamAccess()") > guard)
        assertFalse(source("data/TrackerStartupGate.kt").contains("isPublicCaltopo"))
    }

    @Test fun refusedTeamAccessOpensPersonalCredentialsSignIn() {
        val viewModel = source("ui/R2CViewModel.kt")
        val open = viewModel.substring(viewModel.indexOf("fun openPersonalBecauseTeamRefused()"))
        assertTrue(open.contains("CaltopoPersonalLoginMode.CREDENTIALS"))
        assertTrue(open.contains("loadStoredPersonalCatalog()"))
        assertTrue(viewModel.contains("gate.teamAccessRefused() -> openPersonalBecauseTeamRefused()"))
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
        assertTrue(source("data/CaltopoMap.java").contains("TrackerStartupGate.teamAccessBlockReason()"))
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

/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Startup r2c-tracker check-in for organization devices.
 *
 * Runs on its own worker as soon as the process starts, without waiting for a CalTopo
 * map. It refreshes the team CalTopo credentials and the organization configuration,
 * including Drone Ownership, and then opens [TrackerStartupGate]. The UI never waits.
 */
object TrackerStartupSync {
    private const val TAG = "TrackerStartupSync"

    /** Waits before attempts 2 and 3. A cold Tracker usually answers within one call timeout. */
    @JvmField val ATTEMPT_BACKOFF_MS = longArrayOf(3_000L, 8_000L)
    const val BACKGROUND_RETRY_FIRST_MS = 30_000L
    const val BACKGROUND_RETRY_MAX_MS = 300_000L

    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Tracker startup check-in").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)
    @Volatile private var retryTask: ScheduledFuture<*>? = null
    @Volatile private var nextRetryMs = BACKGROUND_RETRY_FIRST_MS

    /**
     * Retry loop shared by startup and background retries. A rejection stops at once;
     * an unreachable Tracker is tried `ATTEMPT_BACKOFF_MS.size + 1` times.
     */
    @JvmStatic
    fun checkInWithRetries(
        fetch: (attempt: Int) -> ManagedConfigFetchOutcome,
        sleep: (Long) -> Unit,
        backoffMs: LongArray = ATTEMPT_BACKOFF_MS
    ): ManagedConfigFetchOutcome {
        var last: ManagedConfigFetchOutcome = ManagedConfigFetchOutcome.Unreachable("not attempted")
        for (attempt in 1..(backoffMs.size + 1)) {
            last = fetch(attempt)
            if (last !is ManagedConfigFetchOutcome.Unreachable) return last
            if (attempt <= backoffMs.size) sleep(backoffMs[attempt - 1])
        }
        return last
    }

    @JvmStatic
    fun gateStateFor(outcome: ManagedConfigFetchOutcome): TrackerStartupGate.State = when (outcome) {
        ManagedConfigFetchOutcome.CheckedIn -> TrackerStartupGate.State.CHECKED_IN
        ManagedConfigFetchOutcome.Rejected -> TrackerStartupGate.State.REJECTED
        is ManagedConfigFetchOutcome.Unreachable -> TrackerStartupGate.State.UNREACHABLE
    }

    /**
     * Team access follows the Tracker's answer. A rejection revokes it. A later refresh that
     * only fails to reach the Tracker is not a refusal, so an authorization already given in
     * this process stays in place; unreachable before any authorization refuses team access.
     */
    @JvmStatic
    fun nextGateState(
        previous: TrackerStartupGate.State,
        outcome: ManagedConfigFetchOutcome
    ): TrackerStartupGate.State =
        if (previous == TrackerStartupGate.State.CHECKED_IN && outcome is ManagedConfigFetchOutcome.Unreachable)
            TrackerStartupGate.State.CHECKED_IN
        else gateStateFor(outcome)

    /** Called on the settings worker during process start. Never blocks the caller. */
    @JvmStatic
    fun start(context: Context) {
        val app = context.applicationContext
        worker.execute { runCheckIn(app, reason = "startup") }
    }

    /** Network became available. Retries a failed check-in, or refreshes a completed one. */
    @JvmStatic
    fun onNetworkAvailable(context: Context) {
        val app = context.applicationContext
        worker.execute { runCheckIn(app, reason = "network available") }
    }

    /** After Tracker reauthentication or re-enrollment. */
    @JvmStatic
    fun retryNow(context: Context, reason: String) {
        val app = context.applicationContext
        worker.execute { runCheckIn(app, reason = reason) }
    }

    private fun runCheckIn(context: Context, reason: String) {
        if (!inFlight.compareAndSet(false, true)) {
            CaltopoClient.CTDebug(TAG, "Tracker check-in already running; $reason request joined it.")
            return
        }
        try {
            val trackerBaseUrl = CaltopoClient.GetHomeTrackerUrlPfx().trim()
            val deviceToken = CaltopoClient.GetHomeTrackerApiKey().trim()
            if (trackerBaseUrl.isBlank() || deviceToken.isBlank()) {
                CaltopoClient.CTDebug(TAG, "Not enrolled with an organization Tracker; team CalTopo access is not gated.")
                cancelBackgroundRetry()
                TrackerStartupGate.set(TrackerStartupGate.State.NOT_REQUIRED)
                return
            }
            val endpoint = "${trackerBaseUrl.trimEnd('/')}/api/v1/organization-config/current"
            val startedAt = System.currentTimeMillis()
            CaltopoClient.CTInfo(TAG, "Tracker check-in started ($reason).")
            val outcome = checkInWithRetries(
                fetch = { attempt ->
                    val result = OrgConfigManager.fetchAndApplyManagedConfiguration(
                        context, endpoint, deviceToken, TrackerHttp.client
                    )
                    if (result is ManagedConfigFetchOutcome.Unreachable) {
                        CaltopoClient.CTWarn(TAG, "Tracker check-in attempt $attempt failed: ${result.reason}")
                    }
                    result
                },
                sleep = { Thread.sleep(it) }
            )
            val state = nextGateState(TrackerStartupGate.state(), outcome)
            CaltopoClient.CTInfo(
                TAG,
                "Tracker check-in finished ($reason) state=$state elapsedMs=${System.currentTimeMillis() - startedAt}"
            )
            TrackerStartupGate.set(state)
            if (state == TrackerStartupGate.State.UNREACHABLE) scheduleBackgroundRetry(context)
            else cancelBackgroundRetry()
        } catch (error: Exception) {
            CaltopoClient.CTWarn(TAG, "Tracker check-in raised", error)
            val state = nextGateState(TrackerStartupGate.state(), ManagedConfigFetchOutcome.Unreachable("exception"))
            TrackerStartupGate.set(state)
            if (state == TrackerStartupGate.State.UNREACHABLE) scheduleBackgroundRetry(context)
        } finally {
            inFlight.set(false)
        }
    }

    private fun scheduleBackgroundRetry(context: Context) {
        if (retryTask?.isDone == false) return
        val delay = nextRetryMs
        nextRetryMs = minOf(nextRetryMs * 2, BACKGROUND_RETRY_MAX_MS)
        CaltopoClient.CTInfo(TAG, "Tracker unreachable; team CalTopo access refused (personal account only). Retrying in ${delay / 1000}s.")
        retryTask = worker.schedule({
            retryTask = null
            if (!CaltopoClient.IsExitRequested()) runCheckIn(context, reason = "background retry")
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun cancelBackgroundRetry() {
        retryTask?.cancel(false)
        retryTask = null
        nextRetryMs = BACKGROUND_RETRY_FIRST_MS
    }
}

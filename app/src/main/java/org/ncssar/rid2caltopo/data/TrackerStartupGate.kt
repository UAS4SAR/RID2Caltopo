/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Team CalTopo access waits for this process's first r2c-tracker check-in.
 *
 * An organization device must confirm its team credentials and pull the current
 * organization configuration before it signs a CalTopo request with the team
 * credential. Personal CalTopo access and every live function start immediately.
 *
 * - [State.PENDING]: the startup check-in has not finished. Team requests on a worker
 *   wait for it; the main thread never waits.
 * - [State.CHECKED_IN] / [State.NOT_REQUIRED]: team access is allowed.
 * - [State.UNREACHABLE]: the check-in failed after its retries. Team access to
 *   caltopo.com is refused, so CalTopo access uses the personal account. A CalTopo
 *   Desktop server on another host keeps team access, because it serves an offline
 *   incident network that cannot reach r2c-tracker.com. The check-in keeps retrying in
 *   the background and switches to [State.CHECKED_IN] when the Tracker answers.
 * - [State.REJECTED]: the Tracker refused this device. Team access to caltopo.com stays
 *   refused until the device signs in again or is re-enrolled.
 */
object TrackerStartupGate {
    enum class State { NOT_REQUIRED, PENDING, CHECKED_IN, UNREACHABLE, REJECTED }

    const val WAITING_MESSAGE =
        "Checking in with the organization Tracker before using the team CalTopo account…"
    const val UNREACHABLE_MESSAGE =
        "The organization Tracker is unreachable, so team CalTopo maps are unavailable. " +
            "Use your personal CalTopo account. The app keeps retrying the Tracker."
    const val REJECTED_MESSAGE =
        "The organization Tracker did not accept this device. Sign in to the Tracker again " +
            "before using team CalTopo maps. Your personal CalTopo account still works."

    /** Longest a worker waits for the check-in. The check-in itself gives up sooner. */
    const val MAX_WAIT_MS = 180_000L

    private val lock = Object()
    @Volatile private var current = State.NOT_REQUIRED
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @JvmStatic fun state(): State = current

    /** Process start, before anything can open a CalTopo session. Memory only. */
    @JvmStatic fun beginStartupCheckIn() = set(State.PENDING)

    @JvmStatic fun set(next: State) {
        val changed: Boolean
        synchronized(lock) {
            changed = current != next
            current = next
            lock.notifyAll()
        }
        if (changed) listeners.forEach { listener -> runCatching { listener(next) } }
    }

    @JvmStatic fun addListener(listener: (State) -> Unit) { listeners.add(listener) }
    @JvmStatic fun removeListener(listener: (State) -> Unit) { listeners.remove(listener) }

    /** Runs [action] once the check-in has an outcome. Runs now if it already has one. */
    @JvmStatic fun runWhenDecided(action: Runnable) {
        if (current != State.PENDING) { action.run(); return }
        lateinit var listener: (State) -> Unit
        listener = { state ->
            if (state != State.PENDING && listeners.remove(listener)) action.run()
        }
        listeners.add(listener)
        if (current != State.PENDING && listeners.remove(listener)) action.run()
    }

    @JvmStatic fun isPublicCaltopo(domainAndPort: String?): Boolean {
        val host = domainAndPort.orEmpty().trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        return host.isEmpty() || host == "caltopo.com" || host.endsWith(".caltopo.com")
    }

    /** Null when team access may proceed; otherwise the reason it may not. */
    @JvmStatic fun blockReason(state: State, domainAndPort: String?): String? = when (state) {
        State.NOT_REQUIRED, State.CHECKED_IN -> null
        State.PENDING -> WAITING_MESSAGE
        State.UNREACHABLE -> if (isPublicCaltopo(domainAndPort)) UNREACHABLE_MESSAGE else null
        State.REJECTED -> if (isPublicCaltopo(domainAndPort)) REJECTED_MESSAGE else null
    }

    @JvmStatic fun teamAccessBlockReason(domainAndPort: String?): String? = blockReason(current, domainAndPort)

    /** True when team access is refused for an outcome, not merely waiting for one. */
    @JvmStatic fun teamAccessRefused(domainAndPort: String?): Boolean =
        current != State.PENDING && blockReason(current, domainAndPort) != null

    /**
     * Worker-thread wait for team access. Returns null when the team request may be sent.
     * On the main thread this never waits and reports the current reason instead.
     */
    @JvmStatic @JvmOverloads
    fun awaitTeamAccess(domainAndPort: String?, maxWaitMs: Long = MAX_WAIT_MS): String? {
        val onMain = runCatching { Looper.myLooper() == Looper.getMainLooper() && Looper.myLooper() != null }
            .getOrDefault(false)
        if (!onMain) {
            val deadline = System.currentTimeMillis() + maxWaitMs
            synchronized(lock) {
                while (current == State.PENDING) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    try { lock.wait(remaining) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); break
                    }
                }
            }
        }
        return blockReason(current, domainAndPort)
    }

    /** Tests only. */
    @JvmStatic fun resetForTesting() {
        listeners.clear()
        synchronized(lock) { current = State.NOT_REQUIRED; lock.notifyAll() }
    }
}

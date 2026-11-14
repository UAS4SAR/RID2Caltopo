/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Team CalTopo access for an organization device is allowed only after r2c-tracker has
 * authorized the device in this process.
 *
 * Only team-credential access is regulated. Personal-account CalTopo access is never
 * gated, on any server. A device that is not registered with an organization is never
 * gated and keeps using its locally configured team credentials.
 *
 * - [State.NOT_REQUIRED]: not an organization device. Team access is allowed.
 * - [State.PENDING]: the startup check-in has not finished. Team requests on a worker wait
 *   for it (bounded); the main thread never waits. Team access is not yet allowed.
 * - [State.CHECKED_IN]: the Tracker authorized this device. Team access is allowed.
 * - [State.UNREACHABLE]: the Tracker could not be reached. Team access is refused on every
 *   server; the check-in keeps retrying in the background.
 * - [State.REJECTED]: the Tracker refused this device (HTTP 401/403). Team access is refused
 *   and retries stop until Tracker sign-in or re-enrollment.
 *
 * Team access returns only after a later successful authorization.
 */
object TrackerStartupGate {
    enum class State { NOT_REQUIRED, PENDING, CHECKED_IN, UNREACHABLE, REJECTED }

    const val WAITING_MESSAGE =
        "Checking in with the organization Tracker before using the team CalTopo account…"
    const val UNREACHABLE_MESSAGE =
        "The organization Tracker could not be reached, so team CalTopo access is unavailable. " +
            "Sign in with your personal CalTopo account. The app keeps retrying the Tracker."
    const val REJECTED_MESSAGE =
        "The organization Tracker has not authorized this device, so team CalTopo access is " +
            "unavailable. Sign in with your personal CalTopo account."

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

    /** Null when team access may proceed; otherwise the reason it may not. */
    @JvmStatic fun blockReason(state: State): String? = when (state) {
        State.NOT_REQUIRED, State.CHECKED_IN -> null
        State.PENDING -> WAITING_MESSAGE
        State.UNREACHABLE -> UNREACHABLE_MESSAGE
        State.REJECTED -> REJECTED_MESSAGE
    }

    @JvmStatic fun teamAccessBlockReason(): String? = blockReason(current)

    /** True when team access is refused for an outcome, not merely waiting for one. */
    @JvmStatic fun teamAccessRefused(): Boolean = current != State.PENDING && blockReason(current) != null

    /**
     * Worker-thread wait for team access. Returns null when the team request may be sent.
     * On the main thread this never waits and reports the current reason instead.
     */
    @JvmStatic @JvmOverloads
    fun awaitTeamAccess(maxWaitMs: Long = MAX_WAIT_MS): String? {
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
        return blockReason(current)
    }

    /** Tests only. */
    @JvmStatic fun resetForTesting() {
        listeners.clear()
        synchronized(lock) { current = State.NOT_REQUIRED; lock.notifyAll() }
    }
}

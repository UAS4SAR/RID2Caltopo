package org.ncssar.rid2caltopo.btrx

import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * Desk Pod button confirmation (firmware 0x0108, btrx ec1d105, PROTOCOL.md "Desk Pod button
 * confirmation"). Pure Kotlin, owned by [BtrxDeskPodModel], which sends the CONFIRM_RSP frames
 * this returns to the Desk Pod's own node. The iPad has the same logic in Swift (R2CCore
 * BtrxButtonConfirm.swift); keep them matching. Not thread-safe: the model's lock guards it.
 *
 * While the panel can be shown (a ready link to a 0x0108+ Desk Pod and the app in front), a
 * keepalive (token 0, answer 3) goes out every [P.CONFIRM_KEEPALIVE_MS], so the Desk Pod asks
 * before a button tap opens its pairing window or a 10 s hold factory resets it. A CONFIRM_REQ
 * ask (state 0) opens the panel and is answered "shown" at once (the Desk Pod acts on its own
 * when nothing answers in 3 s). Confirm / Cancel answer with the same token. The panel closes
 * on state 1 / 2 / 3 for its token, or [P.CONFIRM_TIMEOUT_S] s after the ask. A token this app
 * never saw asked is ignored; so is a repeat of an outcome.
 */
class BtrxButtonConfirm(private val log: BtrxClient.Logger) {

    /** The panel on screen. [answer] is null until Confirm or Cancel was tapped. */
    data class Panel(val action: Int, val token: Int, val askedMs: Long, val timeoutMs: Long, val answer: Int? = null)

    data class View(val action: Int, val token: Int, val secondsLeft: Int, val confirmSent: Boolean) {
        val factoryReset: Boolean get() = action == P.CFM_ACTION_FACTORY_RESET
    }

    /** A brief, non-blocking result line for the map and the Desk Pod pane. */
    data class Notice(val text: String, val atMs: Long, val showMs: Long)

    private class Known(val action: Int, var finished: Boolean = false, var cancelledHere: Boolean = false)

    /** A CONFIRM_REQ's effects: answers to send ((token, answer)), and whether the Desk Pod was just factory reset. */
    data class Result(val answers: List<Pair<Int, Int>> = emptyList(), val deskReset: Boolean = false)

    var panel: Panel? = null
        private set
    var notice: Notice? = null
        private set
    /** A CONFIRM_REQ arrived this session: the Desk Pod has the feature whatever its reported build. */
    var seenThisSession = false
        private set
    private val known = LinkedHashMap<Int, Known>()
    private var lastKeepaliveMs: Long? = null

    fun view(nowMs: Long): View? = panel?.let {
        View(it.action, it.token, (((it.askedMs + it.timeoutMs - nowMs) + 999) / 1000).toInt().coerceAtLeast(0), it.answer == P.CFM_CONFIRM)
    }

    /** The notice while it is still shown on the map (null after its time). */
    fun liveNotice(nowMs: Long): Notice? = notice?.takeIf { nowMs - it.atMs < it.showMs }

    /** A new session: the keepalive starts again at once; a panel from the old link cannot be answered. */
    fun newSession() {
        lastKeepaliveMs = null
        seenThisSession = false
    }

    /** The link dropped: close the panel (the Desk Pod drops or acts on its own). */
    fun linkLost(nowMs: Long, reason: String) {
        lastKeepaliveMs = null
        panel?.let {
            log.warn("desk button: panel for token=${it.token} closed: link lost ($reason)")
            notice = Notice("Desk Pod button: the link to the Desk Pod was lost; nothing was answered.", nowMs, NOTICE_MS)
            panel = null
        }
    }

    /** True when a keepalive is due now (and counts it as sent). [enabled]: link ready, 0x0108+, panel can be shown. */
    fun keepaliveDue(nowMs: Long, enabled: Boolean): Boolean {
        if (!enabled) { lastKeepaliveMs = null; return false }
        val last = lastKeepaliveMs
        if (last != null && nowMs - last < P.CONFIRM_KEEPALIVE_MS) return false
        lastKeepaliveMs = nowMs
        return true
    }

    /** A CONFIRM_REQ from the Desk Pod. [canShow]: the panel host is on screen (app in front). */
    fun onRequest(r: P.ConfirmReq, nowMs: Long, canShow: Boolean): Result {
        if (r.version != P.CONFIRM_VERSION) {
            log.warn("desk button: CONFIRM_REQ version ${r.version} ignored")
            return Result()
        }
        seenThisSession = true
        val what = "action=${P.confirmActionName(r.action)} token=${r.token}"
        if (r.state == P.CFM_ASK) {
            if (r.token == 0) { log.warn("desk button: ask with token 0 ignored"); return Result() }
            val cur = panel
            if (cur != null && cur.token == r.token) {
                // A repeat of the ask on screen: say "shown" again (harmless to the Desk Pod).
                return Result(listOf(r.token to P.CFM_SHOWN))
            }
            if (known[r.token]?.finished == true) return Result()
            remember(r.token, r.action)
            if (!canShow) {
                log.info("desk button: ask $what not shown: the app is not in front; the Desk Pod acts on its own in ${P.CONFIRM_SHOWN_MS / 1000} s")
                return Result()
            }
            if (cur != null) log.info("desk button: ask token=${r.token} replaces token=${cur.token}")
            val timeout = (if (r.timeoutS > 0) r.timeoutS else P.CONFIRM_TIMEOUT_S) * 1000L
            panel = Panel(r.action, r.token, nowMs, timeout)
            log.info("desk button: ask $what timeout_s=${r.timeoutS}: panel shown")
            return Result(listOf(r.token to P.CFM_SHOWN))
        }
        val k = known[r.token]
        if (k == null || k.finished) {
            log.debug("desk button: CONFIRM_REQ $what state=${P.confirmStateName(r.state)} for a token not asked here (or already closed); ignored")
            return Result()
        }
        k.finished = true
        if (panel?.token == r.token) panel = null
        val reset = r.action == P.CFM_ACTION_FACTORY_RESET
        val text = when (r.state) {
            P.CFM_DONE -> if (reset) RESET_DONE else "Desk Pod button: pairing opened (confirmed)."
            P.CFM_DONE_NO_APP -> if (reset) RESET_DONE_NO_ASK else "Desk Pod button: pairing opened."
            P.CFM_DROPPED -> if (k.cancelledHere) "Desk Pod button: cancelled; nothing was changed."
                else "Desk Pod button: request dropped (no answer in time, or replaced); nothing was changed."
            else -> "Desk Pod button: request ended (state ${r.state})."
        }
        log.info("desk button: $what state=${P.confirmStateName(r.state)}: $text")
        val deskReset = reset && (r.state == P.CFM_DONE || r.state == P.CFM_DONE_NO_APP)
        notice = Notice(text, nowMs, if (deskReset) RESET_NOTICE_MS else NOTICE_MS)
        return Result(deskReset = deskReset)
    }

    /** Confirm or Cancel on the panel: the answer to send ((token, answer)), or null when there is nothing to answer. */
    fun answer(confirm: Boolean, nowMs: Long): Pair<Int, Int>? {
        val p = panel ?: return null
        if (p.answer != null) return null
        val a = if (confirm) P.CFM_CONFIRM else P.CFM_CANCEL
        log.info("desk button: ${P.confirmAnswerName(a)} token=${p.token} (${P.confirmActionName(p.action)})")
        if (confirm) {
            panel = p.copy(answer = a) // stays up until the Desk Pod says done (state 1)
        } else {
            known[p.token]?.cancelledHere = true
            panel = null
            notice = Notice("Desk Pod button: cancelled; nothing was changed.", nowMs, NOTICE_MS)
        }
        return p.token to a
    }

    /** The 15 s limit (the Desk Pod drops the request then too). */
    fun tick(nowMs: Long) {
        val p = panel ?: return
        if (nowMs - p.askedMs >= p.timeoutMs) {
            log.info("desk button: token=${p.token} closed after ${p.timeoutMs / 1000} s with no outcome from the Desk Pod")
            panel = null
            notice = Notice(
                if (p.answer == P.CFM_CONFIRM) "Desk Pod button: no word from the Desk Pod after Confirm; check the Desk Pod."
                else "Desk Pod button: no answer in ${p.timeoutMs / 1000} s; nothing was changed.",
                nowMs, NOTICE_MS)
        }
    }

    private fun remember(token: Int, action: Int) {
        known[token] = Known(action)
        while (known.size > KNOWN_MAX) known.remove(known.keys.first())
    }

    companion object {
        const val NOTICE_MS = 8_000L
        const val RESET_NOTICE_MS = 30_000L
        const val KNOWN_MAX = 8
        const val RESET_DONE = "Desk Pod factory reset (confirmed). It comes back unpaired: re-pair the Drone Pod."
        const val RESET_DONE_NO_ASK = "Desk Pod button: factory reset done without asking. It comes back unpaired: re-pair the Drone Pod."
    }
}

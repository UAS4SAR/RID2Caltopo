package org.ncssar.rid2caltopo.btrx

import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * Everything the Desk Pod screen shows, and the "Pair drone pod" flow, independent
 * of the link (USB or Bluetooth) and of Android, so it is unit tested on the JVM.
 * The iPad has the same model in Swift (R2CCore BtrxDeskPodModel); keep them matching.
 *
 * Fed by the current link's [BtrxClient.Listener] callbacks, [setLink] and [tick]
 * (about once a second). Frames are sent through the attached [BtrxFrameSender]
 * outside this object's lock, so a link thread holding the client's lock can never
 * deadlock with a UI action holding this one.
 *
 * Pairing (brief section 6):
 *  1. "Tap the Desk Pod button": wait for DESK_PAIR open=1 (a tap already seen, even
 *     in the connect backlog, counts while its window is open; see [BtrxDeskWindow]).
 *  2. "Nod the drone pod twice": wait for a live PAIR_REQ while the window is open.
 *     PAIR_REQ outside an open window, or in the connect backlog, is never acted on.
 *  3. The operator confirms the pod shown (dev_a / dev_b, imu_ok, RSSI/SNR).
 *  4. PAIR_CONFIRM to that node, nonce echoed, non-zero pod_id ([P.podIdFor]);
 *     answered again if the same PAIR_REQ repeats (up to [MAX_CONFIRM_TRIES]); then
 *     wait up to [ACK_TIMEOUT_MS] for PAIR_ACK and show a_ok / b_ok.
 * DESK_PAIR open=0 or the window running out ends steps 1-3 with a failure. Nothing
 * here ever makes a clue.
 */
class BtrxDeskPodModel(
    private val log: BtrxClient.Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPaired: (PairedPod) -> Unit = {},
) {
    enum class LinkKind { NONE, BLUETOOTH, USB }

    enum class LinkPhase { OFF, UNAVAILABLE, NO_PERMISSION, SCANNING, BONDING, CONNECTING, CONFIGURING, READY, RETRY_WAIT, PAUSED_FOR_USB }

    data class LinkStatus(
        val kind: LinkKind = LinkKind.NONE,
        val phase: LinkPhase = LinkPhase.OFF,
        val detail: String = "",
        val deviceName: String? = null,
    )

    data class PairedPod(val node: Long, val podId: Long, val devA: Long, val devB: Long)

    enum class PairStep { IDLE, WAIT_BUTTON, WAIT_NOD, POD_FOUND, CONFIRMING, PAIRED, FAILED, CANCELLED }

    data class Candidate(
        val node: Long, val assignedPodId: Long, val devA: Long, val devB: Long, val buildA: Int, val buildB: Int,
        val imuOk: Int, val roleA: Int, val roleB: Int, val nonceHex: String, val rssi: Int, val snr: Float,
    ) {
        /** Brief section 6 failure cases: the port board missing or its motion sensor not OK. */
        val warnings: List<String>
            get() = buildList {
                if (devB == 0L || roleB != P.ROLE_PORT) add("The port board did not answer; check the cable between the boards. It can still pair but cannot nod again.")
                if (imuOk == 0) add("The Drone Pod's motion sensor is not OK.")
            }
    }

    data class Pairing(
        val step: PairStep = PairStep.IDLE,
        val message: String = "",
        /** The step that failed, for the per-step result display. */
        val failedAt: PairStep? = null,
        val windowSecondsLeft: Int = 0,
        val candidate: Candidate? = null,
        val aOk: Int? = null,
        val bOk: Int? = null,
        val ackFlags: Int? = null,
        val confirmTries: Int = 0,
    )

    data class HealthReading(val node: Long, val arrivalMs: Long, val rxRssi: Int, val rxSnr: Float, val health: P.Health)

    data class Snapshot(
        val nowMs: Long,
        val link: LinkStatus,
        val bluetoothPausedForUsb: Boolean,
        val localNode: Long?,
        val role: P.RoleReport?,
        val dronePodHealth: HealthReading?,
        /** Other pods heard on the shared channel (not the paired one). */
        val otherPodNodes: Int,
        val detectsLastMinute: Int,
        val lastDetectMs: Long?,
        val lastPacketRssi: Int?,
        val lastPacketSnr: Float?,
        val liveCounts: Map<String, Int>,
        val backlogCounts: Map<String, Int>,
        val windowOpen: Boolean,
        val windowSecondsLeft: Int,
        val windowFromBacklog: Boolean,
        val pairing: Pairing,
        val pairedPod: PairedPod?,
    ) {
        val roleSupported: Boolean get() = role != null && role.buildHash in P.SUPPORTED_BUILDS
    }

    private var sender: BtrxFrameSender? = null
    private var link = LinkStatus()
    private var pausedForUsb = false
    private var localNode: Long? = null
    private var role: P.RoleReport? = null
    private val window = BtrxDeskWindow()
    private var pairing = Pairing()
    private var stepDeadlineMs = 0L
    private var pairedPod: PairedPod? = null
    private var lastHealth: HealthReading? = null
    private val otherPods = mutableSetOf<Long>()
    private val detectTimes = ArrayDeque<Long>()
    private var lastDetectMs: Long? = null
    private var lastRssi: Int? = null
    private var lastSnr: Float? = null
    private val liveCounts = sortedMapOf<String, Int>()
    private val backlogCounts = sortedMapOf<String, Int>()
    private val outbox = ArrayList<Pair<Long, (Int) -> ByteArray>>()

    // ---- link ----

    @Synchronized
    fun setLink(status: LinkStatus, bluetoothPausedForUsb: Boolean = false) {
        link = status
        pausedForUsb = bluetoothPausedForUsb
    }

    /** A new session (new [BtrxClient]) on the current link. Counters restart; the window and the paired pod do not. */
    @Synchronized
    fun attach(sender: BtrxFrameSender) {
        this.sender = sender
        localNode = null
        role = null
        liveCounts.clear(); backlogCounts.clear()
        detectTimes.clear()
    }

    /** The session ended. A pairing in progress fails: its frames would go nowhere. */
    fun detach(sender: BtrxFrameSender, reason: String) {
        synchronized(this) {
            if (this.sender !== sender) return
            this.sender = null
            if (pairing.step in ACTIVE_STEPS) fail(pairing.step, "The link to the Desk Pod was lost ($reason).")
        }
    }

    @Synchronized
    fun restorePairedPod(pod: PairedPod?) { pairedPod = pod }

    @Synchronized
    fun forgetPairedPod() { pairedPod = null; lastHealth = null; otherPods.clear() }

    // ---- client listener ----

    @Synchronized
    fun onConfigured(node: Long) { localNode = node }

    @Synchronized
    fun onLocalRoleReport(report: P.RoleReport) { role = report }

    fun onFrame(frame: P.Frame, meta: BtrxClient.RadioMeta) {
        val sends = synchronized(this) {
            handleFrame(frame, meta)
            takeOutbox()
        }
        deliver(sends)
    }

    private fun handleFrame(frame: P.Frame, meta: BtrxClient.RadioMeta) {
        val counts = if (meta.backlog) backlogCounts else liveCounts
        counts[frame.typeName] = (counts[frame.typeName] ?: 0) + 1
        val fromDesk = localNode == null || meta.fromNode == localNode
        if (!fromDesk) { lastRssi = meta.rxRssi; lastSnr = meta.rxSnr }
        try {
            when (frame.type) {
                P.DESK_PAIR -> if (fromDesk) onDeskPair(frame, meta)
                P.PAIR_REQ -> onPairReq(P.parsePairReq(frame.payload), meta)
                P.PAIR_ACK -> onPairAck(P.parsePairAck(frame.payload), meta)
                P.HEALTH -> onHealth(P.parseHealth(frame.payload), meta)
                P.DETECT -> if (!meta.backlog) {
                    detectTimes.addLast(meta.arrivalMs)
                    lastDetectMs = meta.arrivalMs
                    trimDetects(meta.arrivalMs)
                }
            }
        } catch (_: P.PayloadException) {
            // Logged with decode-error by BtrxFrameLog; nothing to act on.
        }
    }

    private fun onDeskPair(frame: P.Frame, meta: BtrxClient.RadioMeta) {
        val dp = P.parseDeskPair(frame.payload)
        window.onDeskPair(frame.seq, dp, meta.arrivalMs, meta.backlog)
        val now = meta.arrivalMs
        if (dp.open == 1) {
            if (window.openedFromBacklog) log.info("pairing: Desk Pod window open from a tap that arrived with the connect backlog (${window.secondsLeft(now)} s left)")
            // A new tap while waiting for the nod restarts the countdown on the new window.
            if (pairing.step == PairStep.WAIT_BUTTON || pairing.step == PairStep.WAIT_NOD) enterWaitNod(now)
        } else {
            when (pairing.step) {
                PairStep.WAIT_BUTTON -> Unit // an old close; keep waiting for the tap
                PairStep.WAIT_NOD -> fail(PairStep.WAIT_NOD, "The Desk Pod closed its pairing window before a Drone Pod asked to pair. Tap the Desk Pod button and nod the drone pod again.")
                PairStep.POD_FOUND -> fail(PairStep.POD_FOUND, "The Desk Pod closed its pairing window before the pod was confirmed. Tap the Desk Pod button and start again.")
                else -> Unit
            }
        }
    }

    private fun onPairReq(req: P.PairReq, meta: BtrxClient.RadioMeta) {
        val now = meta.arrivalMs
        if (meta.backlog) {
            log.info("pairing: PAIR_REQ from ${BtrxClient.nodeName(meta.fromNode)} in the connect backlog ignored (stale)")
            return
        }
        if (!window.isOpen(now)) {
            log.info("pairing: PAIR_REQ from ${BtrxClient.nodeName(meta.fromNode)} ignored: no Desk Pod window open")
            return
        }
        val nonce = P.hex(req.nonce)
        when (pairing.step) {
            PairStep.WAIT_NOD -> {
                val c = Candidate(
                    meta.fromNode, P.podIdFor(req.devA), req.devA, req.devB, req.buildA, req.buildB,
                    req.imuOk, req.roleA, req.roleB, nonce, meta.rxRssi, meta.rxSnr,
                )
                pairing = pairing.copy(step = PairStep.POD_FOUND, candidate = c, message = "Drone Pod ${BtrxClient.nodeName(c.node)} asked to pair. Confirm it is the pod in your hand.")
                log.info("pairing: PAIR_REQ from ${BtrxClient.nodeName(c.node)} dev_a=%08x dev_b=%08x imu_ok=%d; waiting for the operator".format(c.devA, c.devB, c.imuOk))
            }
            PairStep.POD_FOUND -> {
                val c = pairing.candidate ?: return
                if (meta.fromNode == c.node) {
                    pairing = pairing.copy(candidate = c.copy(nonceHex = nonce, rssi = meta.rxRssi, snr = meta.rxSnr))
                } else {
                    log.info("pairing: another Drone Pod ${BtrxClient.nodeName(meta.fromNode)} also asked to pair; ignored")
                }
            }
            PairStep.CONFIRMING -> {
                val c = pairing.candidate ?: return
                if (meta.fromNode == c.node && nonce == c.nonceHex && pairing.confirmTries < MAX_CONFIRM_TRIES) {
                    pairing = pairing.copy(confirmTries = pairing.confirmTries + 1)
                    log.info("pairing: PAIR_REQ repeated; answering again (try ${pairing.confirmTries})")
                    queueConfirm(c)
                }
            }
            else -> Unit
        }
    }

    private fun onPairAck(ack: P.PairAck, meta: BtrxClient.RadioMeta) {
        if (meta.backlog || pairing.step != PairStep.CONFIRMING) return
        val c = pairing.candidate ?: return
        if (meta.fromNode != c.node) return
        if (ack.aOk == 1) {
            val pod = PairedPod(c.node, c.assignedPodId, c.devA, c.devB)
            pairedPod = pod
            val msg = if (ack.bOk == 1) "Drone Pod paired." else "Paired, but the port board did not answer. Check the cable between the boards."
            pairing = pairing.copy(step = PairStep.PAIRED, aOk = ack.aOk, bOk = ack.bOk, ackFlags = ack.flags, message = msg, failedAt = null)
            log.info("pairing: PAIR_ACK a_ok=${ack.aOk} b_ok=${ack.bOk}; paired ${BtrxClient.nodeName(c.node)} pod_id=%08x".format(c.assignedPodId))
            onPaired(pod)
        } else {
            pairing = pairing.copy(aOk = ack.aOk, bOk = ack.bOk, ackFlags = ack.flags)
            fail(PairStep.CONFIRMING, "The Drone Pod did not take the pairing (a_ok=0, flags=${P.statusFlagNames(ack.flags)}).")
        }
    }

    private fun onHealth(h: P.Health, meta: BtrxClient.RadioMeta) {
        if (meta.fromNode == localNode) return
        val paired = pairedPod
        if (paired != null && meta.fromNode != paired.node) {
            otherPods += meta.fromNode
            return
        }
        lastHealth = HealthReading(meta.fromNode, meta.arrivalMs, meta.rxRssi, meta.rxSnr, h)
    }

    // ---- pairing actions ----

    /** "Pair drone pod". */
    fun startPairing() {
        synchronized(this) {
            val now = clock()
            val s = sender
            if (s == null || !s.isReady) {
                pairing = Pairing(step = PairStep.FAILED, failedAt = PairStep.WAIT_BUTTON, message = "The Desk Pod is not connected.")
                return
            }
            pairing = Pairing(step = PairStep.WAIT_BUTTON, message = "Tap the Desk Pod button.")
            stepDeadlineMs = now + BUTTON_WAIT_MS
            if (window.isOpen(now)) enterWaitNod(now)
        }
    }

    /** The operator confirmed the pod shown. */
    fun confirmPod() {
        val sends = synchronized(this) {
            val now = clock()
            val c = pairing.candidate
            if (pairing.step != PairStep.POD_FOUND || c == null) return
            if (!window.isOpen(now)) {
                fail(PairStep.POD_FOUND, "The pairing window ran out before the pod was confirmed. Tap the Desk Pod button and start again.")
                return
            }
            pairing = pairing.copy(step = PairStep.CONFIRMING, confirmTries = 1, message = "Waiting for the Drone Pod to acknowledge.")
            stepDeadlineMs = now + ACK_TIMEOUT_MS
            log.info("pairing: PAIR_CONFIRM to ${BtrxClient.nodeName(c.node)} nonce=${c.nonceHex} pod_id=%08x".format(c.assignedPodId))
            queueConfirm(c)
            takeOutbox()
        }
        deliver(sends)
    }

    /** "Cancel pairing": ends the flow. Nothing is sent; the pod's own window runs out. */
    @Synchronized
    fun cancelPairing() {
        if (pairing.step in ACTIVE_STEPS) {
            log.info("pairing: cancelled by the operator at ${pairing.step}")
            pairing = pairing.copy(step = PairStep.CANCELLED, message = "Pairing cancelled.", failedAt = null)
        }
    }

    /** Clears a finished result so the screen shows "Pair drone pod" again. */
    @Synchronized
    fun clearPairingResult() {
        if (pairing.step !in ACTIVE_STEPS) pairing = Pairing()
    }

    /** Timeouts; call about once a second. */
    @Synchronized
    fun tick() {
        val now = clock()
        trimDetects(now)
        val open = window.isOpen(now)
        when (pairing.step) {
            PairStep.WAIT_BUTTON -> if (now >= stepDeadlineMs) fail(PairStep.WAIT_BUTTON, "The Desk Pod button was not tapped. Tap it, then start again.")
            PairStep.WAIT_NOD -> if (!open) fail(PairStep.WAIT_NOD, windowEndedMessage("No Drone Pod asked to pair. Nod the drone pod twice within the window."))
            PairStep.POD_FOUND -> if (!open) fail(PairStep.POD_FOUND, windowEndedMessage("The pod was not confirmed in time."))
            PairStep.CONFIRMING -> if (now >= stepDeadlineMs) fail(PairStep.CONFIRMING, "No PAIR_ACK from the Drone Pod within ${ACK_TIMEOUT_MS / 1000} s. Tap the Desk Pod button and try again.")
            else -> Unit
        }
        if (pairing.step in ACTIVE_STEPS) pairing = pairing.copy(windowSecondsLeft = window.secondsLeft(now))
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val now = clock()
        trimDetects(now)
        return Snapshot(
            nowMs = now, link = link, bluetoothPausedForUsb = pausedForUsb, localNode = localNode, role = role,
            dronePodHealth = lastHealth, otherPodNodes = otherPods.size, detectsLastMinute = detectTimes.size,
            lastDetectMs = lastDetectMs, lastPacketRssi = lastRssi, lastPacketSnr = lastSnr,
            liveCounts = HashMap(liveCounts), backlogCounts = HashMap(backlogCounts),
            windowOpen = window.isOpen(now), windowSecondsLeft = window.secondsLeft(now), windowFromBacklog = window.openedFromBacklog,
            pairing = if (pairing.step in ACTIVE_STEPS) pairing.copy(windowSecondsLeft = window.secondsLeft(now)) else pairing,
            pairedPod = pairedPod,
        )
    }

    // ---- internals ----

    private fun enterWaitNod(now: Long) {
        pairing = pairing.copy(step = PairStep.WAIT_NOD, windowSecondsLeft = window.secondsLeft(now), message = "Nod the drone pod twice.")
        log.info("pairing: Desk Pod window open, ${window.secondsLeft(now)} s left; waiting for PAIR_REQ")
    }

    private fun windowEndedMessage(fallback: String): String = when (window.lastClose) {
        BtrxDeskWindow.CloseReason.CLOSED_BY_DESK_POD -> "The Desk Pod closed its pairing window. $fallback"
        else -> "The pairing window ran out. $fallback"
    }

    private fun fail(at: PairStep, message: String) {
        log.warn("pairing failed at $at: $message")
        pairing = pairing.copy(step = PairStep.FAILED, failedAt = at, message = message, windowSecondsLeft = 0)
    }

    private fun queueConfirm(c: Candidate) {
        val nonce = P.fromHex(c.nonceHex)
        outbox += c.node to { seq -> P.pairConfirmFrame(seq, c.assignedPodId, nonce, c.roleA.takeIf { it != 0 } ?: P.ROLE_STARBOARD, c.roleB.takeIf { it != 0 } ?: P.ROLE_PORT) }
    }

    private fun takeOutbox(): List<Pair<Long, (Int) -> ByteArray>> {
        if (outbox.isEmpty()) return emptyList()
        val out = ArrayList(outbox)
        outbox.clear()
        return out
    }

    private fun deliver(sends: List<Pair<Long, (Int) -> ByteArray>>) {
        if (sends.isEmpty()) return
        val s = synchronized(this) { sender } ?: return
        sends.forEach { (to, build) -> s.sendFrame(to, build(s.nextSeq())) }
    }

    private fun trimDetects(now: Long) {
        while (detectTimes.isNotEmpty() && now - detectTimes.first() > DETECT_WINDOW_MS) detectTimes.removeFirst()
    }

    companion object {
        const val BUTTON_WAIT_MS = 120_000L
        const val ACK_TIMEOUT_MS = 30_000L
        const val MAX_CONFIRM_TRIES = 8
        const val DETECT_WINDOW_MS = 60_000L
        val ACTIVE_STEPS = setOf(PairStep.WAIT_BUTTON, PairStep.WAIT_NOD, PairStep.POD_FOUND, PairStep.CONFIRMING)
    }
}

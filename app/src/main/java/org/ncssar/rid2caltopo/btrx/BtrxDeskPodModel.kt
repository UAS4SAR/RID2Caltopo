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
 *  3. The operator confirms the pod shown (dev_a / dev_b, imu_ok, RSSI/SNR). The pod
 *     only takes PAIR_CONFIRM inside its own 60 s nod window, while it repeats PAIR_REQ
 *     every 2 s; the Desk Pod's window is advisory here. No PAIR_REQ for
 *     [POD_REQUEST_STALE_MS] marks the pod stale and asks for another nod. Only the
 *     same pod can revive a stale candidate; a different pod needs a new Desk Pod tap
 *     (a new DESK_PAIR open=1 goes back to step 2). A stale pod ends the flow once the
 *     Desk Pod window has closed or [POD_FOUND_MAX_MS] after it was found.
 *  4. PAIR_CONFIRM to that node, nonce echoed, non-zero pod_id ([P.podIdFor]);
 *     answered again if the same PAIR_REQ repeats (up to [MAX_CONFIRM_TRIES]); then
 *     wait up to [ACK_TIMEOUT_MS] for PAIR_ACK and show a_ok / b_ok.
 * DESK_PAIR open=0 or the window running out ends steps 1-2 with a failure.
 *
 * Pod traffic (HEALTH, DETECT, RSSI/SNR) counts only when live: backlog frames can be
 * minutes old. Once a pod is paired, only its node is accepted (the Desk Pod does not
 * filter by pod_id); a non-zero header pod_id other than the paired one is refused,
 * counted and shown ("re-pair"). No HEALTH from the paired pod for [PAIRED_QUIET_MS]
 * is shown too: an unconfirmed pod (pod_id reset after a 10 s hold) stops transmitting.
 *
 * Steering (design doc section 41): live STEER frames from the paired pod (by node,
 * pod_id 0 or the paired one, like other pod traffic) feed [BtrxSteering]; the stream
 * view overlay shows only while the link is connected, a pod is paired and STEER keeps
 * arriving. [muteSteeringSource] / [clearSteeringMutes] send STEER_MUTE to that pod.
 * Nothing here ever makes a clue.
 *
 * Re-pair (firmware 0x0106): a paired pod ignores nods (motor vibration read as nods at
 * takeoff). [repairDronePod] sends PAIR_OPEN to the paired pod and DESK_PAIR to the Desk Pod
 * (its phone-link bench-test path opens the Desk Pod window), then runs steps 2-4 above with
 * only the paired node accepted. No confirm leaves the pod paired as it was.
 *
 * Own aircraft (saved) (firmware 0x0106): STEER_MUTE op 3 / 4 by src_id puts the address on
 * (takes it off) the pod's saved ignore list; FILTER_ACK answers. [savedIgnores] is this
 * app's record of what the pod took (per paired pod, persisted by the caller).
 *
 * IMU calibration (design doc section 29.3, firmware 0x0102): [BtrxImuCal] runs the flow;
 * IMU_CAL goes to the paired pod's node like STEER_MUTE, and only that pod's live
 * IMU_CAL_ACK (same filter as other pod traffic) answers it. STEER bit 6 feeds "Calibrated".
 */
class BtrxDeskPodModel(
    private val log: BtrxClient.Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPaired: (PairedPod) -> Unit = {},
    /** The saved-ignore src ids changed (persist them with the paired pod). */
    private val onSavedIgnores: (List<Int>) -> Unit = {},
) {
    enum class LinkKind { NONE, BLUETOOTH, USB }

    enum class LinkPhase { OFF, UNAVAILABLE, NO_PERMISSION, SCANNING, BONDING, CONNECTING, CONFIGURING, READY, RETRY_WAIT, PAUSED_FOR_USB }

    data class LinkStatus(
        val kind: LinkKind = LinkKind.NONE,
        val phase: LinkPhase = LinkPhase.OFF,
        val detail: String = "",
        val deviceName: String? = null,
        /** Bluetooth: connect attempts that failed in a row (0 once a session reaches READY). */
        val failedAttempts: Int = 0,
        /** Bluetooth: why the latest of those attempts failed, e.g. "connection failed (status 147)". */
        val lastFailure: String? = null,
    )

    data class PairedPod(val node: Long, val podId: Long, val devA: Long, val devB: Long)

    enum class PairStep { IDLE, WAIT_BUTTON, WAIT_NOD, POD_FOUND, CONFIRMING, PAIRED, FAILED, CANCELLED }

    data class Candidate(
        val node: Long, val assignedPodId: Long, val devA: Long, val devB: Long, val buildA: Int, val buildB: Int,
        val imuOk: Int, val roleA: Int, val roleB: Int, val nonceHex: String, val rssi: Int, val snr: Float,
        /** Arrival of the latest PAIR_REQ from this pod (they repeat every 2 s inside its nod window). */
        val lastRequestMs: Long = 0,
        /** Arrival of the PAIR_REQ that made this the candidate. */
        val foundMs: Long = lastRequestMs,
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
        /** The pod stopped repeating PAIR_REQ: its own window has probably closed. Nod again. */
        val podStale: Boolean = false,
        /** "Re-pair Drone Pod": only the paired node may answer; a failure leaves it paired as it was. */
        val repair: Boolean = false,
        /** Re-pair of a pod older than 0x0106 (no PAIR_OPEN): it opens its window on a nod. */
        val repairByNod: Boolean = false,
    )

    /** A STEER_MUTE op 3 / 4 waiting for its FILTER_ACK. */
    data class PendingFilter(val op: Int, val srcId: Int, val sentMs: Long)

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
        /** Live frames from the paired node carrying an unexpected non-zero pod_id (refused). */
        val pairedPodIdMismatches: Int = 0,
        val lastMismatchPodId: Long? = null,
        /** Paired, connected, and no HEALTH from the paired pod for over [PAIRED_QUIET_MS]. */
        val pairedPodQuiet: Boolean = false,
        /** The stream-view steering overlay, or null when it must not show. */
        val steerOverlay: BtrxSteering.View? = null,
        /** The latest STEER whatever the link state, for the Desk Pod screen's bench line. */
        val lastSteer: BtrxSteering.View? = null,
        /** Debug: roll correction of the steering hint (off until the bench roll check). */
        val steeringRollCorrection: Boolean = BtrxSteering.ROLL_CORRECTION_DEFAULT,
        /** The Drone Pod IMU calibration flow (Desk Pod screen). */
        val imuCal: BtrxImuCal.View? = null,
        /** phone_lost from the Desk Pod's own HEALTH (build 0x0104); null before one or on older builds. */
        val deskPhoneLost: Long? = null,
        /** This session: packets dropped before btrx decoding, encrypted for another channel. */
        val droppedEncrypted: Int = 0,
        /** This session: packets dropped before btrx decoding, another port (not 256). */
        val droppedOtherPort: Int = 0,
        /** The Desk Pod's own HEALTH fields from build 0x0106 (reset reason etc.), or null. */
        val deskHealthExt: P.HealthExt? = null,
        /** Paired pod build 0x0106+: "Own aircraft (saved)" is offered; else the local toggle only. */
        val savedIgnoreSupported: Boolean = false,
        /** src ids the paired pod confirmed on its saved ignore list (FILTER_ACK ok=1). */
        val savedIgnores: List<Int> = emptyList(),
        /** An op 3 / 4 waiting for FILTER_ACK. */
        val filterPending: PendingFilter? = null,
        /** The latest saved-ignore result, for the BTRX pane. */
        val filterMessage: String? = null,
    ) {
        val roleSupported: Boolean get() = role != null && P.buildSupported(role.buildHash)
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
    private var deskPhoneLost: Long? = null
    private var deskHealthExt: P.HealthExt? = null
    private val savedIgnores = sortedSetOf<Int>()
    private var pendingFilter: PendingFilter? = null
    private var filterMessage: String? = null
    /** The Desk Pod's own latest live HEALTH this session: uptime_s and arrival (logged when the link drops). */
    private var deskUptimeS: Long? = null
    private var deskHealthAtMs = 0L
    private var droppedEncrypted = 0
    private var droppedOtherPort = 0
    private val otherPods = mutableSetOf<Long>()
    private val detectTimes = ArrayDeque<Long>()
    private var lastDetectMs: Long? = null
    private var lastRssi: Int? = null
    private var lastSnr: Float? = null
    private val liveCounts = sortedMapOf<String, Int>()
    private val backlogCounts = sortedMapOf<String, Int>()
    private val outbox = ArrayList<Pair<Long, (Int) -> ByteArray>>()
    private var podIdMismatches = 0
    private var lastMismatchPodId: Long? = null
    /** Start of the current wait for the paired pod's HEALTH (session start, pairing, restore). */
    private var quietWatchSinceMs = 0L
    private val steering = BtrxSteering()
    private val imuCal = BtrxImuCal()

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
        deskPhoneLost = null
        deskHealthExt = null
        deskUptimeS = null; deskHealthAtMs = 0L
        droppedEncrypted = 0; droppedOtherPort = 0
        liveCounts.clear(); backlogCounts.clear()
        detectTimes.clear()
        quietWatchSinceMs = clock()
    }

    /** The session ended. A pairing in progress fails: its frames would go nowhere. */
    fun detach(sender: BtrxFrameSender, reason: String) {
        synchronized(this) {
            if (this.sender !== sender) return
            this.sender = null
            log.debug(linkDropLine(reason, clock()))
            if (pairing.step in ACTIVE_STEPS) fail(pairing.step, "The link to the Desk Pod was lost ($reason).")
            pendingFilter?.let { filterMessage = "No answer from the Drone Pod: the link to the Desk Pod was lost." }
            pendingFilter = null
            imuCal.linkLost(reason)
        }
    }

    /**
     * Logged when a session ends (Nov 16: tell a Desk Pod reboot from a radio drop): the
     * Desk Pod's and the Drone Pod's last HEALTH uptime and age. Same text on the iPad.
     */
    private fun linkDropLine(reason: String, nowMs: Long): String {
        val desk = deskUptimeS?.let { "Desk Pod last HEALTH uptime_s=$it, ${(nowMs - deskHealthAtMs) / 1000} s ago" }
            ?: "no Desk Pod HEALTH this session"
        val pod = lastHealth?.let { "Drone Pod last HEALTH uptime_s=${it.health.uptimeS}, ${(nowMs - it.arrivalMs) / 1000} s ago" }
            ?: "no Drone Pod HEALTH"
        return "link drop ($reason): $desk; $pod"
    }

    @Synchronized
    fun restorePairedPod(pod: PairedPod?, savedIgnoreIds: Collection<Int> = emptyList()) {
        if (pod != pairedPod) imuCal.reset() // another pod: its calibration state is not known
        pairedPod = pod
        savedIgnores.clear()
        if (pod != null) savedIgnores += savedIgnoreIds.filter { it in 1..0xFFFF }
        pendingFilter = null; filterMessage = null
        quietWatchSinceMs = clock()
    }

    /**
     * "Replace Drone Pod" (after the operator confirmed): unpair the current pod in this app,
     * then start pairing. Pod side (firmware 0x0102, btrx af2549e): there is no unpair message.
     * A pod drops its pairing only on PAIR_CONFIRM with confirm=0, which it takes only inside
     * its own nod-opened pair window with that window's nonce, or on a factory reset. So the
     * old pod stays confirmed with its pod_id and keeps sending; this app ignores it from now on,
     * and pairing the same pod again simply gives it a new pod_id.
     */
    fun replacePairedPod() {
        synchronized(this) {
            val old = pairedPod
            forgetPairedPod()
            if (old != null) {
                log.debug("pairing: Drone Pod ${BtrxClient.nodeName(old.node)} pod_id=%08x unpaired in this app to replace it; ".format(old.podId) +
                    "the pod keeps its own pairing until it is paired again or factory reset")
            }
        }
        startPairing()
    }

    @Synchronized
    fun forgetPairedPod() {
        if (savedIgnores.isNotEmpty()) {
            // The old pod keeps its list in flash; this app no longer knows it is paired to it.
            log.debug("own aircraft (saved): forgetting ${savedIgnores.size} saved id(s) with the unpaired pod")
            savedIgnores.clear(); onSavedIgnores(emptyList())
        }
        pendingFilter = null; filterMessage = null
        pairedPod = null; lastHealth = null; otherPods.clear()
        podIdMismatches = 0; lastMismatchPodId = null
        steering.reset()
        imuCal.reset()
    }

    // ---- client listener ----

    @Synchronized
    fun onConfigured(node: Long) { localNode = node }

    /** A non-btrx packet the client dropped (encrypted for another channel, or another port). */
    @Synchronized
    fun onPacketDropped(encrypted: Boolean) { if (encrypted) droppedEncrypted++ else droppedOtherPort++ }

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
        try {
            when (frame.type) {
                P.DESK_PAIR -> if (fromDesk) onDeskPair(frame, meta)
                // Pairing frames come from whichever pod is being paired, so they are not filtered.
                P.PAIR_REQ -> onPairReq(P.parsePairReq(frame.payload), meta)
                P.PAIR_ACK -> onPairAck(P.parsePairAck(frame.payload), meta)
                // The Desk Pod's own HEALTH (sent to its app from build 0x0104): only phone_lost is shown.
                P.HEALTH -> if (localNode != null && meta.fromNode == localNode) {
                    if (!meta.backlog) {
                        val h = P.parseHealth(frame.payload)
                        h.phoneLost?.let { deskPhoneLost = it }
                        h.ext?.let { deskHealthExt = it }
                        deskUptimeS = h.uptimeS
                        deskHealthAtMs = meta.arrivalMs
                    }
                } else if (acceptsPodTraffic(frame, meta)) onPodTraffic(frame, meta)
                else -> if (meta.fromNode != localNode && acceptsPodTraffic(frame, meta)) onPodTraffic(frame, meta)
            }
        } catch (_: P.PayloadException) {
            // Logged with decode-error by BtrxFrameLog; nothing to act on.
        }
    }

    /** Live frames only; once paired, only the paired node (pod_id 0 or the paired one). */
    private fun acceptsPodTraffic(frame: P.Frame, meta: BtrxClient.RadioMeta): Boolean {
        if (meta.backlog) return false
        val paired = pairedPod ?: return true
        if (meta.fromNode != paired.node) {
            otherPods += meta.fromNode
            return false
        }
        // Frames queued before pairing still carry pod_id 0 from the right node; accept by node.
        if (frame.podId == 0L || frame.podId == paired.podId) return true
        podIdMismatches++
        if (frame.podId != lastMismatchPodId || podIdMismatches % MISMATCH_LOG_EVERY == 0) {
            log.warn("paired node ${BtrxClient.nodeName(paired.node)} sending pod_id %08x (paired as %08x, %d frame(s) refused); re-pair"
                .format(frame.podId, paired.podId, podIdMismatches))
        }
        lastMismatchPodId = frame.podId
        return false
    }

    private fun onPodTraffic(frame: P.Frame, meta: BtrxClient.RadioMeta) {
        lastRssi = meta.rxRssi; lastSnr = meta.rxSnr
        when (frame.type) {
            P.HEALTH -> lastHealth = HealthReading(meta.fromNode, meta.arrivalMs, meta.rxRssi, meta.rxSnr, P.parseHealth(frame.payload))
            P.DETECT -> {
                detectTimes.addLast(meta.arrivalMs)
                lastDetectMs = meta.arrivalMs
                trimDetects(meta.arrivalMs)
            }
            // The pod sends STEER only once confirmed; before pairing it is not ours to steer by.
            P.STEER -> if (pairedPod != null) {
                val st = P.parseSteer(frame.payload)
                if (steering.onSteer(st, meta.arrivalMs)) {
                    log.info("steering: window_end_ms went back to ${st.windowEndMs}: the Drone Pod restarted; src ids, colors and mutes reset")
                }
                imuCal.onSteer(st.imuOk, st.imuCal)
            }
            P.FILTER_ACK -> if (pairedPod != null) onFilterAck(P.parseFilterAck(frame.payload), frame.flags)
            // Only from the paired pod: an answer before pairing is not ours.
            P.IMU_CAL_ACK -> if (pairedPod != null) {
                val ack = P.parseImuCalAck(frame.payload)
                if (imuCal.onAck(ack)) {
                    log.debug("imu cal: ${P.imuCalOpName(ack.op)} tag=${ack.tag} answered: ${P.imuCalStatusName(ack.status)}")
                } else {
                    log.debug("imu cal: IMU_CAL_ACK tag=${ack.tag} op=${P.imuCalOpName(ack.op)} matches no request out; ignored")
                }
            }
        }
    }

    private fun onDeskPair(frame: P.Frame, meta: BtrxClient.RadioMeta) {
        val dp = P.parseDeskPair(frame.payload)
        window.onDeskPair(frame.seq, dp, meta.arrivalMs, meta.backlog)
        val now = meta.arrivalMs
        if (dp.open == 1) {
            if (window.openedFromBacklog) log.debug("pairing: Desk Pod window open from a tap that arrived with the connect backlog (${window.secondsLeft(now)} s left)")
            // A new tap while waiting for the nod restarts the countdown on the new window.
            if (pairing.step == PairStep.WAIT_BUTTON || pairing.step == PairStep.WAIT_NOD) enterWaitNod(now)
            // A live tap while the found pod is stale starts over at the nod (any pod may answer).
            else if (pairing.step == PairStep.POD_FOUND && pairing.podStale && !meta.backlog) {
                log.debug("pairing: new Desk Pod tap while ${pairing.candidate?.let { BtrxClient.nodeName(it.node) }} is stale; waiting for a nod")
                pairing = pairing.copy(candidate = null, podStale = false)
                enterWaitNod(now)
            }
        } else {
            when (pairing.step) {
                PairStep.WAIT_BUTTON -> Unit // an old close; keep waiting for the tap
                PairStep.WAIT_NOD -> fail(PairStep.WAIT_NOD, "The Desk Pod closed its pairing window before a Drone Pod asked to pair. Tap the Desk Pod button and nod the drone pod again.")
                // POD_FOUND: the pod's own window decides (see onPairReq / tick); the Desk Pod's is advisory.
                else -> Unit
            }
        }
    }

    private fun onPairReq(req: P.PairReq, meta: BtrxClient.RadioMeta) {
        val now = meta.arrivalMs
        if (meta.backlog) {
            log.debug("pairing: PAIR_REQ from ${BtrxClient.nodeName(meta.fromNode)} in the connect backlog ignored (stale)")
            return
        }
        val nonce = P.hex(req.nonce)
        val open = window.isOpen(now)
        fun newCandidate(): Boolean {
            val paired = pairedPod
            if (pairing.repair && paired != null && meta.fromNode != paired.node) {
                log.debug("pairing: PAIR_REQ from ${BtrxClient.nodeName(meta.fromNode)} ignored: re-pairing ${BtrxClient.nodeName(paired.node)} only")
                return false
            }
            if (!open) {
                log.debug("pairing: PAIR_REQ from ${BtrxClient.nodeName(meta.fromNode)} ignored: no Desk Pod window open")
                return false
            }
            val c = Candidate(
                meta.fromNode, P.podIdFor(req.devA), req.devA, req.devB, req.buildA, req.buildB,
                req.imuOk, req.roleA, req.roleB, nonce, meta.rxRssi, meta.rxSnr, lastRequestMs = now,
            )
            pairing = pairing.copy(step = PairStep.POD_FOUND, candidate = c, podStale = false, message = askedMessage(c))
            log.debug("pairing: PAIR_REQ from ${BtrxClient.nodeName(c.node)} dev_a=%08x dev_b=%08x imu_ok=%d; waiting for the operator".format(c.devA, c.devB, c.imuOk))
            return true
        }
        when (pairing.step) {
            PairStep.WAIT_NOD -> newCandidate()
            PairStep.POD_FOUND -> {
                val c = pairing.candidate ?: return
                if (meta.fromNode == c.node) {
                    if (pairing.podStale) log.debug("pairing: ${BtrxClient.nodeName(c.node)} is asking to pair again")
                    pairing = pairing.copy(
                        candidate = c.copy(nonceHex = nonce, rssi = meta.rxRssi, snr = meta.rxSnr, lastRequestMs = now),
                        podStale = false, message = askedMessage(c),
                    )
                } else {
                    // Only the same pod may revive a stale candidate; another pod needs a new Desk Pod tap.
                    log.debug("pairing: another Drone Pod ${BtrxClient.nodeName(meta.fromNode)} also asked to pair; ignored" +
                        if (pairing.podStale) " (tap the Desk Pod button to pair a different pod)" else "")
                }
            }
            PairStep.CONFIRMING -> {
                val c = pairing.candidate ?: return
                if (meta.fromNode == c.node && nonce == c.nonceHex && pairing.confirmTries < MAX_CONFIRM_TRIES) {
                    pairing = pairing.copy(confirmTries = pairing.confirmTries + 1)
                    log.debug("pairing: PAIR_REQ repeated; answering again (try ${pairing.confirmTries})")
                    queueConfirm(c)
                }
            }
            else -> if (!open) log.debug("pairing: PAIR_REQ from ${BtrxClient.nodeName(meta.fromNode)} ignored: no Desk Pod window open")
        }
    }

    private fun askedMessage(c: Candidate) = "Drone Pod ${BtrxClient.nodeName(c.node)} asked to pair. Confirm it is the pod in your hand."

    private fun onPairAck(ack: P.PairAck, meta: BtrxClient.RadioMeta) {
        if (meta.backlog || pairing.step != PairStep.CONFIRMING) return
        val c = pairing.candidate ?: return
        if (meta.fromNode != c.node) return
        if (ack.aOk == 1) {
            val pod = PairedPod(c.node, c.assignedPodId, c.devA, c.devB)
            if (pairedPod?.node != pod.node) {
                steering.reset()
                if (savedIgnores.isNotEmpty()) { savedIgnores.clear(); onSavedIgnores(emptyList()) }
            }
            if (pairedPod != pod) imuCal.reset()
            pairedPod = pod
            podIdMismatches = 0; lastMismatchPodId = null
            quietWatchSinceMs = meta.arrivalMs
            val msg = when {
                ack.bOk != 1 -> "Paired, but the port board did not answer. Check the cable between the boards."
                pairing.repair -> "Drone Pod re-paired."
                else -> "Drone Pod paired."
            }
            pairing = pairing.copy(step = PairStep.PAIRED, aOk = ack.aOk, bOk = ack.bOk, ackFlags = ack.flags, message = msg, failedAt = null)
            log.debug("pairing: PAIR_ACK a_ok=${ack.aOk} b_ok=${ack.bOk}; paired ${BtrxClient.nodeName(c.node)} pod_id=%08x".format(c.assignedPodId))
            onPaired(pod)
        } else {
            pairing = pairing.copy(aOk = ack.aOk, bOk = ack.bOk, ackFlags = ack.flags)
            fail(PairStep.CONFIRMING, "The Drone Pod did not take the pairing (a_ok=0, flags=${P.statusFlagNames(ack.flags)}).")
        }
    }

    // ---- pairing actions ----

    /**
     * "Pair drone pod". One app pairs exactly one Drone Pod: with a pod already paired this
     * does nothing; the screen offers [replacePairedPod] instead.
     */
    fun startPairing() {
        synchronized(this) {
            val now = clock()
            pairedPod?.let {
                log.debug("pairing: not started: Drone Pod ${BtrxClient.nodeName(it.node)} is paired; use Replace Drone Pod")
                return
            }
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

    /**
     * "Re-pair Drone Pod" (after the operator confirmed): a paired pod ignores nods from build
     * 0x0106, so PAIR_OPEN (0x16) to it opens its pairing window, and DESK_PAIR to the Desk Pod
     * opens the Desk Pod's (its phone-link bench-test path, answered by DESK_PAIR open=1). Then
     * the normal flow, only the paired node accepted. A paired pod on an older build (HEALTH
     * says so) gets no PAIR_OPEN: it still opens its window on a nod. Nothing is unpaired: if
     * no confirm happens the pod stays paired as it was, here and on the pod.
     */
    fun repairDronePod(seconds: Int? = null) {
        val sends = synchronized(this) {
            val now = clock()
            val pod = pairedPod
            val s = sender
            val local = localNode
            when {
                pairing.step in ACTIVE_STEPS -> {
                    log.debug("pairing: re-pair not started: pairing already in progress at ${pairing.step}")
                    return
                }
                pod == null -> {
                    pairing = Pairing(step = PairStep.FAILED, failedAt = PairStep.WAIT_BUTTON, repair = true, message = REPAIR_NOT_PAIRED)
                    return
                }
                s == null || !s.isReady || local == null -> {
                    pairing = Pairing(step = PairStep.FAILED, failedAt = PairStep.WAIT_BUTTON, repair = true,
                        message = "The Desk Pod is not connected. $REPAIR_KEPT")
                    return
                }
            }
            pod!!; local!!
            val build = lastHealth?.takeIf { it.node == pod.node }?.health?.buildHash
            val pairOpen = build == null || build >= P.BUILD_STABLE_IDS
            if (pairOpen) {
                log.info("pairing: re-pair: PAIR_OPEN seconds=${seconds ?: "default"} to ${BtrxClient.nodeName(pod.node)} pod_id=%08x, DESK_PAIR to the Desk Pod".format(pod.podId))
                outbox += pod.node to { seq -> P.pairOpenFrame(seq, pod.podId, seconds) }
            } else {
                log.info("pairing: re-pair: Drone Pod build 0x%04x has no PAIR_OPEN (nod it); DESK_PAIR to the Desk Pod".format(build))
            }
            outbox += local to { seq -> P.deskPairOpenFrame(seq) }
            pairing = Pairing(step = PairStep.WAIT_BUTTON, repair = true, repairByNod = !pairOpen,
                message = if (pairOpen) REPAIR_OPENING else REPAIR_OPENING_NOD)
            stepDeadlineMs = now + REPAIR_WINDOW_WAIT_MS
            if (window.isOpen(now)) enterWaitNod(now)
            takeOutbox()
        }
        deliver(sends)
    }

    /** The operator confirmed the pod shown. */
    fun confirmPod() {
        val sends = synchronized(this) {
            val now = clock()
            val c = pairing.candidate
            if (pairing.step != PairStep.POD_FOUND || c == null) return
            if (pairing.podStale || now - c.lastRequestMs > POD_REQUEST_STALE_MS) {
                markStale(c)
                return
            }
            pairing = pairing.copy(step = PairStep.CONFIRMING, confirmTries = 1, message = "Waiting for the Drone Pod to acknowledge.")
            stepDeadlineMs = now + ACK_TIMEOUT_MS
            log.debug("pairing: PAIR_CONFIRM to ${BtrxClient.nodeName(c.node)} nonce=${c.nonceHex} pod_id=%08x".format(c.assignedPodId))
            queueConfirm(c)
            takeOutbox()
        }
        deliver(sends)
    }

    /** "Cancel pairing": ends the flow. Nothing is sent; the pod's own window runs out. */
    @Synchronized
    fun cancelPairing() {
        if (pairing.step in ACTIVE_STEPS) {
            log.debug("pairing: cancelled by the operator at ${pairing.step}")
            pairing = pairing.copy(step = PairStep.CANCELLED, message = "Pairing cancelled.", failedAt = null)
        }
    }

    /** Clears a finished result so the screen shows "Pair drone pod" again. */
    @Synchronized
    fun clearPairingResult() {
        if (pairing.step !in ACTIVE_STEPS) pairing = Pairing()
    }

    /** Timeouts; call about once a second. */
    fun tick() {
        val sends = synchronized(this) {
            tickLocked()
            imuCal.tick(clock())?.let { queueImuCal(it) }
            takeOutbox()
        }
        deliver(sends)
    }

    private fun tickLocked() {
        val now = clock()
        trimDetects(now)
        val open = window.isOpen(now)
        when (pairing.step) {
            PairStep.WAIT_BUTTON -> if (now >= stepDeadlineMs) fail(PairStep.WAIT_BUTTON,
                if (pairing.repair) "The Desk Pod did not open its pairing window. Tap the Desk Pod button, then try Re-pair again."
                else "The Desk Pod button was not tapped. Tap it, then start again.")
            PairStep.WAIT_NOD -> if (!open) fail(PairStep.WAIT_NOD, windowEndedMessage("No Drone Pod asked to pair. Nod the drone pod twice within the window."))
            PairStep.POD_FOUND -> pairing.candidate?.let { c ->
                if (!pairing.podStale && now - c.lastRequestMs > POD_REQUEST_STALE_MS) markStale(c)
                if (pairing.podStale && (!open || now - c.foundMs > POD_FOUND_MAX_MS)) {
                    fail(PairStep.POD_FOUND, STALE_ENDED_MESSAGE)
                }
            }
            PairStep.CONFIRMING -> if (now >= stepDeadlineMs) fail(PairStep.CONFIRMING, "No PAIR_ACK from the Drone Pod within ${ACK_TIMEOUT_MS / 1000} s. Tap the Desk Pod button and try again.")
            else -> Unit
        }
        if (pairing.step in ACTIVE_STEPS) pairing = pairing.copy(windowSecondsLeft = window.secondsLeft(now))
        pendingFilter?.let {
            if (now - it.sentMs > FILTER_ACK_TIMEOUT_MS) {
                log.warn("own aircraft (saved): no FILTER_ACK for ${P.muteOpName(it.op)} src=0x%04x within %d s".format(it.srcId, FILTER_ACK_TIMEOUT_MS / 1000))
                filterMessage = "No answer from the Drone Pod. Try again."
                pendingFilter = null
            }
        }
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
            pairedPodIdMismatches = podIdMismatches, lastMismatchPodId = lastMismatchPodId,
            pairedPodQuiet = pairedQuiet(now),
            deskPhoneLost = deskPhoneLost,
            droppedEncrypted = droppedEncrypted, droppedOtherPort = droppedOtherPort,
            steerOverlay = if (steeringLinkUp()) steering.view(now) else null,
            lastSteer = steering.lastView(now),
            steeringRollCorrection = steering.rollCorrection,
            imuCal = imuCal.view(now),
            deskHealthExt = deskHealthExt,
            savedIgnoreSupported = savedIgnoreSupportedLocked(),
            savedIgnores = savedIgnores.toList(),
            filterPending = pendingFilter,
            filterMessage = filterMessage,
        )
    }

    private fun savedIgnoreSupportedLocked(): Boolean {
        val pod = pairedPod ?: return false
        return P.savedIgnoreSupported(lastHealth?.takeIf { it.node == pod.node }?.health?.buildHash)
    }

    // ---- own aircraft (saved): STEER_MUTE op 3 / 4, FILTER_ACK (firmware 0x0106) ----

    /** "Own aircraft (saved)": the pod puts the address behind [srcId] on its saved ignore list. */
    fun ignoreSourceSaved(srcId: Int) = filterAction(P.MUTE_IGNORE, srcId)

    /** "Remove": the pod takes the address behind [srcId] off its saved ignore list. */
    fun unignoreSourceSaved(srcId: Int) = filterAction(P.MUTE_UNIGNORE, srcId)

    private fun filterAction(op: Int, srcId: Int) {
        val sends = synchronized(this) {
            val now = clock()
            val pod = pairedPod
            val busy = pendingFilter?.takeIf { now - it.sentMs <= FILTER_ACK_TIMEOUT_MS }
            val blocked = when {
                pod == null -> "No Drone Pod is paired."
                !savedIgnoreSupportedLocked() -> "The Drone Pod's firmware does not save ignored sources (needs build 0x0106). Use Own aircraft on this device."
                sender?.isReady != true -> "The Desk Pod is not connected."
                srcId !in 1..0xFFFF -> "That source has no Drone Pod id yet."
                busy != null -> "Waiting for the Drone Pod to answer the last change."
                else -> null
            }
            if (blocked != null) {
                log.warn("own aircraft (saved): ${P.muteOpName(op)} src=0x%04x not sent: %s".format(srcId, blocked))
                filterMessage = blocked
            } else {
                pod!!
                log.info("own aircraft (saved): STEER_MUTE ${P.muteOpName(op)} src=0x%04x to %s".format(srcId, BtrxClient.nodeName(pod.node)))
                outbox += pod.node to { seq -> P.steerMuteFrame(seq, pod.podId, op, srcId) }
                pendingFilter = PendingFilter(op, srcId, now)
                filterMessage = if (op == P.MUTE_IGNORE) "Saving on the Drone Pod…" else "Removing from the Drone Pod…"
            }
            takeOutbox()
        }
        deliver(sends)
    }

    private fun onFilterAck(ack: P.FilterAck, frameFlags: Int) {
        val pend = pendingFilter
        val wantOp = when (pend?.op) { P.MUTE_IGNORE -> P.FILTER_OP_ADD; P.MUTE_UNIGNORE -> P.FILTER_OP_REMOVE; else -> null }
        if (pend == null || ack.op != wantOp) {
            log.debug("own aircraft (saved): FILTER_ACK op=${ack.op} ok=${ack.ok} stored=${ack.stored} matches no request out; ignored")
            return
        }
        pendingFilter = null
        val id = "0x%04x".format(pend.srcId)
        log.info("own aircraft (saved): FILTER_ACK op=${ack.op} ok=${ack.ok} list_crc=0x%04x stored=${ack.stored} flags=0x%02x for src=$id".format(ack.listCrc, frameFlags))
        if (ack.ok == 1) {
            val changed = if (pend.op == P.MUTE_IGNORE) savedIgnores.add(pend.srcId) else savedIgnores.remove(pend.srcId)
            if (changed) onSavedIgnores(savedIgnores.toList())
            filterMessage = if (pend.op == P.MUTE_IGNORE) "Saved on the Drone Pod (${ack.stored} of $FILTER_LIST_MAX saved)."
                else "Removed from the Drone Pod (${ack.stored} of $FILTER_LIST_MAX saved)."
        } else {
            filterMessage = if (pend.op == P.MUTE_IGNORE)
                "The Drone Pod did not save it: it saves only a source heard in the last 60 s, and holds $FILTER_LIST_MAX."
            else "The Drone Pod did not remove it: it removes only a source it heard in the last 60 s."
        }
    }

    private fun steeringLinkUp(): Boolean = pairedPod != null && link.phase == LinkPhase.READY && sender != null

    // ---- steering actions ----

    /** "Mute": STEER_MUTE op 1 for the current steering source, to the paired pod. */
    fun muteSteeringSource() {
        val sends = synchronized(this) {
            val pod = pairedPod
            val src = steering.view(clock())?.takeIf { it.canMute }?.srcId
            when {
                pod == null -> log.warn("steering: Mute not sent: no paired Drone Pod")
                src == null -> log.warn("steering: Mute not sent: no current steering source")
                sender?.isReady != true -> log.warn("steering: Mute not sent: the Desk Pod is not connected")
                else -> {
                    log.info("steering: STEER_MUTE mute src=$src to ${BtrxClient.nodeName(pod.node)}")
                    outbox += pod.node to { seq -> P.steerMuteFrame(seq, pod.podId, P.MUTE_ADD, src) }
                }
            }
            takeOutbox()
        }
        deliver(sends)
    }

    /** Debug switch on the Desk Pod screen; off by default until the bench roll check. */
    @Synchronized
    fun setSteeringRollCorrection(on: Boolean) {
        if (steering.rollCorrection != on) log.info("steering: roll correction ${if (on) "on" else "off"} (debug)")
        steering.rollCorrection = on
    }

    // ---- IMU calibration actions (design doc section 29.3) ----

    /** "Calibrate" / "Start again": the level step at once. */
    fun startImuCal() = imuCalAction { imuCal.start(clock()) }

    /** "Start starboard step" / "Retry starboard step": the countdown, then the starboard-down step (from [tick]). */
    @Synchronized
    fun startImuCalStarboard() {
        val blocked = imuCalBlocked()
        if (blocked != null) {
            log.warn("imu cal: starboard step not started: $blocked")
            imuCal.notSent(null, blocked)
        } else if (imuCal.startStarboard(clock())) {
            log.debug("imu cal: starboard step countdown")
        }
    }

    /** "Check": which calibration the pod uses. */
    fun checkImuCal() = imuCalAction { imuCal.check(clock()) }

    /** "Clear saved calibration": back to the mounting set in the pod's build. */
    fun clearImuCal() = imuCalAction { imuCal.clear(clock()) }

    /** "Cancel calibration": ends the flow; nothing is sent. */
    @Synchronized
    fun cancelImuCal() {
        if (imuCal.currentStep in BtrxImuCal.FLOW_STEPS) log.debug("imu cal: cancelled by the operator at ${imuCal.currentStep}")
        imuCal.cancel()
    }

    /** "Close": ends a finished calibration result. */
    @Synchronized
    fun closeImuCalResult() = imuCal.close()

    private fun imuCalAction(start: () -> BtrxImuCal.Request?) {
        val sends = synchronized(this) {
            val blocked = imuCalBlocked()
            if (blocked != null) {
                log.warn("imu cal: not sent: $blocked")
                imuCal.notSent(null, blocked)
            } else {
                start()?.let { queueImuCal(it) }
            }
            takeOutbox()
        }
        deliver(sends)
    }

    /** Why IMU_CAL cannot go out now, or null. */
    private fun imuCalBlocked(): String? = when {
        pairedPod == null -> "No Drone Pod is paired. Pair it first."
        sender?.isReady != true -> "The Desk Pod is not connected."
        else -> null
    }

    private fun queueImuCal(r: BtrxImuCal.Request) {
        val pod = pairedPod
        val blocked = imuCalBlocked()
        if (pod == null || blocked != null) {
            log.warn("imu cal: ${P.imuCalOpName(r.op)} not sent: $blocked")
            imuCal.notSent(r, blocked ?: "No Drone Pod is paired.")
            return
        }
        log.debug("imu cal: IMU_CAL ${P.imuCalOpName(r.op)} tag=${r.tag} to ${BtrxClient.nodeName(pod.node)}")
        outbox += pod.node to { seq -> P.imuCalFrame(seq, pod.podId, r.op, r.tag) }
    }

    /** "Clear mutes": STEER_MUTE op 0, to the paired pod. */
    fun clearSteeringMutes() {
        val sends = synchronized(this) {
            val pod = pairedPod
            when {
                pod == null -> log.warn("steering: Clear mutes not sent: no paired Drone Pod")
                sender?.isReady != true -> log.warn("steering: Clear mutes not sent: the Desk Pod is not connected")
                else -> {
                    log.info("steering: STEER_MUTE clear to ${BtrxClient.nodeName(pod.node)}")
                    outbox += pod.node to { seq -> P.steerMuteFrame(seq, pod.podId, P.MUTE_CLEAR, 0) }
                }
            }
            takeOutbox()
        }
        deliver(sends)
    }

    private fun pairedQuiet(now: Long): Boolean {
        val pod = pairedPod ?: return false
        if (link.phase != LinkPhase.READY || sender == null) return false
        val lastHeard = lastHealth?.takeIf { it.node == pod.node }?.arrivalMs ?: 0L
        return now - maxOf(lastHeard, quietWatchSinceMs) > PAIRED_QUIET_MS
    }

    // ---- internals ----

    private fun enterWaitNod(now: Long) {
        val msg = if (pairing.repair && !pairing.repairByNod) "Waiting for the Drone Pod to ask to pair." else "Nod the drone pod twice."
        pairing = pairing.copy(step = PairStep.WAIT_NOD, windowSecondsLeft = window.secondsLeft(now), message = msg)
        log.debug("pairing: Desk Pod window open, ${window.secondsLeft(now)} s left; waiting for PAIR_REQ")
    }

    private fun markStale(c: Candidate) {
        if (!pairing.podStale) log.debug("pairing: no PAIR_REQ from ${BtrxClient.nodeName(c.node)} for over ${POD_REQUEST_STALE_MS / 1000} s; asking for another nod")
        pairing = pairing.copy(podStale = true, message = STALE_MESSAGE)
    }

    private fun windowEndedMessage(fallback: String): String = when (window.lastClose) {
        BtrxDeskWindow.CloseReason.CLOSED_BY_DESK_POD -> "The Desk Pod closed its pairing window. $fallback"
        else -> "The pairing window ran out. $fallback"
    }

    private fun fail(at: PairStep, message: String) {
        val m = if (pairing.repair && pairedPod != null && !message.contains(REPAIR_KEPT)) "$message $REPAIR_KEPT" else message
        log.warn("pairing failed at $at: $m")
        pairing = pairing.copy(step = PairStep.FAILED, failedAt = at, message = m, windowSecondsLeft = 0)
    }

    private fun queueConfirm(c: Candidate) {
        val nonce = P.fromHex(c.nonceHex)
        outbox += c.node to { seq -> P.pairConfirmFrame(seq, c.assignedPodId, nonce, c.roleA.takeIf { it != 0 } ?: P.ROLE_STARBOARD, c.roleB.takeIf { it != 0 } ?: P.ROLE_PORT) }
    }

    // ---- bearing map layer (docs/btrx/BEARING-LAYER-spec.md section 7) ----

    /**
     * Where a frame comes from, for the bearing layer: [BEARING_FROM_POD] the paired Drone
     * Pod's node (backlog included: the layer judges frames by window time),
     * [BEARING_FROM_DESK] the Desk Pod itself (its own HEALTH), else [BEARING_FROM_OTHER].
     */
    @Synchronized
    fun bearingOrigin(frame: P.Frame, meta: BtrxClient.RadioMeta): Int {
        val local = localNode
        if (local != null && meta.fromNode == local) return BEARING_FROM_DESK
        val paired = pairedPod ?: return BEARING_FROM_OTHER
        if (meta.fromNode != paired.node) return BEARING_FROM_OTHER
        return if (frame.podId == 0L || frame.podId == paired.podId) BEARING_FROM_POD else BEARING_FROM_OTHER
    }

    /** TIME_PING to the paired Drone Pod (spec 3.2). False with no paired pod or no ready link. */
    fun sendTimePing(pingSeq: Int, t1Ms: Long): Boolean {
        val sends = synchronized(this) {
            val pod = pairedPod ?: return false
            if (sender?.isReady != true) return false
            outbox += pod.node to { seq -> P.timePingFrame(seq, pod.podId, pingSeq, t1Ms) }
            takeOutbox()
        }
        deliver(sends)
        return true
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
        const val BEARING_FROM_OTHER = 0
        const val BEARING_FROM_POD = 1
        const val BEARING_FROM_DESK = 2
        const val BUTTON_WAIT_MS = 120_000L
        const val ACK_TIMEOUT_MS = 30_000L
        const val MAX_CONFIRM_TRIES = 8
        const val DETECT_WINDOW_MS = 60_000L
        /** PAIR_REQ repeats every 2 s while the pod's own window is open. */
        const val POD_REQUEST_STALE_MS = 7_000L
        /** A stale candidate ends the flow this long after it was found, even with the window open. */
        const val POD_FOUND_MAX_MS = 120_000L
        /** No HEALTH (about every 60 s) from the paired pod for this long: hint to re-pair. */
        const val PAIRED_QUIET_MS = 120_000L
        const val MISMATCH_LOG_EVERY = 50
        /** Re-pair: the Desk Pod answers DESK_PAIR at once; a manual tap also counts until then. */
        const val REPAIR_WINDOW_WAIT_MS = 30_000L
        const val FILTER_ACK_TIMEOUT_MS = 10_000L
        /** The pod's saved ignore list holds 32 addresses (PROTOCOL.md STEER_MUTE). */
        const val FILTER_LIST_MAX = 32
        const val REPAIR_KEPT = "The Drone Pod stays paired as it was."
        const val REPAIR_NOT_PAIRED = "No Drone Pod is paired. Use Pair drone pod."
        const val REPAIR_OPENING = "Opening the pairing windows on the Desk Pod and the Drone Pod…"
        const val REPAIR_OPENING_NOD = "Opening the Desk Pod's pairing window… Then nod the drone pod twice (its firmware is older than 0x0106)."
        const val STALE_ENDED_MESSAGE = "The Drone Pod stopped asking to pair and its pairing window is over. Tap the Desk Pod button and nod the drone pod twice again."
        const val STALE_MESSAGE = "The Drone Pod stopped asking to pair; its own pairing window has probably closed. Nod the drone pod twice again, then confirm."
        val ACTIVE_STEPS = setOf(PairStep.WAIT_BUTTON, PairStep.WAIT_NOD, PairStep.POD_FOUND, PairStep.CONFIRMING)
    }
}

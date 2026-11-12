package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.PairStep
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodText.StepStatus
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

class BtrxDeskPodModelTest {
    private var now = 100_000L
    private val logs = mutableListOf<String>()
    private val logger = object : BtrxClient.Logger {
        override fun info(message: String) { logs += message }
        override fun warn(message: String) { logs += "W $message" }
    }
    private val paired = mutableListOf<BtrxDeskPodModel.PairedPod>()
    private val model = BtrxDeskPodModel(logger, clock = { now }, onPaired = { paired += it })

    private class FakeSender : BtrxFrameSender {
        override var isReady = true
        var seq = 0
        val sent = mutableListOf<Pair<Long, ByteArray>>()
        override fun nextSeq() = ++seq
        override fun sendFrame(to: Long, frame: ByteArray) { sent += to to frame }
    }

    private val sender = FakeSender()
    private val desk = 0x66C842CAL
    private val pod = 0x018968BEL
    private var deskSeq = 0
    private var podSeq = 0

    init {
        model.attach(sender)
        model.onConfigured(desk)
    }

    private fun meta(from: Long, backlog: Boolean = false) = BtrxClient.RadioMeta(from, if (from == desk) desk else 0xFFFFFFFFL, -45, 9.5f, now, backlog)

    private fun deskPair(open: Int, seconds: Int = 60, backlog: Boolean = false, seq: Int = ++deskSeq) =
        model.onFrame(P.decodeFrame(P.encodeFrame(P.DESK_PAIR, seq = seq, payload = byteArrayOf(open.toByte(), 1, seconds.toByte(), (seconds shr 8).toByte()))), meta(desk, backlog))

    private fun pairReq(nonce: String = "570b73e9475dfe3f", from: Long = pod, backlog: Boolean = false, imuOk: Int = 1, devB: Long = 0x351C2971L) {
        val p = ByteArray(27)
        P.putU32(p, 4, 0x4C8C125BL); P.putU32(p, 8, devB)
        p[12] = 0; p[13] = 1; p[14] = 0; p[15] = 1
        p[16] = imuOk.toByte(); p[17] = 2; p[18] = if (devB == 0L) 0 else 1
        P.fromHex(nonce).copyInto(p, 19)
        model.onFrame(P.decodeFrame(P.encodeFrame(P.PAIR_REQ, seq = ++podSeq, payload = p)), meta(from, backlog))
    }

    private fun pairAck(a: Int = 1, b: Int = 1, backlog: Boolean = false) =
        model.onFrame(P.decodeFrame(P.encodeFrame(P.PAIR_ACK, seq = ++podSeq, flags = P.F_IS_ACK, payload = byteArrayOf(a.toByte(), b.toByte(), 0, 0))), meta(pod, backlog))

    private fun advance(ms: Long) { now += ms; model.tick() }
    private val step get() = model.snapshot().pairing.step

    // ---- the desk window ----

    @Test
    fun backlogOpenInsideItsWindowCounts() {
        // Ken tapped while the link was still being made: the open frame arrives in the backlog.
        deskPair(1, backlog = true)
        advance(5_000)
        model.startPairing()
        assertEquals(PairStep.WAIT_NOD, step)
        assertEquals(55, model.snapshot().pairing.windowSecondsLeft)
        assertTrue(model.snapshot().windowFromBacklog)
    }

    @Test
    fun backlogOpenFollowedByBacklogCloseIsClosed() {
        deskPair(1, backlog = true)
        deskPair(0, backlog = true)
        model.startPairing()
        assertEquals(PairStep.WAIT_BUTTON, step)
        assertFalse(model.snapshot().windowOpen)
    }

    @Test
    fun expiredWindowIsClosed() {
        deskPair(1, seconds = 60)
        advance(60_000)
        assertFalse(model.snapshot().windowOpen)
        model.startPairing()
        assertEquals(PairStep.WAIT_BUTTON, step)
    }

    @Test
    fun repeatOfTheSameOpenFrameDoesNotExtendTheWindow() {
        deskPair(1, seq = 40)
        advance(30_000)
        deskPair(1, seq = 40)
        assertEquals(30, model.snapshot().windowSecondsLeft)
        deskPair(1, seq = 41) // a new tap does restart it
        assertEquals(60, model.snapshot().windowSecondsLeft)
    }

    @Test
    fun secondsZeroMeansSixty() {
        val w = BtrxDeskWindow()
        w.onDeskPair(1, P.DeskPair(1, 1, 0), 0, false)
        assertEquals(60, w.secondsLeft(0))
        assertTrue(w.isOpen(59_999))
        assertFalse(w.isOpen(60_000))
        assertEquals(BtrxDeskWindow.CloseReason.EXPIRED, w.lastClose)
    }

    // ---- the pairing flow ----

    @Test
    fun fullPairingFlowSendsPairConfirmWithANonZeroPodId() {
        model.startPairing()
        assertEquals(PairStep.WAIT_BUTTON, step)
        deskPair(1)
        assertEquals(PairStep.WAIT_NOD, step)
        pairReq()
        val s = model.snapshot().pairing
        assertEquals(PairStep.POD_FOUND, s.step)
        assertEquals(pod, s.candidate!!.node)
        assertTrue(sender.sent.isEmpty()) // nothing goes out until the operator confirms
        model.confirmPod()
        assertEquals(PairStep.CONFIRMING, step)
        val (to, frame) = sender.sent.single()
        assertEquals(pod, to)
        val f = P.decodeFrame(frame)
        assertEquals(P.PAIR_CONFIRM, f.type)
        assertEquals(0x45D35AF0L, f.podId)
        assertEquals("45d35af0", "%08x".format(P.u32(f.payload, 0)))
        assertEquals("570b73e9475dfe3f", P.hex(f.payload.copyOfRange(4, 12)))
        assertEquals(1, f.payload[14].toInt()) // confirm
        assertEquals(0, f.payload[15].toInt()) // channel 0
        assertEquals(0, f.payload[16].toInt()) // no key
        pairAck(1, 1)
        val done = model.snapshot()
        assertEquals(PairStep.PAIRED, done.pairing.step)
        assertEquals("Drone Pod paired.", done.pairing.message)
        assertEquals(BtrxDeskPodModel.PairedPod(pod, 0x45D35AF0L, 0x4C8C125BL, 0x351C2971L), done.pairedPod)
        assertEquals(1, paired.size)
        assertTrue(BtrxDeskPodText.steps(done.pairing).all { it.status == StepStatus.DONE })
    }

    @Test
    fun portBoardMissingIsShownAfterPairing() {
        model.startPairing(); deskPair(1); pairReq(devB = 0)
        assertTrue(model.snapshot().pairing.candidate!!.warnings.any { it.contains("port board") })
        model.confirmPod(); pairAck(1, 0)
        assertTrue(model.snapshot().pairing.message.startsWith("Paired, but the port board did not answer"))
    }

    @Test
    fun pairReqWithoutAnOpenWindowIsIgnored() {
        pairReq()
        model.startPairing()
        pairReq()
        assertEquals(PairStep.WAIT_BUTTON, step)
        assertTrue(logs.any { it.contains("no Desk Pod window open") })
    }

    @Test
    fun backlogPairReqIsNeverActedOn() {
        model.startPairing(); deskPair(1)
        pairReq(backlog = true)
        assertEquals(PairStep.WAIT_NOD, step)
        pairReq()
        assertEquals(PairStep.POD_FOUND, step)
    }

    @Test
    fun deskPairCloseEndsTheNodWaitWithAFailure() {
        model.startPairing(); deskPair(1)
        deskPair(0)
        val p = model.snapshot().pairing
        assertEquals(PairStep.FAILED, p.step)
        assertEquals(PairStep.WAIT_NOD, p.failedAt)
        assertTrue(p.message.contains("closed its pairing window"))
        val steps = BtrxDeskPodText.steps(p)
        assertEquals(listOf(StepStatus.DONE, StepStatus.FAILED, StepStatus.PENDING, StepStatus.PENDING), steps.map { it.status })
    }

    @Test
    fun deskWindowEndingDoesNotFailWhileThePodKeepsAsking() {
        // The pod's own window decides; the Desk Pod's is advisory once a pod has asked.
        model.startPairing(); deskPair(1, seconds = 10); pairReq()
        repeat(6) { advance(2_000); pairReq() }
        deskPair(0)
        assertEquals(PairStep.POD_FOUND, step)
        assertFalse(model.snapshot().pairing.podStale)
        model.confirmPod()
        assertEquals(PairStep.CONFIRMING, step)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun podThatStopsAskingIsMarkedStaleAndNotConfirmed() {
        model.startPairing(); deskPair(1); pairReq()
        advance(6_000) // three missed 2 s repeats are not stale yet (threshold 7 s)
        assertFalse(model.snapshot().pairing.podStale)
        advance(BtrxDeskPodModel.POD_REQUEST_STALE_MS - 6_000 + 1_000)
        var p = model.snapshot().pairing
        assertEquals(PairStep.POD_FOUND, p.step)
        assertTrue(p.podStale)
        assertEquals(BtrxDeskPodModel.STALE_MESSAGE, p.message)
        assertTrue(BtrxDeskPodText.steps(p)[2].detail.endsWith(BtrxDeskPodText.POD_STALE_HINT))
        model.confirmPod()
        assertTrue(sender.sent.isEmpty()) // no 30 s wait for a PAIR_ACK that cannot come
        assertEquals(PairStep.POD_FOUND, step)
        // Another nod: a fresh PAIR_REQ with a new nonce.
        pairReq(nonce = "0102030405060708")
        p = model.snapshot().pairing
        assertFalse(p.podStale)
        model.confirmPod()
        assertEquals("0102030405060708", P.hex(P.decodeFrame(sender.sent.single().second).payload.copyOfRange(4, 12)))
    }

    @Test
    fun confirmRightAfterTheRequestsStopIsRefusedBeforeTheTick() {
        model.startPairing(); deskPair(1); pairReq()
        now += BtrxDeskPodModel.POD_REQUEST_STALE_MS + 1 // no tick yet
        model.confirmPod()
        assertTrue(sender.sent.isEmpty())
        assertTrue(model.snapshot().pairing.podStale)
    }

    @Test
    fun onlyANewDeskPodTapLetsAnotherPodReplaceAStaleCandidate() {
        model.startPairing(); deskPair(1); pairReq()
        advance(BtrxDeskPodModel.POD_REQUEST_STALE_MS + 1_000)
        pairReq(from = 0x11112222L)
        var p = model.snapshot().pairing
        assertEquals(pod, p.candidate!!.node) // a different pod cannot revive it
        assertTrue(p.podStale)
        deskPair(1, backlog = true, seq = 90) // not a live tap
        assertEquals(PairStep.POD_FOUND, step)
        deskPair(1) // a new live tap: back to the nod
        p = model.snapshot().pairing
        assertEquals(PairStep.WAIT_NOD, p.step)
        assertNull(p.candidate)
        pairReq(from = 0x11112222L)
        p = model.snapshot().pairing
        assertEquals(PairStep.POD_FOUND, p.step)
        assertEquals(0x11112222L, p.candidate!!.node)
        assertFalse(p.podStale)
    }

    @Test
    fun staleCandidateEndsTheFlowOnceTheDeskWindowHasClosed() {
        model.startPairing(); deskPair(1, seconds = 10); pairReq()
        advance(8_000)
        assertTrue(model.snapshot().pairing.podStale)
        assertEquals(PairStep.POD_FOUND, step) // window still open: another nod can revive it
        advance(3_000)
        val p = model.snapshot().pairing
        assertEquals(PairStep.FAILED, p.step)
        assertEquals(PairStep.POD_FOUND, p.failedAt)
        assertEquals(BtrxDeskPodModel.STALE_ENDED_MESSAGE, p.message)
        assertTrue(p.message.contains("Tap the Desk Pod button and nod the drone pod twice again"))
    }

    @Test
    fun staleCandidateEndsTheFlowTwoMinutesAfterItWasFound() {
        model.startPairing(); deskPair(1); pairReq()
        // The pod keeps asking and the Desk Pod window is renewed by new taps.
        var t = 0L
        while (t < 116_000) {
            advance(2_000); t += 2_000
            pairReq()
            if (t % 50_000 == 0L) deskPair(1)
        }
        assertEquals(PairStep.POD_FOUND, step)
        deskPair(1) // window open well past the stale point
        advance(BtrxDeskPodModel.POD_REQUEST_STALE_MS + 1_000)
        assertTrue(model.snapshot().windowOpen)
        val p = model.snapshot().pairing
        assertEquals(PairStep.FAILED, p.step)
        assertEquals(BtrxDeskPodModel.STALE_ENDED_MESSAGE, p.message)
    }

    @Test
    fun repeatedPairReqIsAnsweredAgainUpToEightTimes() {
        model.startPairing(); deskPair(1); pairReq(); model.confirmPod()
        repeat(10) { pairReq() }
        assertEquals(BtrxDeskPodModel.MAX_CONFIRM_TRIES, sender.sent.size)
        pairReq(nonce = "0102030405060708") // a different request is not answered
        assertEquals(BtrxDeskPodModel.MAX_CONFIRM_TRIES, sender.sent.size)
    }

    @Test
    fun noPairAckTimesOut() {
        model.startPairing(); deskPair(1); pairReq(); model.confirmPod()
        advance(BtrxDeskPodModel.ACK_TIMEOUT_MS)
        assertEquals(PairStep.FAILED, step)
        assertEquals(PairStep.CONFIRMING, model.snapshot().pairing.failedAt)
    }

    @Test
    fun aOkZeroIsAFailure() {
        model.startPairing(); deskPair(1); pairReq(); model.confirmPod()
        pairAck(0, 0)
        assertEquals(PairStep.FAILED, step)
        assertNull(model.snapshot().pairedPod)
    }

    @Test
    fun buttonNotTappedTimesOut() {
        model.startPairing()
        advance(BtrxDeskPodModel.BUTTON_WAIT_MS)
        assertEquals(PairStep.FAILED, step)
        assertEquals(PairStep.WAIT_BUTTON, model.snapshot().pairing.failedAt)
    }

    @Test
    fun cancelEndsTheFlowAndSendsNothing() {
        model.startPairing(); deskPair(1); pairReq()
        model.cancelPairing()
        assertEquals(PairStep.CANCELLED, step)
        model.confirmPod()
        assertTrue(sender.sent.isEmpty())
        model.clearPairingResult()
        assertEquals(PairStep.IDLE, step)
    }

    @Test
    fun anotherPodAskingDuringConfirmationIsIgnored() {
        model.startPairing(); deskPair(1); pairReq()
        pairReq(from = 0x11112222L)
        assertEquals(pod, model.snapshot().pairing.candidate!!.node)
    }

    @Test
    fun linkLossFailsAPairingInProgress() {
        model.startPairing(); deskPair(1)
        model.detach(sender, "disconnected")
        assertEquals(PairStep.FAILED, step)
        assertTrue(model.snapshot().pairing.message.contains("link to the Desk Pod was lost"))
    }

    @Test
    fun pairingNeedsAReadyLink() {
        sender.isReady = false
        model.startPairing()
        assertEquals(PairStep.FAILED, step)
    }

    @Test
    fun newTapWhileWaitingForTheNodRestartsTheCountdown() {
        model.startPairing(); deskPair(1)
        advance(40_000)
        deskPair(1)
        assertEquals(60, model.snapshot().pairing.windowSecondsLeft)
        assertEquals(PairStep.WAIT_NOD, step)
    }

    // ---- link health ----

    @Test
    fun healthAndDetectRateComeFromLiveFrames() {
        val health = P.decodeFrame(P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("HEALTH")))
        val detect = P.decodeFrame(P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("DETECT")))
        model.onFrame(detect, meta(pod, backlog = true))
        repeat(5) { now += 3_000; model.onFrame(detect, meta(pod)) }
        model.onFrame(health, meta(pod))
        var s = model.snapshot()
        assertEquals(5, s.detectsLastMinute)
        assertEquals(1, s.backlogCounts["DETECT"])
        assertEquals(5, s.liveCounts["DETECT"])
        assertEquals(pod, s.dronePodHealth!!.node)
        assertEquals(1, s.dronePodHealth!!.health.confirmed)
        val lines = BtrxDeskPodText.healthLines(s)
        assertTrue(lines.toString(), lines[0].contains("confirmed, listening"))
        assertTrue(lines.toString(), lines.any { it == "Drone Pod hears Desk Pod: RSSI -30 dBm, SNR 12 dB" })
        assertTrue(lines.toString(), lines.any { it == "Desk Pod hears Drone Pod: RSSI -45 dBm, SNR 9.5 dB" })
        now += 61_000
        s = model.snapshot()
        assertEquals(0, s.detectsLastMinute)
    }

    @Test
    fun onlyThePairedPodsHealthIsShownOnceKnown() {
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, 1, 2, 3))
        val health = P.decodeFrame(P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("HEALTH")))
        model.onFrame(health, meta(0x22223333L))
        assertNull(model.snapshot().dronePodHealth)
        assertEquals(1, model.snapshot().otherPodNodes)
        model.onFrame(health, meta(pod))
        assertNotNull(model.snapshot().dronePodHealth)
    }

    @Test
    fun newSessionResetsCountersButKeepsTheWindow() {
        deskPair(1)
        model.onFrame(P.decodeFrame(P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("DETECT"))), meta(pod))
        val next = FakeSender()
        model.attach(next)
        val s = model.snapshot()
        assertTrue(s.liveCounts.isEmpty())
        assertTrue(s.windowOpen)
    }

    // ---- backlog and paired-pod filtering ----

    private fun podFrame(name: String, podId: Long = 0): P.Frame {
        val ex = P.decodeFrame(P.fromHex(BtrxProtocolTest.EXAMPLES.getValue(name)))
        return P.decodeFrame(P.encodeFrame(ex.type, seq = ex.seq, podId = podId, payload = ex.payload))
    }

    @Test
    fun backlogHealthAndRssiAreNotShownAsCurrent() {
        model.onFrame(podFrame("HEALTH"), meta(pod, backlog = true))
        model.onFrame(podFrame("DETECT"), meta(0x22223333L, backlog = true))
        val s = model.snapshot()
        assertNull(s.dronePodHealth)
        assertNull(s.lastPacketRssi)
        assertEquals(0, s.otherPodNodes)
        assertEquals(0, s.detectsLastMinute)
        assertEquals(1, s.backlogCounts["HEALTH"])
    }

    @Test
    fun oncePairedOnlyThePairedNodeCounts() {
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, 0x45D35AF0L, 0x4C8C125BL, 0x351C2971L))
        model.onFrame(podFrame("DETECT"), meta(0x22223333L))        // another pod: refused
        model.onFrame(podFrame("DETECT"), meta(pod))                // queued before pairing: pod_id 0, right node
        model.onFrame(podFrame("DETECT", 0x45D35AF0L), meta(pod))   // the paired pod_id
        model.onFrame(podFrame("DETECT", 0x12345678L), meta(pod))   // a different pod_id: refused
        model.onFrame(podFrame("HEALTH", 0x12345678L), meta(pod))
        val s = model.snapshot()
        assertEquals(2, s.detectsLastMinute)
        assertEquals(1, s.otherPodNodes)
        assertNull(s.dronePodHealth)
        model.onFrame(podFrame("HEALTH", 0x45D35AF0L), meta(pod))
        assertEquals(pod, model.snapshot().dronePodHealth!!.node)
    }

    @Test
    fun rssiComesOnlyFromThePairedPod() {
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, 1, 2, 3))
        model.onFrame(podFrame("DETECT"), BtrxClient.RadioMeta(0x22223333L, 0xFFFFFFFFL, -90, 1f, now, false))
        assertNull(model.snapshot().lastPacketRssi)
        model.onFrame(podFrame("DETECT"), meta(pod))
        assertEquals(-45, model.snapshot().lastPacketRssi)
    }

    @Test
    fun pairedNodeWithAnUnexpectedPodIdIsCountedLoggedAndShown() {
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, 0x45D35AF0L, 0x4C8C125BL, 0x351C2971L))
        repeat(3) { model.onFrame(podFrame("DETECT", 0x12345678L), meta(pod)) }
        val s = model.snapshot()
        assertEquals(3, s.pairedPodIdMismatches)
        assertEquals(0x12345678L, s.lastMismatchPodId)
        assertEquals(1, logs.count { it.startsWith("W paired node !018968be sending pod_id 12345678") && it.endsWith("re-pair") })
        assertEquals(
            listOf("Paired Drone Pod is sending pod_id 12345678 (3 frame(s) ignored); re-pair it."),
            BtrxDeskPodText.pairedPodWarnings(s),
        )
        model.forgetPairedPod()
        assertEquals(0, model.snapshot().pairedPodIdMismatches)
    }

    @Test
    fun quietPairedPodShowsAReplairHint() {
        model.setLink(BtrxDeskPodModel.LinkStatus(BtrxDeskPodModel.LinkKind.BLUETOOTH, BtrxDeskPodModel.LinkPhase.READY))
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, 0x45D35AF0L, 0x4C8C125BL, 0x351C2971L))
        now += 60_000
        model.onFrame(podFrame("HEALTH", 0x45D35AF0L), meta(pod))
        now += BtrxDeskPodModel.PAIRED_QUIET_MS
        assertFalse(model.snapshot().pairedPodQuiet)
        now += 1
        val s = model.snapshot()
        assertTrue(s.pairedPodQuiet)
        assertEquals(listOf(BtrxDeskPodText.PAIRED_POD_QUIET), BtrxDeskPodText.pairedPodWarnings(s))
        model.setLink(BtrxDeskPodModel.LinkStatus(BtrxDeskPodModel.LinkKind.BLUETOOTH, BtrxDeskPodModel.LinkPhase.RETRY_WAIT))
        assertFalse(model.snapshot().pairedPodQuiet) // not while the link is down
    }

    @Test
    fun noHintBeforeTheFirstTwoMinutesOfASession() {
        model.setLink(BtrxDeskPodModel.LinkStatus(BtrxDeskPodModel.LinkKind.BLUETOOTH, BtrxDeskPodModel.LinkPhase.READY))
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, 1, 2, 3))
        now += 100_000
        model.attach(FakeSender()) // reconnect restarts the wait
        now += 100_000
        assertFalse(model.snapshot().pairedPodQuiet)
        now += 30_000
        assertTrue(model.snapshot().pairedPodQuiet)
    }

    // ---- steering (STEER / STEER_MUTE) ----

    private val pairedPodId = 0x45D35AF0L
    // ---- one Drone Pod per app ----

    @Test
    fun pairingIsNotStartedWhileADronePodIsPaired() {
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, 0x45D35AF0L, 0x4C8C125BL, 0x351C2971L))
        model.startPairing()
        assertEquals(PairStep.IDLE, step)
        assertTrue(logs.any { it.contains("is paired; use Replace Drone Pod") })
    }

    @Test
    fun replaceUnpairsTheCurrentPodThenRunsTheWholePairingFlow() {
        val old = BtrxDeskPodModel.PairedPod(0x0BADF00DL, 0x11111111L, 0x4C8C125BL, 0x351C2971L)
        model.restorePairedPod(old)
        model.replacePairedPod()
        assertNull(model.snapshot().pairedPod) // unpaired first
        assertEquals(PairStep.WAIT_BUTTON, step)
        assertTrue(logs.any { it.contains("Drone Pod !0badf00d pod_id=11111111 unpaired in this app to replace it") })
        assertTrue(sender.sent.isEmpty()) // firmware 0x0102 has no unpair message to send
        deskPair(1)
        pairReq()
        model.confirmPod()
        pairAck(1, 1)
        val done = model.snapshot()
        assertEquals(PairStep.PAIRED, done.pairing.step)
        assertEquals(pod, done.pairedPod!!.node)
        assertEquals(1, paired.size)
        // The old pod keeps sending with its old pod_id: ignored now.
        model.onFrame(P.decodeFrame(P.encodeFrame(P.STEER, seq = ++podSeq, podId = 0x11111111L, payload = P.fromHex(BtrxProtocolTest.STEER_PAYLOAD))), meta(0x0BADF00DL))
        assertNull(model.snapshot().lastSteer)
    }

    @Test
    fun replaceWithNothingPairedJustStartsPairing() {
        model.replacePairedPod()
        assertEquals(PairStep.WAIT_BUTTON, step)
    }

    @Test
    fun replaceTextNamesThePodAndWhatThePodStillHolds() {
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, 0x45D35AF0L, 0x4C8C125BL, 0x351C2971L))
        val msg = BtrxDeskPodText.replacePodMessage(model.snapshot())
        assertTrue(msg, msg.startsWith("Drone Pod !018968be (pod_id 45d35af0) will be unpaired from this app"))
        assertTrue(msg.contains("keeps its own pairing"))
    }

    private fun ready() = model.setLink(BtrxDeskPodModel.LinkStatus(BtrxDeskPodModel.LinkKind.BLUETOOTH, BtrxDeskPodModel.LinkPhase.READY))
    private fun steerFrame(podId: Long = pairedPodId, src: Int = 0xBEEF): P.Frame {
        val s = P.parseSteer(P.fromHex(BtrxProtocolTest.STEER_PAYLOAD))
        val p = P.steerPayload(s); p[8] = src.toByte(); p[9] = (src ushr 8).toByte()
        return P.decodeFrame(P.encodeFrame(P.STEER, seq = ++podSeq, podId = podId, payload = p))
    }

    @Test
    fun steerOverlayShowsOnlyWhilePairedLinkedAndArriving() {
        ready()
        model.onFrame(steerFrame(podId = 0), meta(pod))
        assertNull(model.snapshot().steerOverlay) // not paired yet: ignored
        assertNull(model.snapshot().lastSteer)
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, pairedPodId, 0x4C8C125BL, 0x351C2971L))
        model.onFrame(steerFrame(), meta(pod))
        val v = model.snapshot().steerOverlay!!
        assertEquals(BtrxSteering.Hint.LEFT, v.hint)
        assertEquals(0xBEEF, v.srcId)
        assertEquals(0, v.colorIndex)
        model.setLink(BtrxDeskPodModel.LinkStatus(BtrxDeskPodModel.LinkKind.BLUETOOTH, BtrxDeskPodModel.LinkPhase.RETRY_WAIT))
        assertNull(model.snapshot().steerOverlay) // link down: hidden
        assertNotNull(model.snapshot().lastSteer) // the Desk Pod screen keeps the last one
        ready()
        assertNotNull(model.snapshot().steerOverlay)
        now += BtrxSteering.SHOW_MS + 1
        assertNull(model.snapshot().steerOverlay) // STEER stopped arriving
        assertEquals(BtrxSteering.Hint.LOST, model.snapshot().lastSteer!!.hint)
    }

    @Test
    fun steerIsFilteredByPairedNodeAndPodId() {
        ready()
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, pairedPodId, 0x4C8C125BL, 0x351C2971L))
        model.onFrame(steerFrame(), meta(0x22223333L))              // another node
        model.onFrame(steerFrame(podId = 0x12345678L), meta(pod))   // another pod_id
        model.onFrame(steerFrame(), meta(pod, backlog = true))      // connect backlog
        assertNull(model.snapshot().lastSteer)
        model.onFrame(steerFrame(), meta(pod))
        assertNotNull(model.snapshot().steerOverlay)
    }

    @Test
    fun muteAndClearMutesGoToThePairedPodNode() {
        ready()
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, pairedPodId, 0x4C8C125BL, 0x351C2971L))
        model.muteSteeringSource()
        assertTrue(sender.sent.isEmpty()) // no source yet
        assertTrue(logs.any { it.startsWith("W steering: Mute not sent") })
        model.onFrame(steerFrame(src = 0x0102), meta(pod))
        model.muteSteeringSource()
        val (to, bytes) = sender.sent.last()
        assertEquals(pod, to)
        val f = P.decodeFrame(bytes)
        assertEquals(P.STEER_MUTE, f.type)
        assertEquals(pairedPodId, f.podId)
        assertEquals("010201", P.hex(f.payload)) // op 1, src 0x0102
        model.clearSteeringMutes()
        assertEquals("000000", P.hex(P.decodeFrame(sender.sent.last().second).payload))
        assertEquals(pod, sender.sent.last().first)
    }

    @Test
    fun muteIsRefusedWithoutAPairing() {
        ready()
        model.clearSteeringMutes()
        model.muteSteeringSource()
        assertTrue(sender.sent.none { P.decodeFrame(it.second).type == P.STEER_MUTE })
    }

    // ---- IMU calibration (IMU_CAL / IMU_CAL_ACK) ----

    private fun imuAck(op: Int, tag: Int, status: Int = P.IMUCAL_OK, flags: Int = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_LEVEL_HELD, podId: Long = pairedPodId) =
        P.decodeFrame(P.encodeFrame(P.IMU_CAL_ACK, seq = ++podSeq, podId = podId, flags = P.F_IS_ACK,
            payload = P.imuCalAckPayload(P.ImuCalAck(op, tag, status, flags, 40, 0, 4, 0, 0, listOf(0, 0, 0)))))
    private fun lastImuCal(): Pair<Long, P.Frame> = sender.sent.last().let { it.first to P.decodeFrame(it.second) }
    private val imu get() = model.snapshot().imuCal!!

    @Test
    fun imuCalGoesToThePairedPodNodeAndOnlyItsAnswerCounts() {
        ready()
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, pairedPodId, 0x4C8C125BL, 0x351C2971L))
        model.startImuCal()
        val (to, f) = lastImuCal()
        assertEquals(pod, to)
        assertEquals(P.IMU_CAL, f.type)
        assertEquals(pairedPodId, f.podId)
        assertEquals("0101", P.hex(f.payload)) // level, tag 1
        assertEquals(BtrxImuCal.Step.LEVEL, imu.step)
        model.onFrame(imuAck(1, 1), meta(0x22223333L))               // another node
        model.onFrame(imuAck(1, 1, podId = 0x12345678L), meta(pod))  // another pod_id
        model.onFrame(imuAck(1, 1), meta(pod, backlog = true))       // connect backlog
        assertEquals(BtrxImuCal.Step.LEVEL, imu.step)
        model.onFrame(imuAck(1, 2), meta(pod))                        // another tag
        assertEquals(BtrxImuCal.Step.LEVEL, imu.step)
        assertTrue(logs.any { it.contains("matches no request out; ignored") })
        model.onFrame(imuAck(1, 1), meta(pod))
        assertEquals(BtrxImuCal.Step.STBD_READY, imu.step)
        // The starboard step goes out from tick() when the countdown ends.
        model.startImuCalStarboard()
        assertEquals(BtrxImuCal.Step.STBD_COUNTDOWN, imu.step)
        val before = sender.sent.size
        advance(1_000)
        assertEquals(before, sender.sent.size)
        advance(2_000)
        val (to2, f2) = lastImuCal()
        assertEquals(pod, to2)
        assertEquals(pairedPodId, f2.podId)
        assertEquals("0202", P.hex(f2.payload)) // starboard, tag 2
        model.onFrame(imuAck(2, 2, flags = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_CALIBRATED), meta(pod))
        assertEquals(BtrxImuCal.Step.CALIBRATED, imu.step)
        assertEquals(true, imu.calibrated)
    }

    @Test
    fun imuCalAnswerBeforePairingIsIgnored() {
        ready()
        model.onFrame(imuAck(3, 1, flags = P.IMUCAL_F_IMU_OK or P.IMUCAL_F_CALIBRATED, podId = 0), meta(pod))
        assertNull(imu.calibrated)
    }

    @Test
    fun imuCalIsRefusedWithoutAPairingOrLink() {
        ready()
        model.startImuCal()
        assertTrue(sender.sent.none { P.decodeFrame(it.second).type == P.IMU_CAL })
        assertEquals(BtrxImuCal.Step.FAILED, imu.step)
        assertEquals("No Drone Pod is paired. Pair it first.", imu.message)
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, pairedPodId, 0x4C8C125BL, 0x351C2971L))
        sender.isReady = false
        model.checkImuCal()
        assertTrue(sender.sent.none { P.decodeFrame(it.second).type == P.IMU_CAL })
        assertEquals("The Desk Pod is not connected.", imu.message)
    }

    @Test
    fun imuCalTimesOutCheckClearAndSteerBit6() {
        ready()
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, pairedPodId, 0x4C8C125BL, 0x351C2971L))
        val p = P.steerPayload(P.parseSteer(P.fromHex(BtrxProtocolTest.STEER_PAYLOAD)))
        p[1] = (P.STEER_F_IMU_OK or P.STEER_F_IMU_CAL).toByte()
        model.onFrame(P.decodeFrame(P.encodeFrame(P.STEER, seq = ++podSeq, podId = pairedPodId, payload = p)), meta(pod))
        assertEquals(true, imu.calibrated)
        assertTrue(imu.calibratedFromSteer)
        model.checkImuCal()
        assertEquals("0301", P.hex(lastImuCal().second.payload))
        advance(BtrxImuCal.ACK_TIMEOUT_MS)
        assertEquals(BtrxImuCal.Step.FAILED, imu.step)
        model.clearImuCal()
        assertEquals("0002", P.hex(lastImuCal().second.payload))
        model.onFrame(imuAck(0, 2, flags = P.IMUCAL_F_IMU_OK), meta(pod))
        assertEquals(false, imu.calibrated)
        model.closeImuCalResult()
        assertEquals("", imu.message)
    }

    @Test
    fun imuCalCancelLinkLossAndForget() {
        ready()
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, pairedPodId, 0x4C8C125BL, 0x351C2971L))
        model.startImuCal()
        val n = sender.sent.size
        model.cancelImuCal()
        assertEquals(n, sender.sent.size) // cancel sends nothing
        assertEquals(BtrxImuCal.CANCELLED, imu.message)
        model.startImuCal()
        model.detach(sender, "test")
        assertEquals(BtrxImuCal.Step.FAILED, imu.step)
        assertTrue(imu.message.startsWith("The link to the Desk Pod was lost"))
        model.forgetPairedPod()
        assertEquals(BtrxImuCal.Step.IDLE, imu.step)
        assertNull(imu.calibrated)
    }

    @Test
    fun forgettingThePodClearsSteering() {
        ready()
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, pairedPodId, 0x4C8C125BL, 0x351C2971L))
        model.onFrame(steerFrame(), meta(pod))
        model.forgetPairedPod()
        assertNull(model.snapshot().lastSteer)
        assertNull(model.snapshot().steerOverlay)
    }

    @Test
    fun aPodRestartIsLoggedAndResetsSteering() {
        ready()
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, pairedPodId, 0x4C8C125BL, 0x351C2971L))
        model.onFrame(steerFrame(src = 5), meta(pod))
        model.onFrame(steerFrame(src = 6), meta(pod))
        assertEquals(1, model.snapshot().steerOverlay!!.colorIndex)
        val p = P.steerPayload(P.parseSteer(P.fromHex(BtrxProtocolTest.STEER_PAYLOAD)))
        P.putU32(p, 2, 1_000L); p[8] = 6; p[9] = 0 // window_end_ms went back
        model.onFrame(P.decodeFrame(P.encodeFrame(P.STEER, seq = ++podSeq, podId = pairedPodId, payload = p)), meta(pod))
        assertEquals(0, model.snapshot().steerOverlay!!.colorIndex)
        assertTrue(logs.any { it.startsWith("steering: window_end_ms went back to 1000: the Drone Pod restarted") })
    }

    @Test
    fun rollCorrectionIsOffUntilSwitchedOn() {
        assertFalse(model.snapshot().steeringRollCorrection)
        model.setSteeringRollCorrection(true)
        assertTrue(model.snapshot().steeringRollCorrection)
    }
}

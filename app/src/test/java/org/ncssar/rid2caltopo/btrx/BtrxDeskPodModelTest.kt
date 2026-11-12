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
        advance(BtrxDeskPodModel.POD_REQUEST_STALE_MS + 1_000)
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
    fun anotherPodMayTakeOverAStaleCandidate() {
        model.startPairing(); deskPair(1); pairReq()
        advance(6_000)
        pairReq(from = 0x11112222L)
        val p = model.snapshot().pairing
        assertEquals(0x11112222L, p.candidate!!.node)
        assertFalse(p.podStale)
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
}

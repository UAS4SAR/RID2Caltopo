package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertArrayEquals
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
    private val savedLists = mutableListOf<List<Int>>()
    private val model = BtrxDeskPodModel(logger, clock = { now }, onPaired = { paired += it }, onSavedIgnores = { savedLists += it })

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

    private fun deskPair(open: Int, seconds: Int = 60, backlog: Boolean = false, seq: Int = ++deskSeq, source: Int = 1) =
        model.onFrame(P.decodeFrame(P.encodeFrame(P.DESK_PAIR, seq = seq, payload = byteArrayOf(open.toByte(), source.toByte(), seconds.toByte(), (seconds shr 8).toByte()))), meta(desk, backlog))

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

    private fun role(build: Int) = P.RoleReport(role = P.ROLE_DESK_POD, source = 0, confirmed = 1, flags = 0, buildHash = build,
        devId = 1, peerId = 0, podId = 0, imuWhoami = 0, txPolicy = 0)

    @Test
    fun linkDropLogsTheLastHealthUptimes() {
        model.detach(sender, "first")
        assertTrue(logs.toString(), logs.contains("link drop (first): no Desk Pod HEALTH this session; no Drone Pod HEALTH"))
        model.attach(sender)
        model.onConfigured(desk)
        val h34 = ByteArray(34).also { P.putU32(it, 0, 500); it[6] = 3 }
        model.onFrame(P.decodeFrame(P.encodeFrame(P.HEALTH, payload = h34)), meta(desk))
        model.onFrame(P.decodeFrame(P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("HEALTH"))), meta(pod))
        val podUptime = model.snapshot().dronePodHealth!!.health.uptimeS
        now += 12_000
        model.detach(sender, "disconnected (status 8)")
        val line = "link drop (disconnected (status 8)): Desk Pod last HEALTH uptime_s=500, 12 s ago; " +
            "Drone Pod last HEALTH uptime_s=$podUptime, 12 s ago"
        assertTrue(logs.toString(), logs.contains(line))
    }

    @Test
    fun deskPodOwnHealthShowsPhoneLostAndIsNotDronePodHealth() {
        model.onLocalRoleReport(role(0x0104))
        var s = model.snapshot()
        assertTrue(s.roleSupported)
        assertEquals("Firmware build: 0x0104 (supported)", BtrxDeskPodText.firmwareLine(s))
        val h34 = ByteArray(34).also { P.putU32(it, 0, 500); it[6] = 3; P.putU32(it, 30, 17L) }
        val frame = P.decodeFrame(P.encodeFrame(P.HEALTH, payload = h34))
        model.onFrame(frame, meta(desk, backlog = true))
        assertEquals(null, model.snapshot().deskPhoneLost) // backlog: not current
        model.onFrame(frame, meta(desk))
        s = model.snapshot()
        assertEquals(17L, s.deskPhoneLost)
        assertEquals(null, s.dronePodHealth)
        assertEquals(0, s.otherPodNodes)
        assertTrue(BtrxDeskPodText.healthLines(s).toString(),
            BtrxDeskPodText.healthLines(s).contains("Desk Pod to app: 17 message(s) dropped since the Desk Pod started (queue full)"))
        // A 30-byte HEALTH (older Desk Pod) adds no line.
        model.onLocalRoleReport(role(0x00FF))
        assertEquals("Firmware build: 0x00ff (older than this app supports; update to 0x0100 or newer)", BtrxDeskPodText.firmwareLine(model.snapshot()))
    }

    @Test
    fun droppedPacketsShowOnTheLiveFramesLineAndRestartWithTheSession() {
        assertEquals("Live frames: none", BtrxDeskPodText.liveFramesLine(model.snapshot()))
        model.onPacketDropped(encrypted = true); model.onPacketDropped(encrypted = true); model.onPacketDropped(encrypted = false)
        model.onFrame(P.decodeFrame(P.encodeFrame(P.HEALTH, payload = ByteArray(30))), meta(desk))
        val s = model.snapshot()
        assertEquals(2, s.droppedEncrypted); assertEquals(1, s.droppedOtherPort)
        assertEquals("Live frames: HEALTH=1; dropped: encrypted=2 other-port=1", BtrxDeskPodText.liveFramesLine(s))
        model.attach(FakeSender())
        assertEquals("Live frames: none", BtrxDeskPodText.liveFramesLine(model.snapshot()))
    }

    @Test
    fun linkLineShowsReconnectAttemptsAndTheLastFailure() {
        fun line(link: BtrxDeskPodModel.LinkStatus): String { model.setLink(link); return BtrxDeskPodText.linkLine(model.snapshot()) }
        val bt = BtrxDeskPodModel.LinkKind.BLUETOOTH
        assertEquals("Bluetooth: connecting", line(BtrxDeskPodModel.LinkStatus(bt, BtrxDeskPodModel.LinkPhase.CONNECTING)))
        assertEquals("Bluetooth: waiting to retry; reconnecting, attempt 2 (last failure: connection failed (status 8))",
            line(BtrxDeskPodModel.LinkStatus(bt, BtrxDeskPodModel.LinkPhase.RETRY_WAIT, failedAttempts = 1, lastFailure = "connection failed (status 8)")))
        assertEquals("Bluetooth: connecting; reconnecting, attempt 10 (last failure: connection failed (status 147))",
            line(BtrxDeskPodModel.LinkStatus(bt, BtrxDeskPodModel.LinkPhase.CONNECTING, failedAttempts = 9, lastFailure = "connection failed (status 147)")))
        assertEquals("Bluetooth: connected", line(BtrxDeskPodModel.LinkStatus(bt, BtrxDeskPodModel.LinkPhase.READY, failedAttempts = 0)))
        assertEquals("Bluetooth: off", line(BtrxDeskPodModel.LinkStatus(bt, BtrxDeskPodModel.LinkPhase.OFF, failedAttempts = 3, lastFailure = "x")))
    }

    /**
     * Field, Nov 16 (BT RX Desk Pod serial log): Drone Pod A !018968be sends 40-byte STEER
     * (two extra sources) and 34-byte HEALTH (build 0x0104) through Desk Pod !66c842ca. The whole
     * path (Meshtastic FromRadio -> BtrxClient -> model) must keep them for the paired pod.
     */
    @Test
    fun build0104FramesFromPairedPod018968beReachTheScreenThroughTheClient() {
        model.restorePairedPod(BtrxDeskPodModel.PairedPod(pod, 0x45D35AF0L, 0x4C8C125BL, 0x351C2971L))
        model.setLink(BtrxDeskPodModel.LinkStatus(BtrxDeskPodModel.LinkKind.USB, BtrxDeskPodModel.LinkPhase.READY))
        val out = mutableListOf<ByteArray>()
        val quiet = object : BtrxClient.Logger {
            override fun info(message: String) {}
            override fun warn(message: String) { throw AssertionError("client warned: $message") }
            override fun debug(message: String) {}
        }
        val client = BtrxClient(
            send = { out += it }, log = quiet,
            listener = object : BtrxClient.Listener {
                override fun onConfigured(localNode: Long) = model.onConfigured(localNode)
                override fun onLocalRoleReport(report: P.RoleReport) = model.onLocalRoleReport(report)
                override fun onFrame(frame: P.Frame, meta: BtrxClient.RadioMeta) = model.onFrame(frame, meta)
                override fun onPacketDropped(encrypted: Boolean) = model.onPacketDropped(encrypted)
            },
            random = java.util.Random(1), clock = { now },
        )
        client.start()
        val msgs = mutableListOf<ByteArray>()
        MeshtasticSerial.StreamDecoder({ msgs += it }).also { d -> out.forEach { d.feed(it) } }
        val configId = MeshtasticSerial.ProtoReader(msgs.single()).let { r -> r.tag(); r.varint() }
        fun feed(proto: ByteArray) = client.onBytes(MeshtasticSerial.frame(proto))
        feed(MeshtasticSerial.ProtoWriter().bytesField(3, MeshtasticSerial.ProtoWriter().varint(1, desk).bytes()).bytes())
        feed(MeshtasticSerial.ProtoWriter().varint(7, configId).bytes())
        now += BtrxClient.SERIAL_QUIET_GAP_MS
        client.checkQuietGap()

        val steer27 = P.steerPayload(P.Steer(steerSeq = 9, flags = P.STEER_F_TARGET or P.STEER_F_IMU_OK, windowEndMs = 145_298L, ageMs = 61,
            srcId = 1, rssiPort = -37, rssiStbd = -30, peakRssi = -25, hitsPort = 7, hitsStbd = 7, companyId = 0, devClass = 0, addrType = 0,
            macLsbFirst = byteArrayOf(1, 2, 3, 4, 5, 6), rollX2 = 5, pitchX2 = 6))
        val steer40 = steer27 + byteArrayOf(0x12) +
            byteArrayOf(2, 0, (-50).toByte(), (-55).toByte(), 0x33, (1 shl 2).toByte()) +
            byteArrayOf(3, 0, (-70).toByte(), (-128).toByte(), 0x01, (2 shl 2).toByte())
        assertEquals(40, steer40.size)
        val health34 = ByteArray(34).also { P.putU32(it, 0, 300); it[4] = 0x04; it[5] = 0x01; it[6] = 2; it[8] = 1; it[29] = 1; P.putU32(it, 30, 0) }
        fun fromPod(type: Int, payload: ByteArray, seq: Int) = MeshtasticSerialTest.fromRadioPacket(
            pod, 0xFFFFFFFFL, -68, 13f, P.PRIVATE_APP_PORT, P.encodeFrame(type, seq = seq, podId = 0x45D35AF0L, payload = payload))
        feed(fromPod(P.STEER, steer40, 1))
        feed(fromPod(P.HEALTH, health34, 2))
        feed(fromPod(P.DETECT, P.decodeFrame(P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("DETECT"))).payload, 3))

        assertEquals(1, client.frameCount("STEER"))
        assertEquals(1, client.frameCount("HEALTH"))
        assertFalse(client.summary(), client.summary().contains("rejected"))
        val s = model.snapshot()
        assertEquals(1, s.liveCounts["STEER"]); assertEquals(1, s.liveCounts["HEALTH"]); assertEquals(1, s.liveCounts["DETECT"])
        assertEquals(pod, s.dronePodHealth!!.node)
        assertEquals(0x0104, s.dronePodHealth!!.health.buildHash)
        assertEquals(1, s.detectsLastMinute)
        assertEquals(0, s.pairedPodIdMismatches)
        assertEquals(0, s.otherPodNodes)
        assertTrue(s.lastSteer != null)
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

    @Test
    fun learnMoreLinkOpensTheBtrxPageAtTheTopOfThePane() {
        assertEquals("Learn more about BTRX", BtrxDeskPodText.LEARN_MORE)
        assertEquals("https://rid2caltopo.com/btrx", BtrxDeskPodText.LEARN_MORE_URL)
        val wd = java.io.File(requireNotNull(System.getProperty("user.dir")))
        val rel = "app/src/main/java/org/ncssar/rid2caltopo/ui/DeskPodScreen.kt"
        val src = listOf(java.io.File(wd, rel), java.io.File(wd.parentFile ?: wd, rel)).first { it.isFile }.readText()
        val link = src.indexOf("LearnMoreLink()")
        assertTrue(link in 0 until src.indexOf("SectionTitle(\"Connection\")"))
        assertTrue(src.contains("Intent.ACTION_VIEW"))
    }

    @Test
    fun btrxOpensFromDeveloperToolsNotTheMainMenu() {
        val wd = java.io.File(requireNotNull(System.getProperty("user.dir")))
        val rel = "app/src/main/java/org/ncssar/rid2caltopo/ui/MainScreen.kt"
        val src = listOf(java.io.File(wd, rel), java.io.File(wd.parentFile ?: wd, rel))
            .first { it.isFile }.readText()
        assertFalse(src.contains("DropdownMenuItem(text = { Text(\"BTRX\") }"))
        val tools = src.indexOf("title = { Text(\"Developer Tools\") }")
        assertTrue(tools > 0)
        val entry = src.indexOf("BtrxDeskPodText.TITLE", tools)
        assertTrue(entry > tools)
        assertTrue(src.substring(tools, entry).contains("showDeskPod = true"))
        assertTrue(src.contains("DeskPodScreen(onDismiss = { showDeskPod = false })"))
    }

    // ---- firmware 0x0106: re-pair, own aircraft (saved), HEALTH 44 ----

    private val pod0106 = BtrxDeskPodModel.PairedPod(pod, 0x45D35AF0L, 0x4C8C125BL, 0x351C2971L)

    private fun podHealth(build: Int, size: Int = 44, ext: ((ByteArray) -> Unit)? = null) {
        val p = ByteArray(size).also { P.putU32(it, 0, 500); it[4] = build.toByte(); it[5] = (build shr 8).toByte(); it[6] = P.ROLE_STARBOARD.toByte(); it[8] = 1 }
        ext?.invoke(p)
        model.onFrame(P.decodeFrame(P.encodeFrame(P.HEALTH, seq = ++podSeq, podId = pod0106.podId, payload = p)), meta(pod))
    }

    /** A 6-byte FILTER_ACK (build 0x0106), or the 8-byte one (0x0107) when [srcId] is given. */
    private fun filterAck(op: Int, ok: Int, stored: Int, from: Long = pod, srcId: Int? = null) =
        model.onFrame(P.decodeFrame(P.encodeFrame(P.FILTER_ACK, seq = ++podSeq, podId = pod0106.podId,
            flags = if (ok == 0) P.F_ERROR else 0,
            payload = byteArrayOf(1, op.toByte(), ok.toByte(), 0x34, 0x12, stored.toByte()) +
                (srcId?.let { byteArrayOf(it.toByte(), (it shr 8).toByte()) } ?: ByteArray(0)))), meta(from))

    @Test
    fun repairSendsPairOpenAndDeskPairThenPairsTheSamePodAgain() {
        model.restorePairedPod(pod0106)
        podHealth(0x0106)
        sender.sent.clear()
        model.repairDronePod()
        assertEquals(2, sender.sent.size)
        val (to1, f1) = sender.sent[0]; val (to2, f2) = sender.sent[1]
        assertEquals(pod, to1)
        P.decodeFrame(f1).let { assertEquals(P.PAIR_OPEN, it.type); assertEquals(pod0106.podId, it.podId); assertEquals(0, it.payload.size) }
        assertEquals(desk, to2)
        assertEquals(P.DESK_PAIR, P.decodeFrame(f2).type)
        val p0 = model.snapshot().pairing
        assertEquals(PairStep.WAIT_BUTTON, p0.step); assertTrue(p0.repair)
        assertEquals("Desk Pod opens its pairing window", BtrxDeskPodText.steps(p0)[0].title)
        assertEquals(pod0106, model.snapshot().pairedPod)   // still paired while re-pairing
        deskPair(1)                                       // the Desk Pod answers (source 2 in the field)
        assertEquals(PairStep.WAIT_NOD, step)
        assertEquals("Drone Pod asks to pair", BtrxDeskPodText.steps(model.snapshot().pairing)[1].title)
        pairReq(from = 0x0BADBEEFL)                       // another pod: not while re-pairing ours
        assertEquals(PairStep.WAIT_NOD, step)
        pairReq()
        assertEquals(PairStep.POD_FOUND, step)
        model.confirmPod()
        assertEquals(PairStep.CONFIRMING, step)
        pairAck()
        assertEquals(PairStep.PAIRED, step)
        assertEquals("Drone Pod re-paired.", model.snapshot().pairing.message)
        assertEquals(pod, model.snapshot().pairedPod!!.node)
    }

    @Test
    fun repairFailureKeepsThePodPaired() {
        model.restorePairedPod(pod0106)
        model.repairDronePod()
        advance(BtrxDeskPodModel.REPAIR_WINDOW_WAIT_MS + 1000)
        val p = model.snapshot().pairing
        assertEquals(PairStep.FAILED, p.step)
        assertTrue(p.message, p.message.endsWith(BtrxDeskPodModel.REPAIR_KEPT))
        assertEquals(pod0106, model.snapshot().pairedPod)
        // Cancel also leaves it paired.
        model.repairDronePod()
        deskPair(1)
        model.cancelPairing()
        assertEquals(pod0106, model.snapshot().pairedPod)
    }

    @Test
    fun repairNeedsAPairedPodAndALink() {
        sender.sent.clear()
        model.repairDronePod()
        assertEquals(PairStep.FAILED, step)
        assertEquals(BtrxDeskPodModel.REPAIR_NOT_PAIRED, model.snapshot().pairing.message)
        model.clearPairingResult()
        model.restorePairedPod(pod0106)
        sender.isReady = false
        model.repairDronePod()
        assertEquals(PairStep.FAILED, step)
        assertTrue(sender.sent.isEmpty())
        // startPairing still refuses with a pod paired (Replace or Re-pair instead)
        sender.isReady = true
        model.clearPairingResult()
        model.startPairing()
        assertEquals(PairStep.IDLE, step)
    }

    @Test
    fun repairOfAnOlderPodSendsOnlyTheDeskWindowAndAsksForANod() {
        model.restorePairedPod(pod0106)
        podHealth(0x0105, size = 34)
        sender.sent.clear()
        model.repairDronePod()
        assertEquals(listOf(P.DESK_PAIR), sender.sent.map { P.decodeFrame(it.second).type })
        deskPair(1)
        assertEquals("Nod the drone pod twice", BtrxDeskPodText.steps(model.snapshot().pairing)[1].title)
    }

    @Test
    fun ownAircraftSavedOnBuild0106() {
        model.restorePairedPod(pod0106)
        model.setLink(BtrxDeskPodModel.LinkStatus(BtrxDeskPodModel.LinkKind.USB, BtrxDeskPodModel.LinkPhase.READY))
        assertFalse(model.snapshot().savedIgnoreSupported)
        sender.sent.clear()
        model.ignoreSourceSaved(0x148C)
        assertTrue("older or unknown build: nothing sent", sender.sent.isEmpty())
        assertTrue(model.snapshot().filterMessage!!.contains("0x0106"))
        podHealth(0x0105, size = 34)
        assertFalse(model.snapshot().savedIgnoreSupported)
        podHealth(0x0106)
        assertTrue(model.snapshot().savedIgnoreSupported)
        model.ignoreSourceSaved(0xF1A3)
        val f = P.decodeFrame(sender.sent.single().second)
        assertEquals(pod, sender.sent.single().first)
        assertEquals(P.STEER_MUTE, f.type)
        assertArrayEquals(byteArrayOf(3, 0xA3.toByte(), 0xF1.toByte()), f.payload)
        assertEquals(listOf(0xF1A3), model.snapshot().filterPending.map { it.srcId })
        assertTrue(model.snapshot().filterPendingFor(0xF1A3))
        model.ignoreSourceSaved(0xF1A3)                  // one at a time per source
        assertEquals(1, sender.sent.size)
        filterAck(P.FILTER_OP_REMOVE, 1, 1)              // not the op we asked for
        assertTrue(model.snapshot().savedIgnores.isEmpty())
        filterAck(P.FILTER_OP_ADD, 1, 1, from = 0x0BADBEEFL) // another pod's answer is not ours
        assertTrue(model.snapshot().savedIgnores.isEmpty())
        filterAck(P.FILTER_OP_ADD, 1, 1)
        assertEquals(listOf(0xF1A3), model.snapshot().savedIgnores)
        assertTrue(model.snapshot().filterPending.isEmpty())
        assertEquals(listOf(listOf(0xF1A3)), savedLists)
        assertTrue(model.snapshot().filterMessage!!.startsWith("Saved source F1A3 on the Drone Pod"))
        // not saved: ok=0
        model.ignoreSourceSaved(0x0002)
        filterAck(P.FILTER_OP_ADD, 0, 1)
        assertEquals(listOf(0xF1A3), model.snapshot().savedIgnores)
        assertTrue(model.snapshot().filterMessage!!.contains("did not save"))
        // remove
        model.unignoreSourceSaved(0xF1A3)
        assertArrayEquals(byteArrayOf(4, 0xA3.toByte(), 0xF1.toByte()), P.decodeFrame(sender.sent.last().second).payload)
        filterAck(P.FILTER_OP_REMOVE, 1, 0)
        assertTrue(model.snapshot().savedIgnores.isEmpty())
        assertEquals(emptyList<Int>(), savedLists.last())
        // no answer
        model.ignoreSourceSaved(0x0003)
        advance(BtrxDeskPodModel.FILTER_ACK_TIMEOUT_MS + 1000)
        assertTrue(model.snapshot().filterPending.isEmpty())
        assertEquals("No answer from the Drone Pod for source 0003. Try again.", model.snapshot().filterMessage)
    }

    private fun savedIgnoreReady(saved: List<Int> = emptyList(), build: Int = 0x0106) {
        model.restorePairedPod(pod0106, saved)
        model.setLink(BtrxDeskPodModel.LinkStatus(BtrxDeskPodModel.LinkKind.USB, BtrxDeskPodModel.LinkPhase.READY))
        podHealth(build)
        sender.sent.clear()
    }

    @Test
    fun sixByteAcksMatchTheOldestWaitingRequestOfThatOp() {
        savedIgnoreReady(listOf(0x0003))
        model.ignoreSourceSaved(0x0001)
        model.ignoreSourceSaved(0x0002)
        model.unignoreSourceSaved(0x0003)
        assertEquals(3, sender.sent.size)
        assertEquals(listOf(0x0001, 0x0002, 0x0003), model.snapshot().filterPending.map { it.srcId })
        filterAck(P.FILTER_OP_REMOVE, 1, 0)             // the op 4 answer, though sent last
        assertEquals(listOf(0x0001, 0x0002), model.snapshot().filterPending.map { it.srcId })
        assertTrue(model.snapshot().savedIgnores.isEmpty())
        filterAck(P.FILTER_OP_ADD, 1, 1)
        assertEquals(listOf(0x0001), model.snapshot().savedIgnores)
        filterAck(P.FILTER_OP_ADD, 0, 1)
        assertEquals(listOf(0x0001), model.snapshot().savedIgnores)
        assertTrue(model.snapshot().filterMessage!!.contains("did not save source 0002"))
        assertTrue(model.snapshot().filterPending.isEmpty())
    }

    @Test
    fun eightByteAcksMatchByEchoedSrcId() {
        savedIgnoreReady(build = 0x0107)
        model.ignoreSourceSaved(0x0001)
        model.ignoreSourceSaved(0x0002)
        filterAck(P.FILTER_OP_ADD, 1, 1, srcId = 0x0002)  // out of order
        assertEquals(listOf(0x0002), model.snapshot().savedIgnores)
        assertEquals(listOf(0x0001), model.snapshot().filterPending.map { it.srcId })
        filterAck(P.FILTER_OP_ADD, 1, 2, srcId = 0)       // a FILTER_LIST answer: not ours
        filterAck(P.FILTER_OP_REPLACE, 1, 2, srcId = 0x0001) // op 0: not ours
        filterAck(P.FILTER_OP_ADD, 1, 2, srcId = 0x0009)  // no request for 0x0009
        filterAck(P.FILTER_OP_REMOVE, 1, 2, srcId = 0x0001) // wrong op
        assertEquals(listOf(0x0001), model.snapshot().filterPending.map { it.srcId })
        assertEquals(listOf(0x0002), model.snapshot().savedIgnores)
        filterAck(P.FILTER_OP_ADD, 1, 2, srcId = 0x0001)
        assertEquals(listOf(0x0001, 0x0002), model.snapshot().savedIgnores)
        assertTrue(model.snapshot().filterPending.isEmpty())
        // 0x0107: op 3 for an already-saved id answers ok and it stays saved
        model.ignoreSourceSaved(0x0001)
        filterAck(P.FILTER_OP_ADD, 1, 2, srcId = 0x0001)
        assertEquals(listOf(0x0001, 0x0002), model.snapshot().savedIgnores)
    }

    @Test
    fun pendingRequestsAreCappedAndTimeOutEachOnItsOwn() {
        savedIgnoreReady()
        (1..BtrxDeskPodModel.MAX_PENDING_FILTERS + 1).forEach { model.ignoreSourceSaved(it) }
        assertEquals(BtrxDeskPodModel.MAX_PENDING_FILTERS, sender.sent.size)
        assertEquals("Waiting for the Drone Pod to answer earlier changes.", model.snapshot().filterMessage)
        advance(5_000)
        model.ignoreSourceSaved(0x0100)                 // still full
        filterAck(P.FILTER_OP_ADD, 1, 1)
        model.ignoreSourceSaved(0x0100)
        assertEquals(BtrxDeskPodModel.MAX_PENDING_FILTERS + 1, sender.sent.size)
        advance(BtrxDeskPodModel.FILTER_ACK_TIMEOUT_MS - 4_000)  // the first three expire, 0x0100 does not
        assertEquals(listOf(0x0100), model.snapshot().filterPending.map { it.srcId })
        model.detach(sender, "test")
        assertTrue(model.snapshot().filterPending.isEmpty())
    }

    @Test
    fun removeOfASavedButAbsentSourceOnBuild0106KeepsTheRow() {
        savedIgnoreReady(listOf(0x148C))                 // saved earlier; no longer heard
        model.unignoreSourceSaved(0x148C)
        // reuses the src_id op 3 was sent with
        assertArrayEquals(byteArrayOf(4, 0x8C.toByte(), 0x14), P.decodeFrame(sender.sent.single().second).payload)
        filterAck(P.FILTER_OP_REMOVE, 0, 1)              // 0x0106: not in the live table
        assertEquals(listOf(0x148C), model.snapshot().savedIgnores)
        assertTrue(savedLists.isEmpty())
        val msg = model.snapshot().filterMessage!!
        assertTrue(msg, msg.contains("no longer tracks source 148C") && msg.contains("0x0107") && msg.contains("stays in this list"))
        assertTrue(model.snapshot().filterPending.isEmpty())
    }

    @Test
    fun removeOfASavedButAbsentSourceOnBuild0107() {
        savedIgnoreReady(listOf(0x148C, 0x2000), build = 0x0107)
        model.unignoreSourceSaved(0x148C)
        filterAck(P.FILTER_OP_REMOVE, 1, 1, srcId = 0x148C) // 0x0107 removes the saved entry
        assertEquals(listOf(0x2000), model.snapshot().savedIgnores)
        assertTrue(model.snapshot().filterMessage!!.startsWith("Removed source 148C"))
        model.unignoreSourceSaved(0x2000)
        filterAck(P.FILTER_OP_REMOVE, 0, 0, srcId = 0x2000) // nothing under that id on the pod
        assertTrue(model.snapshot().savedIgnores.isEmpty())
        assertEquals(listOf(listOf(0x2000), emptyList<Int>()), savedLists)
        assertTrue(model.snapshot().filterMessage!!.contains("had nothing saved under source 2000"))
    }

    @Test
    fun deskPairSourceIsTheDeskPodPairingState() {
        val closed = BtrxDeskPodText.healthLines(model.snapshot())
        assertTrue(closed.toString(), "Desk Pod pairing window: closed" in closed)
        deskPair(1, seconds = 60, source = P.DESK_SRC_LINK)
        assertEquals(P.DESK_SRC_LINK, model.snapshot().windowSource)
        assertTrue("Desk Pod pairing window: open, 60 s left (phone/USB-opened)" in BtrxDeskPodText.healthLines(model.snapshot()))
        deskPair(1, seconds = 60, source = P.DESK_SRC_PAIR_OPEN)
        assertTrue("Desk Pod pairing window: open, 60 s left (app-opened)" in BtrxDeskPodText.healthLines(model.snapshot()))
        deskPair(0)
        assertEquals(0, model.snapshot().windowSource)
        assertEquals("button", BtrxDeskPodText.deskPairSourceText(P.DESK_SRC_BUTTON))
    }

    @Test
    fun savedIgnoresRestoreWithThePodAndClearWhenItIsReplaced() {
        model.restorePairedPod(pod0106, listOf(0x148C, 0, 0x10000, 7))
        assertEquals(listOf(7, 0x148C), model.snapshot().savedIgnores)
        model.forgetPairedPod()
        assertTrue(model.snapshot().savedIgnores.isEmpty())
        assertEquals(emptyList<Int>(), savedLists.last())
        model.restorePairedPod(null, listOf(5))
        assertTrue(model.snapshot().savedIgnores.isEmpty())
    }

    @Test
    fun health44LinesForBothPods() {
        model.restorePairedPod(pod0106)
        podHealth(0x0106) { p ->
            P.putU32(p, 34, 0); p[38] = P.PAIR_SRC_NOD.toByte(); p[39] = 38; p[41] = 12
            p[43] = (P.EXCL_OWN or P.EXCL_OTHER_BOARD or P.EXCL_DJI).toByte()
        }
        val desk44 = ByteArray(44).also { it[4] = 0x06; it[5] = 0x01; it[6] = P.ROLE_DESK_POD.toByte(); P.putU32(it, 34, 2) }
        model.onFrame(P.decodeFrame(P.encodeFrame(P.HEALTH, payload = desk44)), meta(desk))
        val lines = BtrxDeskPodText.healthLines(model.snapshot())
        assertTrue(lines.toString(), "Drone Pod last reset: power on or brown-out" in lines)
        assertTrue(lines.toString(), "Drone Pod pairing window last opened by: a nod (not paired yet); nods ignored while paired: 38" in lines)
        assertTrue(lines.toString(), "Drone Pod leaves out: its own board, the other board, DJI (not yet: the Desk Pod); 12 ad(s) left out per second" in lines)
        assertTrue(lines.toString(), "Desk Pod last reset: watchdog (0x2)" in lines)
        assertEquals(2L, model.snapshot().deskHealthExt!!.resetReason)
        assertEquals("nothing yet (its own board, the other board, the Desk Pod, DJI not known)", BtrxDeskPodText.excludeText(0))
        assertEquals("software reset, wake from pin (0x10004)", BtrxDeskPodText.resetReasonText(0x10004))
    }

    // ---- Desk Pod button confirmation (firmware 0x0108) ----

    private fun confirmReq(action: Int, token: Int, state: Int, timeoutS: Int = if (state == P.CFM_ASK) 15 else 0,
                           backlog: Boolean = false, version: Int = 1, from: Long = desk) =
        model.onFrame(P.decodeFrame(P.encodeFrame(P.CONFIRM_REQ, seq = ++deskSeq,
            payload = byteArrayOf(version.toByte(), action.toByte(), token.toByte(), (token shr 8).toByte(), timeoutS.toByte(), state.toByte()))), meta(from, backlog))

    /** CONFIRM_RSP frames sent to the Desk Pod, as (token, answer). */
    private fun confirmRsps(): List<Pair<Int, Int>> = sender.sent.filter { it.first == desk }.map { P.decodeFrame(it.second) }
        .filter { it.type == P.CONFIRM_RSP }.map { P.parseConfirmRsp(it.payload).let { r -> r.token to r.answer } }

    private fun buttonReady(build: Int = 0x0108) {
        model.setLink(BtrxDeskPodModel.LinkStatus(BtrxDeskPodModel.LinkKind.BLUETOOTH, BtrxDeskPodModel.LinkPhase.READY))
        model.onLocalRoleReport(role(build))
        model.setButtonPanelAvailable(true)
        sender.sent.clear()
    }

    @Test
    fun keepaliveEvery5sOnlyWhileReadyIn0108AndInFront() {
        buttonReady()
        advance(1_000)
        assertEquals(listOf(0 to P.CFM_KEEPALIVE), confirmRsps())
        assertArrayEquals(byteArrayOf(1, 0, 0, 3), P.decodeFrame(sender.sent.single().second).payload)
        assertEquals(0L, P.decodeFrame(sender.sent.single().second).podId)
        repeat(4) { advance(1_000) }
        assertEquals(1, confirmRsps().size)                       // 4 s after: not yet
        advance(1_000)
        assertEquals(2, confirmRsps().size)                       // 5 s
        repeat(10) { advance(1_000) }
        assertEquals(4, confirmRsps().size)                       // 10 s more: two more
        assertEquals(BtrxDeskPodModel.ButtonConfirmLink.ON, model.snapshot().buttonConfirmLink)
        // app in the background: none, and the button acts at once on the Desk Pod
        model.setButtonPanelAvailable(false)
        repeat(12) { advance(1_000) }
        assertEquals(4, confirmRsps().size)
        assertEquals(BtrxDeskPodModel.ButtonConfirmLink.NOT_IN_FRONT, model.snapshot().buttonConfirmLink)
        model.setButtonPanelAvailable(true)
        advance(1_000)
        assertEquals(5, confirmRsps().size)                       // at once when back in front
        // link not ready: none
        model.setLink(BtrxDeskPodModel.LinkStatus(BtrxDeskPodModel.LinkKind.BLUETOOTH, BtrxDeskPodModel.LinkPhase.RETRY_WAIT))
        repeat(12) { advance(1_000) }
        assertEquals(5, confirmRsps().size)
        assertEquals(BtrxDeskPodModel.ButtonConfirmLink.NO_LINK, model.snapshot().buttonConfirmLink)
    }

    @Test
    fun noKeepaliveToADeskPodBefore0108() {
        buttonReady(0x0107)
        repeat(12) { advance(1_000) }
        assertTrue(confirmRsps().isEmpty())
        assertEquals(BtrxDeskPodModel.ButtonConfirmLink.OLD_FIRMWARE, model.snapshot().buttonConfirmLink)
        assertEquals("Desk Pod button: acts at once (confirmation needs Desk Pod firmware 0x0108)", BtrxDeskPodText.buttonConfirmLine(model.snapshot()))
        // no ROLE_REPORT yet: the Desk Pod's own HEALTH build counts
        model.attach(sender); model.onConfigured(desk)
        assertEquals(BtrxDeskPodModel.ButtonConfirmLink.OLD_FIRMWARE, model.snapshot().buttonConfirmLink)
        val desk44 = ByteArray(44).also { it[4] = 0x08; it[5] = 0x01; it[6] = P.ROLE_DESK_POD.toByte() }
        model.onFrame(P.decodeFrame(P.encodeFrame(P.HEALTH, payload = desk44)), meta(desk))
        advance(1_000)
        assertEquals(listOf(0 to P.CFM_KEEPALIVE), confirmRsps())
        // an ask proves the feature even when the reported build is older (e.g. a stale role)
        model.attach(sender); model.onConfigured(desk); model.onLocalRoleReport(role(0x0107)); sender.sent.clear()
        confirmReq(P.CFM_ACTION_DESK_PAIR, 9, P.CFM_ASK)
        advance(1_000)
        assertEquals(listOf(9 to P.CFM_SHOWN, 0 to P.CFM_KEEPALIVE), confirmRsps())
    }

    @Test
    fun askShowsThePanelAndAnswersShownAtOnce() {
        buttonReady()
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x1234, P.CFM_ASK)
        assertEquals(listOf(0x1234 to P.CFM_SHOWN), confirmRsps())  // before any tick: well inside 3 s
        assertArrayEquals(byteArrayOf(1, 0x34, 0x12, 2), P.decodeFrame(sender.sent.single().second).payload)
        val v = model.snapshot().buttonConfirm!!
        assertEquals(0x1234, v.token); assertFalse(v.factoryReset); assertEquals(15, v.secondsLeft)
        assertEquals(BtrxDeskPodText.CONFIRM_PAIR_TITLE, BtrxDeskPodText.confirmTitle(v))
        model.answerButtonConfirm(true)
        assertEquals(0x1234 to P.CFM_CONFIRM, confirmRsps().last())
        assertTrue(model.snapshot().buttonConfirm!!.confirmSent)
        model.answerButtonConfirm(true)                               // once only
        model.answerButtonConfirm(false)
        assertEquals(2, confirmRsps().size)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x1234, P.CFM_DONE)
        assertNull(model.snapshot().buttonConfirm)
        assertEquals("Desk Pod button: pairing opened (confirmed).", model.snapshot().buttonNotice!!.text)
        advance(BtrxButtonConfirm.NOTICE_MS)
        assertNull(model.snapshot().buttonNotice)                     // brief on the map
        assertNotNull(model.snapshot().buttonLastNotice)              // kept in the pane
    }

    @Test
    fun cancelAndDroppedAndDoneWithoutAskingClose() {
        buttonReady()
        confirmReq(P.CFM_ACTION_DESK_PAIR, 7, P.CFM_ASK)
        model.answerButtonConfirm(false)
        assertEquals(7 to P.CFM_CANCEL, confirmRsps().last())
        assertNull(model.snapshot().buttonConfirm)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 7, P.CFM_DROPPED)
        assertEquals("Desk Pod button: cancelled; nothing was changed.", model.snapshot().buttonNotice!!.text)
        // dropped by the Desk Pod (a reset hold replaces a waiting tap: state 2, then a new ask)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 8, P.CFM_ASK)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 8, P.CFM_DROPPED)
        confirmReq(P.CFM_ACTION_FACTORY_RESET, 9, P.CFM_ASK)
        assertEquals(9, model.snapshot().buttonConfirm!!.token)
        assertTrue(model.snapshot().buttonConfirm!!.factoryReset)
        assertEquals(BtrxDeskPodText.CONFIRM_RESET_TITLE, BtrxDeskPodText.confirmTitle(model.snapshot().buttonConfirm!!))
        // done without asking (state 3) for a token asked while the app could not show it
        model.answerButtonConfirm(false)
        model.setButtonPanelAvailable(false)
        sender.sent.clear()
        confirmReq(P.CFM_ACTION_DESK_PAIR, 10, P.CFM_ASK)
        assertNull(model.snapshot().buttonConfirm)
        assertTrue("not in front: no shown", confirmRsps().isEmpty())
        confirmReq(P.CFM_ACTION_DESK_PAIR, 10, P.CFM_DONE_NO_APP)
        assertEquals("Desk Pod button: pairing opened.", model.snapshot().buttonNotice!!.text)
        assertTrue(logs.any { it.contains("desk button: action=desk-pair token=10 state=done-no-app") })
    }

    @Test
    fun panelClosesAfter15sAndUnknownTokensAreIgnored() {
        buttonReady()
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0101, P.CFM_ASK)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0202, P.CFM_DONE)          // never asked here
        confirmReq(P.CFM_ACTION_FACTORY_RESET, 0x0303, P.CFM_DONE_NO_APP)
        assertEquals(0x0101, model.snapshot().buttonConfirm!!.token)
        assertNull(model.snapshot().buttonNotice)
        assertNull(model.snapshot().deskResetAtMs)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0101, P.CFM_ASK, version = 2)  // other version: ignored
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0404, P.CFM_ASK, backlog = true) // stale, from the connect backlog
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0505, P.CFM_ASK, from = pod)     // not the Desk Pod
        assertEquals(0x0101, model.snapshot().buttonConfirm!!.token)
        assertEquals(listOf(0x0101 to P.CFM_SHOWN), confirmRsps().filter { it.first != 0 })
        advance(14_000)
        assertEquals(1, model.snapshot().buttonConfirm!!.secondsLeft)
        advance(1_000)
        assertNull(model.snapshot().buttonConfirm)
        assertEquals("Desk Pod button: no answer in 15 s; nothing was changed.", model.snapshot().buttonNotice!!.text)
        model.answerButtonConfirm(true)                                 // nothing to answer
        assertFalse(confirmRsps().any { it.second == P.CFM_CONFIRM })
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0101, P.CFM_DROPPED)        // the Desk Pod's drop: quiet
        assertEquals("Desk Pod button: request dropped (no answer in time, or replaced); nothing was changed.", model.snapshot().buttonLastNotice!!.text)
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x0101, P.CFM_DROPPED)        // a repeat of the outcome
        assertEquals(1, logs.count { it.contains("token=257 state=dropped:") })
    }

    @Test
    fun confirmedFactoryResetAsksTheRoleAndSaysTheDeskPodIsUnpaired() {
        model.restorePairedPod(pod0106)
        buttonReady()
        confirmReq(P.CFM_ACTION_FACTORY_RESET, 0x2222, P.CFM_ASK)
        model.answerButtonConfirm(true)
        sender.sent.clear()
        confirmReq(P.CFM_ACTION_FACTORY_RESET, 0x2222, P.CFM_DONE)
        val s = model.snapshot()
        assertNull(s.buttonConfirm)
        assertEquals(now, s.deskResetAtMs)
        assertEquals(BtrxButtonConfirm.RESET_DONE, s.buttonNotice!!.text)
        assertEquals(listOf(P.ROLE_GET), sender.sent.filter { it.first == desk }.map { P.decodeFrame(it.second).type })
        assertEquals(pod0106, s.pairedPod)                               // the app's record stays: Re-pair uses it
        advance(BtrxButtonConfirm.NOTICE_MS + 1_000)
        assertNotNull("the reset notice stays longer", model.snapshot().buttonNotice)
        // the link is lost while a panel is up: it closes
        confirmReq(P.CFM_ACTION_DESK_PAIR, 0x2223, P.CFM_ASK)
        model.detach(sender, "test")
        assertNull(model.snapshot().buttonConfirm)
        assertNotNull(model.snapshot().deskResetAtMs)                    // until a pairing completes
    }
}

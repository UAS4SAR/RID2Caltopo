package org.ncssar.rid2caltopo.btrx

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.FromRadio
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.ProtoWriter

class BtrxClientTest {
    private val sent = mutableListOf<ByteArray>()
    private val infos = mutableListOf<String>()
    private val warns = mutableListOf<String>()
    private val debugs = mutableListOf<String>()
    private val refused = mutableListOf<Pair<Long, Int>>()
    private val logger = object : BtrxClient.Logger {
        override fun info(message: String) { infos += message }
        override fun warn(message: String) { warns += message }
        override fun debug(message: String) { debugs += message }
    }
    private var now = 50_000L
    private val client = BtrxClient(
        send = { sent += it },
        log = logger,
        listener = object : BtrxClient.Listener {
            override fun onVersionRefused(fromNode: Long, version: Int) { refused += fromNode to version }
        },
        random = Random(1),
        clock = { now },
    )

    private val deskNode = 0x0A0B0C0DL
    private val starboard = 0x4C8C125BL

    /** Decodes everything the client wrote back into ToRadio protobufs. */
    private fun sentMessages(): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val d = MeshtasticSerial.StreamDecoder({ out += it })
        sent.forEach { d.feed(it) }
        return out
    }

    private fun feed(proto: ByteArray) = client.onBytes(MeshtasticSerial.frame(proto))

    /** Handshake, then the USB quiet gap that ends the connect backlog (and sends ROLE_GET). */
    private fun handshake(endBacklog: Boolean = true) {
        client.start()
        val want = sentMessages().single()
        val configId = MeshtasticSerial.ProtoReader(want).let { r -> r.tag(); r.varint() }
        feed(ProtoWriter().bytesField(3, ProtoWriter().varint(1, deskNode).bytes()).bytes())
        feed(ProtoWriter().bytesField(10, byteArrayOf(8, 0)).bytes()) // a channel record, ignored
        feed(ProtoWriter().varint(7, configId).bytes())
        if (endBacklog) {
            now += BtrxClient.SERIAL_QUIET_GAP_MS
            client.checkQuietGap()
        }
    }

    private fun btrxPacket(from: Long, frame: ByteArray, port: Int = 256) =
        MeshtasticSerialTest.fromRadioPacket(from, 0xFFFFFFFFL, -60, 7.5f, port, frame)

    @Test
    fun startSendsWakeBytesThenWantConfig() {
        client.start()
        assertEquals(32, sent[0].size)
        assertTrue(sent[0].all { it == 0xC3.toByte() })
        val msgs = sentMessages()
        assertEquals(1, msgs.size)
        assertEquals(0x18, msgs[0][0].toInt()) // ToRadio field 3, varint
        assertEquals(BtrxClient.State.CONFIGURING, client.state)
    }

    @Test
    fun roleGetToTheLocalNodeFollowsTheUsbQuietGap() {
        handshake(endBacklog = false)
        assertEquals(BtrxClient.State.READY, client.state)
        assertEquals(deskNode, client.localNode)
        assertEquals(1, sentMessages().size) // only want_config_id: still in the connect burst
        now += BtrxClient.SERIAL_QUIET_GAP_MS - 1
        client.checkQuietGap()
        assertEquals(1, sentMessages().size)
        now += 1
        client.checkQuietGap()
        assertTrue(infos.any { it.startsWith("connect backlog drained after a 300 ms quiet gap: 0 frame(s)") })
        val toRadio = sentMessages().last()
        // ToRadio.packet -> MeshPacket
        val packetBytes = MeshtasticSerial.ProtoReader(toRadio).let { r -> assertEquals(1 to 2, r.tag()); r.lengthDelimited() }
        var to = -1L
        var frame: ByteArray? = null
        var port = -1
        val r = MeshtasticSerial.ProtoReader(packetBytes)
        while (r.hasMore()) {
            val (f, w) = r.tag()
            when (f) {
                2 -> to = r.fixed32()
                4 -> {
                    val d = MeshtasticSerial.ProtoReader(r.lengthDelimited())
                    while (d.hasMore()) {
                        val (df, dw) = d.tag()
                        when (df) { 1 -> port = d.varint().toInt(); 2 -> frame = d.lengthDelimited(); else -> d.skip(dw) }
                    }
                }
                else -> r.skip(w)
            }
        }
        assertEquals(deskNode, to)
        assertEquals(256, port)
        assertArrayEquals(P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("ROLE_GET")), frame)
    }

    @Test
    fun staleConfigCompleteIsIgnored() {
        client.start()
        feed(ProtoWriter().varint(7, 12345).bytes())
        assertEquals(BtrxClient.State.CONFIGURING, client.state)
    }

    @Test
    fun deskPodRoleReportIsRecorded() {
        handshake()
        val report = P.encodeFrame(P.ROLE_REPORT, seq = 4, payload = P.fromHex("030500000000015b128c4c00000000000000000002"))
        feed(MeshtasticSerialTest.fromRadioPacket(deskNode, deskNode, 0, 0f, 256, report))
        assertNotNull(client.localRole)
        assertEquals(P.ROLE_DESK_POD, client.localRole!!.role)
        assertTrue(infos.any { it.startsWith("Desk Pod confirmed: role=3 build_hash=0x0100 (supported)") })
    }

    @Test
    fun everyFrameIsDecodedIntoTheLogAndCounted() {
        handshake()
        repeat(3) { feed(btrxPacket(starboard, P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("DETECT")))) }
        feed(btrxPacket(starboard, P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("HEALTH"))))
        feed(btrxPacket(starboard, byteArrayOf(1, 2, 3), port = 1)) // text message port: ignored
        assertEquals(3, client.frameCount("DETECT"))
        assertEquals(1, client.frameCount("HEALTH"))
        assertTrue((infos + debugs).any { it.startsWith("rx from=!4c8c125b rssi=-60 snr=7.5 DETECT seq=201") })
        assertTrue(client.summary(), client.summary().contains("DETECT=3 HEALTH=1"))
        assertTrue(client.summary(), client.summary().contains("dropped: encrypted=0 other-port=1"))
    }

    /** MeshPacket whose payload_variant is encrypted (field 5): a channel the Desk Pod cannot read. */
    private fun encryptedPacket(from: Long, bytes: Int): ByteArray {
        val packet = ProtoWriter().fixed32(1, from).fixed32(2, 0xFFFFFFFFL).bytesField(5, ByteArray(bytes) { 0x5A }).fixed32(6, 7).bytes()
        return ProtoWriter().varint(1, 9).bytesField(2, packet).bytes()
    }

    @Test
    fun droppedPacketsAreCountedByKindAndTheFirstFiveLoggedAtDebug() {
        val dropped = mutableListOf<Boolean>()
        val c = BtrxClient(send = { sent += it }, log = logger, random = Random(1), clock = { now },
            listener = object : BtrxClient.Listener { override fun onPacketDropped(encrypted: Boolean) { dropped += encrypted } })
        c.onBytes(MeshtasticSerial.frame(encryptedPacket(0x018968BEL, 52)))
        c.onBytes(MeshtasticSerial.frame(btrxPacket(0x018968BEL, byteArrayOf(1, 2, 3), port = 67)))
        repeat(5) { c.onBytes(MeshtasticSerial.frame(encryptedPacket(0x018968BEL, 40))) }
        assertEquals(6 to 1, c.droppedCounts())
        assertEquals(listOf(true, false, true, true, true, true, true), dropped)
        val lines = debugs.filter { it.startsWith("dropped packet ") }
        assertEquals(lines.toString(), 5, lines.size)
        assertEquals("dropped packet 1/5 logged: payload_variant=encrypted portnum=- from=!018968be len=52 (encrypted for a channel the Desk Pod cannot read)", lines[0])
        assertEquals("dropped packet 2/5 logged: payload_variant=decoded portnum=67 from=!018968be len=3", lines[1])
        assertTrue(c.summary(), c.summary().contains("dropped: encrypted=6 other-port=1"))
        assertTrue(warns.isEmpty())
    }

    @Test
    fun healthAndDetectReachTheSessionLogFirstThreeThenOncePer10s() {
        handshake()
        val detect = P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("DETECT"))
        repeat(6) { feed(btrxPacket(starboard, detect)); now += 1_000 } // 6 within 6 s
        val rxDebug = { debugs.filter { it.startsWith("rx ") && it.contains(" DETECT ") } }
        assertEquals(3, rxDebug().size)
        assertEquals(3, infos.count { it.startsWith("rx ") && it.contains(" DETECT ") })
        now += 10_000
        feed(btrxPacket(starboard, detect))
        assertEquals(4, rxDebug().size)
        assertTrue(rxDebug().last(), rxDebug().last().endsWith("(+3 not logged since the last one)"))
        feed(btrxPacket(starboard, P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("HEALTH"))))
        assertEquals(1, debugs.count { it.startsWith("rx ") && it.contains(" HEALTH ") })
    }

    @Test
    fun steerIsLoggedAtDebugLevelWithOnlyTheAddressTail() {
        val debugs = mutableListOf<String>()
        val c = BtrxClient(
            send = { sent += it },
            log = object : BtrxClient.Logger {
                override fun info(message: String) { infos += message }
                override fun warn(message: String) { warns += message }
                override fun debug(message: String) { debugs += message }
            },
            listener = object : BtrxClient.Listener {},
            random = Random(1),
            clock = { now },
        )
        c.start()
        val want = sentMessages().last()
        val configId = MeshtasticSerial.ProtoReader(want).let { r -> r.tag(); r.varint() }
        c.onBytes(MeshtasticSerial.frame(ProtoWriter().bytesField(3, ProtoWriter().varint(1, deskNode).bytes()).bytes()))
        c.onBytes(MeshtasticSerial.frame(ProtoWriter().varint(7, configId).bytes()))
        now += BtrxClient.SERIAL_QUIET_GAP_MS
        c.checkQuietGap()
        c.onBytes(MeshtasticSerial.frame(btrxPacket(starboard, P.fromHex(BtrxProtocolTest.STEER_FRAME))))
        val line = debugs.single { it.startsWith("rx ") }
        assertTrue(line, line.contains("STEER seq=1"))
        assertTrue(line, line.contains("src=48879"))
        assertTrue(line, line.contains("[..00AA]"))
        assertFalse(line, line.contains("AA0000000055") || line.contains("aa0000000055"))
        assertTrue(infos.none { it.contains(" STEER ") })
        assertEquals(1, c.frameCount("STEER"))
    }

    @Test
    fun usbFramesUntilTheFirstQuietGapAfterConfigCompleteAreBacklog() {
        val metas = mutableListOf<BtrxClient.RadioMeta>()
        val c = BtrxClient(send = { sent += it }, log = logger, random = Random(2), clock = { now }, listener = object : BtrxClient.Listener {
            override fun onFrame(frame: P.Frame, meta: BtrxClient.RadioMeta) { metas += meta }
        })
        c.start()
        val want = sentMessages().single()
        val configId = MeshtasticSerial.ProtoReader(want).let { r -> r.tag(); r.varint() }
        val detect = P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("DETECT"))
        val oldReport = P.encodeFrame(P.ROLE_REPORT, seq = 4, payload = P.fromHex("030500000000015b128c4c00000000000000000002"))
        c.onBytes(MeshtasticSerial.frame(ProtoWriter().bytesField(3, ProtoWriter().varint(1, deskNode).bytes()).bytes()))
        c.onBytes(MeshtasticSerial.frame(btrxPacket(starboard, detect))) // before config_complete
        c.onBytes(MeshtasticSerial.frame(ProtoWriter().varint(7, configId).bytes()))
        now += 120
        c.onBytes(MeshtasticSerial.frame(btrxPacket(starboard, detect))) // queued burst
        now += 250 // gaps inside the burst are shorter than 300 ms
        c.onBytes(MeshtasticSerial.frame(MeshtasticSerialTest.fromRadioPacket(deskNode, deskNode, 0, 0f, 256, oldReport)))
        assertNull(c.localRole) // a ROLE_REPORT in the burst is stale
        assertTrue(infos.any { it.startsWith("stale ROLE_REPORT in the connect backlog ignored") })
        assertEquals(1, sentMessages().size) // no ROLE_GET yet
        now += 400 // quiet gap: the next bytes are live, and ROLE_GET went out first
        c.onBytes(MeshtasticSerial.frame(btrxPacket(starboard, detect)))
        assertEquals(listOf(true, true, true, false), metas.map { it.backlog })
        assertEquals(2, c.backlogCount("DETECT"))
        assertEquals(1, c.frameCount("DETECT"))
        assertEquals(2, sentMessages().size)
        assertTrue(infos.any { it.startsWith("connect backlog drained after a 300 ms quiet gap: 2 frame(s)") })
        assertTrue(c.summary(), c.summary().contains("frames: DETECT=1 backlog: DETECT=2 ROLE_REPORT=1"))
    }

    @Test
    fun deskPairFromTheDeskPodItselfIsDecoded() {
        handshake()
        val deskPair = P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("DESK_PAIR"))
        feed(MeshtasticSerialTest.fromRadioPacket(deskNode, deskNode, 0, 0f, 256, deskPair))
        assertEquals(1, client.frameCount("DESK_PAIR"))
        assertTrue(debugs.any { it.contains("DESK_PAIR seq=37") && it.contains("open=1 source=button seconds=60") })
    }

    @Test
    fun badCrcIsLoggedAndCountedNotDecoded() {
        handshake()
        val bad = P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("HEALTH"))
        bad[15] = (bad[15] + 1).toByte()
        feed(btrxPacket(starboard, bad))
        assertEquals(0, client.frameCount("HEALTH"))
        assertTrue(warns.any { it.contains("crc mismatch") })
        assertTrue(client.summary().contains("CRC_MISMATCH=1"))
    }

    @Test
    fun versionTwoIsRefusedAndThatBoardIsIgnored() {
        handshake()
        val v2 = P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("HEALTH"))
        v2[2] = 2
        val len = v2[10].toInt() and 0xFF
        val crc = P.crc16(v2, 2, 11 + len)
        v2[11 + len] = crc.toByte(); v2[12 + len] = (crc ushr 8).toByte()
        feed(btrxPacket(starboard, v2))
        feed(btrxPacket(starboard, P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("DETECT"))))
        assertEquals(listOf(starboard to 2), refused)
        assertEquals(0, client.frameCount("DETECT"))
        assertTrue(warns.any { it.contains("btrx version 2 refused") && it.contains("newer than this app") })
    }

    @Test
    fun noFramesAreDecodedAfterClose() {
        handshake()
        client.close()
        feed(btrxPacket(starboard, P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("DETECT"))))
        assertEquals(0, client.frameCount("DETECT"))
    }

    @Test
    fun parserSurvivesGarbageProtobuf() {
        handshake()
        feed(byteArrayOf(0x12, 0x7F, 0x01))
        assertTrue(warns.any { it.startsWith("serial: undecodable FromRadio") })
        assertEquals(FromRadio.Other(0), MeshtasticSerial.parseFromRadio(ByteArray(0)))
    }

    // ---- Bluetooth framing and the connect backlog ----

    private fun packetClient(clock: () -> Long, metas: MutableList<BtrxClient.RadioMeta>, out: MutableList<ByteArray>) = BtrxClient(
        send = { out += it }, log = logger, random = Random(3), framing = BtrxClient.Framing.PACKET,
        clock = clock,
        listener = object : BtrxClient.Listener {
            override fun onFrame(frame: P.Frame, meta: BtrxClient.RadioMeta) { metas += meta }
        },
    )

    private fun packetHandshake(c: BtrxClient, out: List<ByteArray>) {
        c.start()
        val configId = MeshtasticSerial.ProtoReader(out[0]).let { r -> r.tag(); r.varint() }
        c.onFromRadio(ProtoWriter().bytesField(3, ProtoWriter().varint(1, deskNode).bytes()).bytes())
        c.onFromRadio(ProtoWriter().varint(7, configId).bytes())
    }

    private fun isRoleGet(toRadio: ByteArray) = toRadio[0].toInt() == 0x0A && P.hex(toRadio).contains(P.hex(P.roleGetFrame(1)).substring(0, 8))

    @Test
    fun bluetoothFramingSendsBareProtobufsWithoutWakeBytes() {
        val out = mutableListOf<ByteArray>()
        val c = packetClient({ 0L }, mutableListOf(), out)
        c.start()
        assertEquals(1, out.size)
        assertEquals(0x18, out[0][0].toInt()) // ToRadio field 3 (want_config_id), no 0x94 0xC3 header
        val configId = MeshtasticSerial.ProtoReader(out[0]).let { r -> r.tag(); r.varint() }
        c.onFromRadio(ProtoWriter().bytesField(3, ProtoWriter().varint(1, deskNode).bytes()).bytes())
        c.onFromRadio(ProtoWriter().varint(7, configId).bytes())
        assertEquals(BtrxClient.State.READY, c.state)
        assertEquals(1, out.size) // ROLE_GET waits for the end of the connect backlog
        c.onFromRadio(ByteArray(0))
        assertEquals(0x0A, out.last()[0].toInt()) // ROLE_GET as a bare ToRadio.packet
        assertTrue(isRoleGet(out.last()))
    }

    @Test
    fun heartbeatsAreDistinct() {
        val out = mutableListOf<ByteArray>()
        val c = packetClient({ 0L }, mutableListOf(), out)
        packetHandshake(c, out)
        c.sendHeartbeat()
        c.sendHeartbeat()
        // Never nonce 1: Meshtastic answers it by broadcasting node info over LoRa.
        assertEquals("3a020802", P.hex(out[out.size - 2]))
        assertEquals("3a020803", P.hex(out.last()))
    }

    @Test
    fun heartbeatNonceSkipsZeroAndOneWhenItWraps() {
        assertEquals(2L, BtrxClient.nextHeartbeatNonce(1))
        assertEquals(0xFFFFFFFFL, BtrxClient.nextHeartbeatNonce(0xFFFFFFFEL))
        assertEquals(2L, BtrxClient.nextHeartbeatNonce(0xFFFFFFFFL))
        var n = 1L
        repeat(1000) { n = BtrxClient.nextHeartbeatNonce(n); assertTrue(n >= 2) }
    }

    @Test
    fun emptyFromRadioReadIsIgnored() {
        val c = packetClient({ 0L }, mutableListOf(), mutableListOf())
        c.start()
        c.onFromRadio(ByteArray(0))
        assertEquals(BtrxClient.State.CONFIGURING, c.state)
        assertTrue(warns.isEmpty())
    }

    @Test
    fun firstDrainAfterConfigCompleteIsBacklogUntilTheFirstEmptyRead() {
        var now = 10_000L
        val metas = mutableListOf<BtrxClient.RadioMeta>()
        val out = mutableListOf<ByteArray>()
        val c = packetClient({ now }, metas, out)
        packetHandshake(c, out)
        val detect = P.fromHex(BtrxProtocolTest.EXAMPLES.getValue("DETECT"))
        // The queued burst can take up to 3 s; time does not matter, the empty read does.
        now += 500; c.onFromRadio(btrxPacket(starboard, detect))
        now += 2_500; c.onFromRadio(btrxPacket(starboard, detect))
        c.onFromRadio(ByteArray(0))
        now += 100; c.onFromRadio(btrxPacket(starboard, detect))
        c.onFromRadio(ByteArray(0)) // later drains change nothing
        now += 3_000; c.onFromRadio(btrxPacket(starboard, detect))
        assertEquals(listOf(true, true, false, false), metas.map { it.backlog })
        assertEquals(2, c.backlogCount("DETECT"))
        assertEquals(2, c.frameCount("DETECT"))
        assertTrue(infos.contains("connect backlog drained at the first empty read: 2 frame(s) queued while R2C was away"))
        assertEquals(1, out.count(::isRoleGet))
    }

    // ---- the ROLE_REPORT wait (A5 Pro, Nov 12: a full Desk Pod queue took 10 s to drain) ----

    private val deskRoleReport = MeshtasticSerialTest.fromRadioPacket(deskNode, deskNode, 0, 0f, 256,
        P.encodeFrame(P.ROLE_REPORT, seq = 4, payload = P.fromHex("030500000000015b128c4c00000000000000000002")))

    @Test
    fun roleWaitStartsAtRoleGetNotAtConnect() {
        var now = 0L
        val out = mutableListOf<ByteArray>()
        val c = packetClient({ now }, mutableListOf(), out)
        c.start()
        assertEquals(BtrxClient.RoleCheck(BtrxClient.RoleWait.WAIT, BtrxClient.ROLE_POLL_MS), c.roleCheck(false))
        now = 10_000 // the config dump took 10 s
        val configId = MeshtasticSerial.ProtoReader(out[0]).let { r -> r.tag(); r.varint() }
        c.onFromRadio(ProtoWriter().bytesField(3, ProtoWriter().varint(1, deskNode).bytes()).bytes())
        c.onFromRadio(ProtoWriter().varint(7, configId).bytes())
        val steer = P.fromHex(BtrxProtocolTest.STEER_FRAME)
        // 37 queued frames over 10 s: still draining, so no ROLE_GET and no retry yet.
        repeat(37) { now += 270; c.onFromRadio(btrxPacket(starboard, steer)); assertEquals(BtrxClient.RoleWait.WAIT, c.roleCheck(false).action) }
        assertTrue(c.connectBacklogPending)
        assertEquals(0, out.count(::isRoleGet))
        assertTrue(warns.none { it.contains("ROLE") })
        c.onFromRadio(ByteArray(0)) // drained: ROLE_GET goes out now
        assertEquals(1, out.count(::isRoleGet))
        now += 9_999
        assertEquals(BtrxClient.RoleCheck(BtrxClient.RoleWait.WAIT, 1), c.roleCheck(false))
        c.onFromRadio(deskRoleReport) // the live answer
        assertEquals(BtrxClient.RoleWait.DONE, c.roleCheck(false).action)
        assertTrue(infos.any { it.startsWith("Desk Pod confirmed: role=3 build_hash=0x0100 (supported) 30.0 s after connect") })
    }

    @Test
    fun roleGetIsRetriedOnceThenGivenUp() {
        var now = 0L
        val out = mutableListOf<ByteArray>()
        val c = packetClient({ now }, mutableListOf(), out)
        packetHandshake(c, out)
        c.onFromRadio(ByteArray(0))
        assertEquals(1, out.count(::isRoleGet))
        now += BtrxClient.ROLE_TIMEOUT_MS
        assertEquals(BtrxClient.RoleCheck(BtrxClient.RoleWait.RETRY, BtrxClient.ROLE_TIMEOUT_MS), c.roleCheck(false))
        assertEquals(2, out.count(::isRoleGet))
        now += BtrxClient.ROLE_TIMEOUT_MS - 1
        assertEquals(BtrxClient.RoleWait.WAIT, c.roleCheck(true).action)
        now += 1
        assertEquals(BtrxClient.RoleWait.GIVE_UP, c.roleCheck(true).action)
        assertEquals(2, out.count(::isRoleGet))
    }

    @Test
    fun aBacklogThatNeverEndsIsCutOffSoRoleGetStillGoesOut() {
        var now = 0L
        val metas = mutableListOf<BtrxClient.RadioMeta>()
        val out = mutableListOf<ByteArray>()
        val c = packetClient({ now }, metas, out)
        packetHandshake(c, out)
        now += BtrxClient.MAX_CONNECT_BACKLOG_MS - 1
        assertEquals(BtrxClient.RoleWait.WAIT, c.roleCheck(false).action)
        now += 1
        assertEquals(BtrxClient.RoleCheck(BtrxClient.RoleWait.WAIT, BtrxClient.ROLE_TIMEOUT_MS), c.roleCheck(false))
        assertFalse(c.connectBacklogPending)
        assertEquals(1, out.count(::isRoleGet))
        assertTrue(warns.any { it.startsWith("connect backlog still draining 30 s after config_complete") })
        c.onFromRadio(btrxPacket(starboard, P.fromHex(BtrxProtocolTest.STEER_FRAME)))
        assertFalse(metas.last().backlog)
    }

    @Test
    fun noConfigCompleteIsReportedAfterThirtySeconds() {
        var now = 0L
        val c = packetClient({ now }, mutableListOf(), mutableListOf())
        c.start()
        now += BtrxClient.CONFIG_TIMEOUT_MS - 1
        assertEquals(BtrxClient.RoleWait.WAIT, c.roleCheck(false).action)
        now += 1
        assertEquals(BtrxClient.RoleWait.NO_CONFIG, c.roleCheck(false).action)
    }

    @Test
    fun imuCalAckAndSteerFlagChangesShowAtEveryLogLevel() {
        val out = mutableListOf<ByteArray>()
        val c = packetClient({ 0L }, mutableListOf(), out)
        packetHandshake(c, out)
        c.onFromRadio(ByteArray(0))
        val ack = P.encodeFrame(P.IMU_CAL_ACK, seq = 5, payload = P.fromHex(BtrxProtocolTest.IMU_CAL_ACK_PAYLOAD))
        c.onFromRadio(btrxPacket(starboard, ack))
        assertTrue(debugs.any { it.contains("IMU_CAL_ACK") && it.contains("status=") && it.contains("gyro_bias_x10=") })
        val steer = P.decodeFrame(P.fromHex(BtrxProtocolTest.STEER_FRAME)).payload
        fun steerWith(flags: Int) = P.encodeFrame(P.STEER, seq = 6, payload = steer.copyOf().also { it[1] = flags.toByte() })
        c.onFromRadio(btrxPacket(starboard, steerWith(P.STEER_F_TARGET or P.STEER_F_IMU_OK)))
        c.onFromRadio(btrxPacket(starboard, steerWith(P.STEER_F_TARGET or P.STEER_F_IMU_OK)))
        c.onFromRadio(btrxPacket(starboard, steerWith(P.STEER_F_TARGET or P.STEER_F_IMU_OK or P.STEER_F_IMU_CAL)))
        val changes = debugs.filter { it.startsWith("steer flags from") }
        assertEquals(2, changes.size)
        assertTrue(changes[0].endsWith("(first) -> target,imu (imu_cal=no)"))
        assertTrue(changes[1].endsWith("target,imu -> target,imu,cal (imu_cal=yes)"))
    }

    @Test
    fun staleRoleReportInTheBacklogIsNotTaken() {
        val out = mutableListOf<ByteArray>()
        val roles = mutableListOf<P.RoleReport>()
        val c = BtrxClient(send = { out += it }, log = logger, random = Random(4), framing = BtrxClient.Framing.PACKET,
            listener = object : BtrxClient.Listener {
                override fun onLocalRoleReport(report: P.RoleReport) { roles += report }
            })
        packetHandshake(c, out)
        val report = P.encodeFrame(P.ROLE_REPORT, seq = 4, payload = P.fromHex("030500000000015b128c4c00000000000000000002"))
        val fromDesk = MeshtasticSerialTest.fromRadioPacket(deskNode, deskNode, 0, 0f, 256, report)
        c.onFromRadio(fromDesk)
        assertNull(c.localRole)
        assertTrue(roles.isEmpty())
        assertTrue(infos.any { it.startsWith("stale ROLE_REPORT in the connect backlog ignored") })
        c.onFromRadio(ByteArray(0))
        c.onFromRadio(fromDesk)
        assertEquals(P.ROLE_DESK_POD, c.localRole?.role)
        assertEquals(1, roles.size)
    }

    @Test
    fun toRadioMustFitOneBluetoothWrite() {
        val out = mutableListOf<ByteArray>()
        val c = packetClient({ 0L }, mutableListOf(), out)
        c.setAttMtu(247)
        packetHandshake(c, out)
        c.onFromRadio(ByteArray(0))
        val before = out.size
        c.sendFrame(starboard, ByteArray(BtrxClient.MAX_SEND_FRAME + 1))
        assertEquals(before, out.size)
        assertTrue(warns.any { it.startsWith("ERROR: btrx frame of 201 bytes not sent") })
        c.sendFrame(starboard, ByteArray(BtrxClient.MAX_SEND_FRAME))
        assertEquals(before + 1, out.size)
        assertTrue(out.last().size <= BtrxClient.MAX_TO_RADIO_BLE)
        // A smaller negotiated MTU lowers the limit for the whole ToRadio.
        c.setAttMtu(100)
        c.sendFrame(starboard, ByteArray(150))
        assertEquals(before + 1, out.size)
        assertTrue(warns.any { it.contains("at most 97 per Bluetooth write") })
        c.setAttMtu(64)
        assertTrue(warns.any { it.startsWith("ERROR: Bluetooth MTU 64 is too small") })
    }
}

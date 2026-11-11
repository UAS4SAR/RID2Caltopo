package org.ncssar.rid2caltopo.btrx

import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.FromRadio
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.ProtoWriter

class BtrxClientTest {
    private val sent = mutableListOf<ByteArray>()
    private val infos = mutableListOf<String>()
    private val warns = mutableListOf<String>()
    private val refused = mutableListOf<Pair<Long, Int>>()
    private val logger = object : BtrxClient.Logger {
        override fun info(message: String) { infos += message }
        override fun warn(message: String) { warns += message }
    }
    private val client = BtrxClient(
        send = { sent += it },
        log = logger,
        listener = object : BtrxClient.Listener {
            override fun onVersionRefused(fromNode: Long, version: Int) { refused += fromNode to version }
        },
        random = Random(1),
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

    private fun handshake() {
        client.start()
        val want = sentMessages().single()
        val configId = MeshtasticSerial.ProtoReader(want).let { r -> r.tag(); r.varint() }
        feed(ProtoWriter().bytesField(3, ProtoWriter().varint(1, deskNode).bytes()).bytes())
        feed(ProtoWriter().bytesField(10, byteArrayOf(8, 0)).bytes()) // a channel record, ignored
        feed(ProtoWriter().varint(7, configId).bytes())
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
    fun configCompleteSendsRoleGetToTheLocalNode() {
        handshake()
        assertEquals(BtrxClient.State.READY, client.state)
        assertEquals(deskNode, client.localNode)
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
        assertTrue(infos.any { it.startsWith("rx from=!4c8c125b rssi=-60 snr=7.5 DETECT seq=201") })
        assertTrue(client.summary(), client.summary().contains("DETECT=3 HEALTH=1"))
        assertTrue(client.summary(), client.summary().contains("other-port packets=1"))
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
}

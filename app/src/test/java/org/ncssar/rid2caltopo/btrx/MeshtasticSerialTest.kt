package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.FromRadio
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.ProtoWriter

class MeshtasticSerialTest {
    private fun hex(b: ByteArray) = BtrxProtocol.hex(b)

    @Test
    fun streamFrameIsMagicPlusBigEndianLength() {
        val framed = MeshtasticSerial.frame(ByteArray(300) { 7 })
        assertEquals("94c3012c", hex(framed.copyOf(4)))
        assertEquals(304, framed.size)
    }

    @Test
    fun wantConfigAndHeartbeatEncodings() {
        // field 3 varint 0x12345678
        assertEquals("18f8acd19101", hex(MeshtasticSerial.toRadioWantConfig(0x12345678)))
        // field 7, empty Heartbeat message
        assertEquals("3a00", hex(MeshtasticSerial.toRadioHeartbeat()))
    }

    @Test
    fun toRadioPacketEncodesPrivateAppData() {
        val frame = BtrxProtocol.roleGetFrame(1)
        val out = MeshtasticSerial.toRadioPacket(to = 0xA1B2C3D4L, packetId = 0x01020304L, portnum = 256, payload = frame)
        val data = "08800212" + "0d" + hex(frame)                // portnum=256, payload (13 bytes)
        val packet = "15d4c3b2a1" + "22" + "%02x".format(data.length / 2) + data + "3504030201" + "4803"
        assertEquals("0a" + "%02x".format(packet.length / 2) + packet, hex(out))
    }

    @Test
    fun parsesMyInfoConfigCompleteAndOtherRecords() {
        val myInfo = ProtoWriter().varint(1, 7).bytesField(3, ProtoWriter().varint(1, 0xDEADBEEFL).varint(8, 3).bytes()).bytes()
        assertEquals(FromRadio.MyInfo(0xDEADBEEFL), MeshtasticSerial.parseFromRadio(myInfo))
        val complete = ProtoWriter().varint(7, 42).bytes()
        assertEquals(FromRadio.ConfigComplete(42), MeshtasticSerial.parseFromRadio(complete))
        val channel = ProtoWriter().bytesField(10, byteArrayOf(8, 0)).bytes()
        assertEquals(FromRadio.Other(10), MeshtasticSerial.parseFromRadio(channel))
    }

    @Test
    fun parsesMeshPacketWithNegativeRssiAndFloatSnr() {
        val payload = BtrxProtocol.fromHex(BtrxProtocolTest.EXAMPLES.getValue("HEALTH"))
        val proto = fromRadioPacket(from = 0x4C8C125BL, to = 0xFFFFFFFFL, rssi = -87, snr = 6.25f, port = 256, payload = payload)
        val p = MeshtasticSerial.parseFromRadio(proto) as FromRadio.Packet
        assertEquals(0x4C8C125BL, p.from)
        assertEquals(0xFFFFFFFFL, p.to)
        assertEquals(-87, p.rxRssi)
        assertEquals(6.25f, p.rxSnr)
        assertEquals(256, p.portnum)
        assertArrayEquals(payload, p.payload)
    }

    @Test
    fun streamDecoderSkipsTextAndHandlesSplitReads() {
        val got = mutableListOf<String>()
        var noise = 0
        val d = MeshtasticSerial.StreamDecoder({ got += hex(it) }, { noise += it })
        val one = MeshtasticSerial.frame(byteArrayOf(0x38, 0x01))
        val two = MeshtasticSerial.frame(byteArrayOf(0x3a, 0x00))
        val stream = "INFO boot\r\n".toByteArray() + one + byteArrayOf(0x94.toByte(), 0x41) + two
        stream.forEach { d.feed(byteArrayOf(it)) }
        assertEquals(listOf("3801", "3a00"), got)
        assertTrue(noise >= 11)
    }

    @Test
    fun streamDecoderResyncsAfterAnImpossibleLength() {
        val got = mutableListOf<String>()
        val d = MeshtasticSerial.StreamDecoder({ got += hex(it) })
        d.feed(byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x7F, 0x00) + MeshtasticSerial.frame(byteArrayOf(0x38, 0x05)))
        assertEquals(listOf("3805"), got)
    }

    companion object {
        fun fromRadioPacket(from: Long, to: Long, rssi: Int, snr: Float, port: Int, payload: ByteArray): ByteArray {
            val data = ProtoWriter().varint(1, port.toLong()).bytesField(2, payload).bytes()
            val packet = ProtoWriter()
                .fixed32(1, from).fixed32(2, to).bytesField(4, data).fixed32(6, 99)
                .fixed32(7, 0).fixed32(8, java.lang.Float.floatToIntBits(snr).toLong() and 0xFFFFFFFFL)
                .varint(12, rssi.toLong()) // int32 negative: sign-extended 10-byte varint
                .bytes()
            return ProtoWriter().varint(1, 5).bytesField(2, packet).bytes()
        }
    }
}

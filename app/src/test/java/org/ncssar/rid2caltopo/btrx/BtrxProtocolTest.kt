package org.ncssar.rid2caltopo.btrx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * Vectors from the btrx R2C integration brief (section 5.3, checked with
 * protocol.py) and from firmware/tools/r2c_sim/test_protocol.py, which were
 * dumped from the firmware's btrx_protocol.cpp.
 */
class BtrxProtocolTest {
    private fun h(s: String) = P.fromHex(s)

    private fun decodeExample(name: String) = P.decodeFrame(h(EXAMPLES.getValue(name)))

    private fun rejection(buf: ByteArray): P.Rejection {
        try {
            P.decodeFrame(buf)
        } catch (e: P.FrameException) {
            return e.reason
        }
        fail("frame was accepted: ${P.hex(buf)}")
        throw AssertionError()
    }

    // ---- CRC and framing ----

    @Test
    fun crc16CcittFalseCheckValue() {
        assertEquals(0x29B1, P.crc16("123456789".toByteArray()))
    }

    @Test
    fun allBriefExamplesDecode() {
        EXAMPLES.forEach { (name, hex) ->
            val f = P.decodeFrame(h(hex))
            assertEquals(name, 1, f.version)
            assertEquals(name, name, f.typeName)
            if (name != "HELLO") assertEquals(name, 0L, f.podId)
        }
    }

    @Test
    fun briefExamplesReEncodeByteForByte() {
        EXAMPLES.forEach { (name, hex) ->
            val f = P.decodeFrame(h(hex))
            assertEquals(name, hex, P.hex(P.encodeFrame(f.type, f.seq, f.podId, f.flags, f.payload)))
        }
    }

    @Test
    fun roleGetEncodesToTheBriefExample() {
        assertEquals(EXAMPLES["ROLE_GET"], P.hex(P.roleGetFrame(1)))
        val f = decodeExample("ROLE_GET")
        assertEquals(P.ROLE_GET, f.type)
        assertEquals(1, f.seq)
        assertEquals(0, f.payload.size)
    }

    @Test
    fun anyOneByteChangeIsRejected() {
        EXAMPLES.forEach { (name, hex) ->
            val good = h(hex)
            for (i in good.indices) {
                val bad = good.copyOf()
                bad[i] = (bad[i].toInt() xor 0x01).toByte()
                val reason = rejection(bad)
                // The length byte can also make the frame look truncated or too long,
                // the version byte is refused before the CRC, and the preamble is checked first.
                val allowed = when (i) {
                    0, 1 -> setOf(P.Rejection.BAD_PREAMBLE)
                    2 -> setOf(P.Rejection.UNSUPPORTED_VERSION)
                    10 -> setOf(P.Rejection.CRC_MISMATCH, P.Rejection.TRUNCATED)
                    else -> setOf(P.Rejection.CRC_MISMATCH)
                }
                assertTrue("$name byte $i -> $reason", reason in allowed)
            }
        }
    }

    @Test
    fun payloadByteChangeFailsTheCrc() {
        val bad = h(EXAMPLES.getValue("DETECT"))
        bad[20] = (bad[20] + 1).toByte()
        assertEquals(P.Rejection.CRC_MISMATCH, rejection(bad))
        val badCrc = h(EXAMPLES.getValue("HELLO"))
        badCrc[badCrc.size - 1] = (badCrc[badCrc.size - 1].toInt() xor 0xFF).toByte()
        assertEquals(P.Rejection.CRC_MISMATCH, rejection(badCrc))
    }

    @Test
    fun versionTwoIsRefusedEvenWithAValidCrc() {
        val frame = h(EXAMPLES.getValue("DETECT"))
        frame[2] = 2
        val len = frame[10].toInt() and 0xFF
        val crc = P.crc16(frame, 2, 11 + len)
        frame[11 + len] = (crc and 0xFF).toByte()
        frame[12 + len] = (crc ushr 8).toByte()
        try {
            P.decodeFrame(frame)
            fail("version 2 accepted")
        } catch (e: P.FrameException) {
            assertEquals(P.Rejection.UNSUPPORTED_VERSION, e.reason)
            assertEquals(2, e.version)
        }
    }

    @Test
    fun shortAndTruncatedFramesAreRejected() {
        assertEquals(P.Rejection.TOO_SHORT, rejection(h("b75201200100000000")))
        val detect = h(EXAMPLES.getValue("DETECT"))
        assertEquals(P.Rejection.TRUNCATED, rejection(detect.copyOf(detect.size - 1)))
        val tooLong = h(EXAMPLES.getValue("ROLE_GET"))
        tooLong[10] = 221.toByte()
        assertEquals(P.Rejection.LENGTH_TOO_LARGE, rejection(tooLong))
    }

    @Test
    fun goldenHelloFrameFromFirmware() {
        val f = decodeExample("HELLO")
        assertEquals(P.HELLO, f.type)
        assertEquals(1, f.seq)
        assertEquals(0xA1B2C3D4L, f.podId)
    }

    // ---- 5.3 example payloads decode to the values shown ----

    @Test
    fun deskPairExample() {
        val f = decodeExample("DESK_PAIR")
        assertEquals(37, f.seq)
        assertEquals(P.DeskPair(open = 1, source = 1, secondsLeft = 60), P.parseDeskPair(f.payload))
    }

    @Test
    fun pairReqExample() {
        val r = P.parsePairReq(decodeExample("PAIR_REQ").payload)
        assertEquals(0L, r.podId)
        assertEquals(0x4C8C125BL, r.devA)
        assertEquals(0x351C2971L, r.devB)
        assertEquals(0x0100, r.buildA)
        assertEquals(0x0100, r.buildB)
        assertEquals(1, r.imuOk)
        assertEquals(P.ROLE_STARBOARD, r.roleA)
        assertEquals(P.ROLE_PORT, r.roleB)
        assertEquals("570b73e9475dfe3f", P.hex(r.nonce))
    }

    @Test
    fun pairAckExample() {
        val f = decodeExample("PAIR_ACK")
        assertEquals(P.F_IS_ACK, f.flags)
        assertEquals(P.PairAck(aOk = 1, bOk = 1, flags = 0), P.parsePairAck(f.payload))
    }

    @Test
    fun timePingExample() {
        val t = P.parseTime(decodeExample("TIME_PING").payload)
        assertEquals(1, t.pingSeq)
        assertEquals(0x12345678L, t.t1)
        assertEquals(0L, t.t2)
        assertEquals(0L, t.t3)
    }

    @Test
    fun detectExampleMatchesTheBrief() {
        val d = P.parseDetect(decodeExample("DETECT").payload)
        assertEquals(356522L, d.windowEndMs)
        assertEquals(2.0, d.rollX2 / 2.0, 0.0)
        assertEquals(-3.0, d.pitchX2 / 2.0, 0.0)
        assertTrue(d.imuOk)
        assertFalse(d.tilted)
        assertEquals(1, d.records.size)
        val r = d.records[0]
        assertEquals("11:22:33:44:55:66", r.address)
        assertEquals("5566", r.addressTail)
        assertEquals(1, r.addressType) // random static
        assertEquals(1, r.deviceClass) // Apple
        assertTrue(r.hasManufacturerData)
        assertFalse(r.longRange)
        assertEquals(-71, r.rssiPort)
        assertEquals(-64, r.rssiStbd)
        assertEquals(9, r.hits)
        assertEquals(850, r.ageMs)
        assertEquals(356522L - 850, d.windowEndMs - r.ageMs)
        assertEquals(0x004C, r.companyId)
        assertEquals(3, r.rxMask)
        assertEquals(4, r.mfgLen)
        assertEquals(4, r.mfg.size)
        assertEquals(0, r.uuid16)
        assertFalse(r.isRemoteId)
    }

    @Test
    fun healthExample() {
        val hl = P.parseHealth(decodeExample("HEALTH").payload)
        assertEquals(332L, hl.uptimeS)
        assertEquals(0x0100, hl.buildHash)
        assertEquals(P.ROLE_STARBOARD, hl.role)
        assertEquals(1, hl.confirmed)
        assertEquals(P.FLAG_SCANNING, hl.flags)
        assertEquals(24, hl.dieTempC)
        assertEquals(5, hl.advPerSPort)
        assertEquals(6, hl.advPerSStbd)
        assertEquals(-30, hl.loraRssi)
        assertEquals(12, hl.loraSnr)
        assertEquals(2, hl.txPolicy)
        assertEquals(1, hl.scanning)
    }

    // ---- payload vectors from test_protocol.py ----

    @Test
    fun healthPayloadFromFirmware() {
        val hl = P.parseHealth(h("0a00000000010300000000ffff00fc000000000000ecffffff0000000001"))
        assertEquals(10L, hl.uptimeS)
        assertEquals(P.ROLE_DESK_POD, hl.role)
        assertEquals(0xFFFF, hl.bLinkAgeS)
        assertEquals(-4, hl.dieTempC)
        assertEquals(-20, hl.clockOffsetMs)
        assertEquals(1, hl.scanning)
    }

    @Test
    fun roleReportPayloadFromFirmware() {
        val r = P.parseRoleReport(h("020200080000015b128c4c71291c35000000000000"))
        assertEquals(P.ROLE_STARBOARD, r.role)
        assertEquals(2, r.source)
        assertEquals(0, r.confirmed)
        assertEquals(P.FLAG_TX_INHIBIT, r.flags)
        assertEquals(0x0100, r.buildHash)
        assertEquals(0x4C8C125BL, r.devId)
        assertEquals(0x351C2971L, r.peerId)
        assertEquals(0L, r.podId)
        assertEquals(0, r.txPolicy)
    }

    @Test
    fun timeCtrlAckAndPairAckPayloadsFromFirmware() {
        assertEquals(300L, P.parseTime(h("070064000000c80000002c0100000000")).t3)
        val ack = P.parseCtrlAck(h("4001010300000004000000"))
        assertEquals(P.START, ack.ackedType)
        assertEquals(1, ack.ackedSeq)
        assertEquals(1, ack.ok)
        assertEquals(3L, ack.sessionId)
        assertEquals(4L, ack.actualMs)
        assertEquals(P.PairAck(1, 0, P.FLAG_PAIRING), P.parsePairAck(h("01001000")))
    }

    @Test
    fun pairReqAndConfirmFramesFromFirmware() {
        val req = P.decodeFrame(h("b75201100201000000001b0100000002000000030000000400050001020101020304050607082ac3"))
        assertEquals(2, req.seq)
        assertEquals(1L, req.podId)
        val fields = P.parsePairReq(req.payload)
        assertEquals(2L, fields.devA)
        assertEquals("0102030405060708", P.hex(fields.nonce))
        val confirm = P.decodeFrame(h(PAIR_CONFIRM_FIRMWARE))
        assertEquals(P.PAIR_CONFIRM, confirm.type)
        assertEquals(3, confirm.seq)
        assertEquals(9L, confirm.podId)
        assertEquals(61, confirm.payload.size)
    }

    @Test
    fun deskPairTooShortIsAPayloadError() {
        assertEquals(P.DeskPair(1, 1, 60), P.parseDeskPair(byteArrayOf(1, 1, 60, 0)))
        try {
            P.parseDeskPair(byteArrayOf(1, 1, 60))
            fail("short DESK_PAIR accepted")
        } catch (_: P.PayloadException) {
        }
    }

    @Test
    fun detectWithTooManyRecordsIsAPayloadError() {
        val p = ByteArray(11)
        p[10] = 8
        try {
            P.parseDetect(p)
            fail("8 records accepted")
        } catch (_: P.PayloadException) {
        }
    }

    @Test
    fun frameLogNeverPrintsTheFullAddress() {
        val line = BtrxFrameLog.format(decodeExample("DETECT"))
        assertTrue(line, line.startsWith("DETECT seq=201 pod=00000000"))
        assertTrue(line, line.contains("..5566"))
        assertFalse(line, line.contains("11:22:33"))
        assertFalse(line, line.contains("112233"))
        assertFalse(line, line.contains("665544"))
    }

    @Test
    fun encodeRejectsOversizedPayload() {
        assertArrayEquals(h(EXAMPLES.getValue("ROLE_GET")), P.encodeFrame(P.ROLE_GET))
        try {
            P.encodeFrame(P.DETECT, payload = ByteArray(221))
            fail("221-byte payload accepted")
        } catch (_: IllegalArgumentException) {
        }
    }

    companion object {
        val EXAMPLES = linkedMapOf(
            "ROLE_GET" to "b7520120010000000000005830",
            "DESK_PAIR" to "b75201152500000000000401013c0090ff",
            "PAIR_REQ" to "b7520110ee00000000001b000000005b128c4c71291c3500010001010201570b73e9475dfe3fbd48",
            "PAIR_CONFIRM" to "b75201110600000000003d00000000570b73e9475dfe3f02010100000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000005f98",
            "PAIR_ACK" to "b7520112f900000000020401010000e857",
            "TIME_PING" to "b75201300200000000001001007856341200000000000000000000fa86",
            "START" to "b75201400300000000000907000000000000000076b1",
            "DETECT" to "b7520170c9000000000028aa70050004fa010002000152030c66554433221185b9c0094c005a0300044c00100500000000000090c5",
            "HEALTH" to "b7520180af00000000001e4c010000000102020140000000011805000600e20c000000000000020001d190",
            "HELLO" to "b752010101d4c3b2a1000c425452580102000144332211ccbb",
        )
        const val PAIR_CONFIRM_FIRMWARE = "b75201110309000000003d" +
            "0900000001020304050607080201010110" +
            "abababababababababababababababab" +
            "00000000000000000000000000000000" +
            "627472780000000000000000" +
            "74fe"
    }
}

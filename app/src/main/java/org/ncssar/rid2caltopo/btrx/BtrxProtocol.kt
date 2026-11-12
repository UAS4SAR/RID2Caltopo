package org.ncssar.rid2caltopo.btrx

/**
 * btrx frame codec, byte for byte with the btrx firmware (btrx_protocol.cpp) and
 * its reference, firmware/tools/r2c_sim/protocol.py. The frame rides inside a
 * Meshtastic PRIVATE_APP (port 256) payload. Little-endian throughout:
 *
 *   B7 52 | version | type | seq | pod_id u32 | flags | len | payload | crc u16
 *
 * The CRC is CRC-16/CCITT-FALSE over version .. end of payload, low byte first.
 * Pure Kotlin with no Android types, so it is unit tested on the JVM.
 */
object BtrxProtocol {
    const val PREAMBLE_0 = 0xB7
    const val PREAMBLE_1 = 0x52
    const val PROTO_VERSION = 1
    const val HEADER_LEN = 11
    const val FRAME_MIN = 13
    const val FRAME_MAX = 233
    const val PAYLOAD_MAX = 220
    const val NONCE_LEN = 8
    const val PRIVATE_APP_PORT = 256

    /** Board builds this app knows. A board reporting another build gets a notice (M2). */
    val SUPPORTED_BUILDS: Set<Int> = setOf(0x0100, 0x0101)
    /** Firmware that sends STEER (btrx PR #18, steering-report). */
    const val BUILD_STEER = 0x0101

    // Message types (section 5 of the R2C integration brief).
    const val HELLO = 0x01
    const val HELLO_ACK = 0x02
    const val PAIR_REQ = 0x10
    const val PAIR_CONFIRM = 0x11
    const val PAIR_ACK = 0x12
    const val B_CFG = 0x13
    const val GESTURE = 0x14
    const val DESK_PAIR = 0x15
    const val ROLE_GET = 0x20
    const val ROLE_REPORT = 0x21
    const val ROLE_SET = 0x22
    const val TIME_PING = 0x30
    const val TIME_PONG = 0x31
    const val START = 0x40
    const val STOP = 0x41
    const val CTRL_ACK = 0x42
    const val SCAN_CFG = 0x50
    const val SCAN_CFG_ACK = 0x51
    const val FILTER_LIST = 0x60
    const val FILTER_ACK = 0x61
    const val DETECT = 0x70
    const val IMU_SAMPLE = 0x71
    const val ADV_SAMPLE = 0x72
    const val STEER = 0x73
    const val STEER_MUTE = 0x74
    const val HEALTH = 0x80
    const val DFU_REQ = 0x90
    const val DFU_ACK = 0x91

    private val MSG_NAME = mapOf(
        HELLO to "HELLO", HELLO_ACK to "HELLO_ACK", PAIR_REQ to "PAIR_REQ",
        PAIR_CONFIRM to "PAIR_CONFIRM", PAIR_ACK to "PAIR_ACK", B_CFG to "B_CFG",
        GESTURE to "GESTURE", DESK_PAIR to "DESK_PAIR", ROLE_GET to "ROLE_GET",
        ROLE_REPORT to "ROLE_REPORT", ROLE_SET to "ROLE_SET", TIME_PING to "TIME_PING",
        TIME_PONG to "TIME_PONG", START to "START", STOP to "STOP", CTRL_ACK to "CTRL_ACK",
        SCAN_CFG to "SCAN_CFG", SCAN_CFG_ACK to "SCAN_CFG_ACK", FILTER_LIST to "FILTER_LIST",
        FILTER_ACK to "FILTER_ACK", DETECT to "DETECT", IMU_SAMPLE to "IMU_SAMPLE",
        ADV_SAMPLE to "ADV_SAMPLE", STEER to "STEER", STEER_MUTE to "STEER_MUTE", HEALTH to "HEALTH", DFU_REQ to "DFU_REQ", DFU_ACK to "DFU_ACK",
    )

    fun typeName(type: Int): String = MSG_NAME[type] ?: "0x%02x".format(type)

    // Roles: 1 port (pod-b), 2 starboard (pod-a), 3 Desk Pod (tablet).
    const val ROLE_UNKNOWN = 0
    const val ROLE_PORT = 1
    const val ROLE_STARBOARD = 2
    const val ROLE_DESK_POD = 3

    fun roleName(role: Int): String = when (role) {
        ROLE_UNKNOWN -> "unknown"
        ROLE_PORT -> "port"
        ROLE_STARBOARD -> "starboard"
        ROLE_DESK_POD -> "desk-pod"
        else -> "0x%x".format(role)
    }

    fun txName(policy: Int): String = when (policy) {
        0 -> "off"
        1 -> "pairing"
        2 -> "on"
        else -> "0x%x".format(policy)
    }

    // Frame flags.
    const val F_ACK_REQ = 0x01
    const val F_IS_ACK = 0x02
    const val F_TO_B = 0x04
    const val F_ERROR = 0x80
    private val FRAME_FLAGS = listOf(F_ACK_REQ to "ack-req", F_IS_ACK to "is-ack", F_TO_B to "to-b", F_ERROR to "error")

    // Status flags (HEALTH, ROLE_REPORT, PAIR_ACK).
    const val FLAG_ROLE_MISMATCH = 0x01
    const val FLAG_B_MISSING = 0x02
    const val FLAG_IMU_MISSING = 0x04
    const val FLAG_TX_INHIBIT = 0x08
    const val FLAG_PAIRING = 0x10
    const val FLAG_CLOCK_UNSYNCED = 0x20
    const val FLAG_SCANNING = 0x40
    private val STATUS_FLAGS = listOf(
        FLAG_ROLE_MISMATCH to "role-mismatch", FLAG_B_MISSING to "port-missing",
        FLAG_IMU_MISSING to "imu-missing", FLAG_TX_INHIBIT to "tx-held-off",
        FLAG_PAIRING to "pairing", FLAG_CLOCK_UNSYNCED to "clock-unsynced",
        FLAG_SCANNING to "listening",
    )

    // STEER flags (btrx_types.h BTRX_STEER_F_*; names as protocol.py STEER_FLAGS).
    const val STEER_F_TARGET = 0x01
    const val STEER_F_NEW = 0x02
    const val STEER_F_LINKED = 0x04
    const val STEER_F_GATED = 0x08
    const val STEER_F_IMU_OK = 0x10
    const val STEER_F_MUTED = 0x20
    private val STEER_FLAGS = listOf(
        STEER_F_TARGET to "target", STEER_F_NEW to "new", STEER_F_LINKED to "linked",
        STEER_F_GATED to "gated", STEER_F_IMU_OK to "imu", STEER_F_MUTED to "muted",
    )

    // STEER_MUTE ops (BTRX_MUTE_*).
    const val MUTE_CLEAR = 0
    const val MUTE_ADD = 1
    const val MUTE_REMOVE = 2

    // STEER device kind (BTRX_DEV_*).
    const val DEV_UNKNOWN = 0
    const val DEV_PHONE = 1
    const val DEV_WEARABLE = 2
    const val DEV_TRACKER = 3
    const val DEV_AUDIO = 4
    const val DEV_COMPUTER = 5

    fun devClassName(kind: Int): String = when (kind) {
        DEV_UNKNOWN -> "unknown"
        DEV_PHONE -> "phone"
        DEV_WEARABLE -> "wearable"
        DEV_TRACKER -> "tracker"
        DEV_AUDIO -> "audio"
        DEV_COMPUTER -> "computer"
        else -> kind.toString()
    }

    fun steerFlagNames(value: Int): String = flagNames(value, STEER_FLAGS)
    fun frameFlagNames(value: Int): String = flagNames(value, FRAME_FLAGS)
    fun statusFlagNames(value: Int): String = flagNames(value, STATUS_FLAGS)

    private fun flagNames(value: Int, table: List<Pair<Int, String>>): String {
        val names = table.filter { value and it.first != 0 }.map { it.second }.toMutableList()
        val unknown = value and table.fold(0) { acc, p -> acc or p.first }.inv()
        if (unknown != 0) names += "0x%x".format(unknown)
        return if (names.isEmpty()) "none" else names.joinToString(",")
    }

    /** CRC-16/CCITT-FALSE: init 0xFFFF, poly 0x1021, no reflection, no final xor. */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    class Frame(
        val version: Int,
        val type: Int,
        val seq: Int,
        val podId: Long,
        val flags: Int,
        val payload: ByteArray,
    ) {
        val typeName: String get() = typeName(type)
    }

    enum class Rejection { TOO_SHORT, BAD_PREAMBLE, UNSUPPORTED_VERSION, LENGTH_TOO_LARGE, TRUNCATED, CRC_MISMATCH }

    class FrameException(val reason: Rejection, message: String, val version: Int? = null) : Exception(message)

    fun encodeFrame(type: Int, seq: Int = 1, podId: Long = 0, flags: Int = 0, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= PAYLOAD_MAX) { "payload too long: ${payload.size}" }
        val out = ByteArray(FRAME_MIN + payload.size)
        out[0] = PREAMBLE_0.toByte()
        out[1] = PREAMBLE_1.toByte()
        out[2] = PROTO_VERSION.toByte()
        out[3] = type.toByte()
        out[4] = seq.toByte()
        putU32(out, 5, podId)
        out[9] = flags.toByte()
        out[10] = payload.size.toByte()
        payload.copyInto(out, HEADER_LEN)
        val crc = crc16(out, 2, HEADER_LEN + payload.size)
        out[HEADER_LEN + payload.size] = (crc and 0xFF).toByte()
        out[HEADER_LEN + payload.size + 1] = (crc ushr 8).toByte()
        return out
    }

    /** Decodes one frame or throws [FrameException]. Mirrors protocol.py decode_frame. */
    fun decodeFrame(buf: ByteArray): Frame {
        if (buf.size < FRAME_MIN) throw FrameException(Rejection.TOO_SHORT, "frame too short (${buf.size} bytes)")
        if (u8(buf, 0) != PREAMBLE_0 || u8(buf, 1) != PREAMBLE_1) throw FrameException(Rejection.BAD_PREAMBLE, "bad preamble")
        val version = u8(buf, 2)
        if (version != PROTO_VERSION) {
            throw FrameException(Rejection.UNSUPPORTED_VERSION, "unsupported version $version", version)
        }
        val len = u8(buf, 10)
        if (len > PAYLOAD_MAX) throw FrameException(Rejection.LENGTH_TOO_LARGE, "payload length $len too large")
        if (buf.size < FRAME_MIN + len) throw FrameException(Rejection.TRUNCATED, "truncated frame")
        val expect = crc16(buf, 2, HEADER_LEN + len)
        val got = u16(buf, HEADER_LEN + len)
        if (expect != got) {
            throw FrameException(Rejection.CRC_MISMATCH, "crc mismatch expect=0x%04x got=0x%04x".format(expect, got))
        }
        return Frame(version, u8(buf, 3), u8(buf, 4), u32(buf, 5), u8(buf, 9), buf.copyOfRange(HEADER_LEN, HEADER_LEN + len))
    }

    // ---- payloads R2C receives ----

    class PayloadException(message: String) : Exception(message)

    private fun need(payload: ByteArray, size: Int, name: String) {
        if (payload.size < size) throw PayloadException("$name payload too short (${payload.size} < $size)")
    }

    data class DeskPair(val open: Int, val source: Int, val secondsLeft: Int)

    fun parseDeskPair(p: ByteArray): DeskPair {
        need(p, 4, "DESK_PAIR")
        return DeskPair(u8(p, 0), u8(p, 1), u16(p, 2))
    }

    class PairReq(
        val podId: Long, val devA: Long, val devB: Long, val buildA: Int, val buildB: Int,
        val imuOk: Int, val roleA: Int, val roleB: Int, val nonce: ByteArray,
    )

    fun parsePairReq(p: ByteArray): PairReq {
        need(p, 27, "PAIR_REQ")
        return PairReq(u32(p, 0), u32(p, 4), u32(p, 8), u16(p, 12), u16(p, 14), u8(p, 16), u8(p, 17), u8(p, 18), p.copyOfRange(19, 27))
    }

    data class PairAck(val aOk: Int, val bOk: Int, val flags: Int)

    fun parsePairAck(p: ByteArray): PairAck {
        need(p, 4, "PAIR_ACK")
        return PairAck(u8(p, 0), u8(p, 1), u16(p, 2))
    }

    data class RoleReport(
        val role: Int, val source: Int, val confirmed: Int, val flags: Int, val buildHash: Int,
        val devId: Long, val peerId: Long, val podId: Long, val imuWhoami: Int, val txPolicy: Int,
    )

    fun parseRoleReport(p: ByteArray): RoleReport {
        need(p, 21, "ROLE_REPORT")
        return RoleReport(u8(p, 0), u8(p, 1), u8(p, 2), u16(p, 3), u16(p, 5), u32(p, 7), u32(p, 11), u32(p, 15), u8(p, 19), u8(p, 20))
    }

    data class TimeSync(val pingSeq: Int, val t1: Long, val t2: Long, val t3: Long)

    fun parseTime(p: ByteArray): TimeSync {
        need(p, 16, "TIME")
        return TimeSync(u8(p, 0), u32(p, 2), u32(p, 6), u32(p, 10))
    }

    data class CtrlAck(val ackedType: Int, val ackedSeq: Int, val ok: Int, val sessionId: Long, val actualMs: Long)

    fun parseCtrlAck(p: ByteArray): CtrlAck {
        need(p, 11, "CTRL_ACK")
        return CtrlAck(u8(p, 0), u8(p, 1), u8(p, 2), u32(p, 3), u32(p, 7))
    }

    data class ScanCfg(
        val interval625us: Int, val window625us: Int, val phy: Int, val rssiFloor: Int, val reportMs: Int,
        val dedupMs: Int, val channelMask: Int, val profileId: Int, val activeScan: Int, val result: Int?,
    )

    fun parseScanCfg(p: ByteArray): ScanCfg {
        need(p, 13, "SCAN_CFG")
        return ScanCfg(
            u16(p, 0), u16(p, 2), u8(p, 4), i8(p, 5), u16(p, 6), u16(p, 8), u8(p, 10), u8(p, 11), u8(p, 12),
            if (p.size >= 14) u8(p, 13) else null,
        )
    }

    data class FilterAck(val version: Int, val op: Int, val ok: Int, val listCrc: Int, val stored: Int)

    fun parseFilterAck(p: ByteArray): FilterAck {
        need(p, 6, "FILTER_ACK")
        return FilterAck(u8(p, 0), u8(p, 1), u8(p, 2), u16(p, 3), u8(p, 5))
    }

    data class Health(
        val uptimeS: Long, val buildHash: Int, val role: Int, val source: Int, val confirmed: Int,
        val flags: Int, val bLinkAgeS: Int, val imuOk: Int, val dieTempC: Int,
        val advPerSPort: Int, val advPerSStbd: Int, val loraRssi: Int, val loraSnr: Int,
        val clockOffsetMs: Int, val cfgVersion: Int, val filterVersion: Int, val txPolicy: Int,
        val errorFlags: Int, val scanning: Int,
    )

    fun parseHealth(p: ByteArray): Health {
        need(p, 30, "HEALTH")
        return Health(
            u32(p, 0), u16(p, 4), u8(p, 6), u8(p, 7), u8(p, 8), u16(p, 9), u16(p, 11), u8(p, 13), i8(p, 14),
            u16(p, 15), u16(p, 17), i8(p, 19), i8(p, 20), i32(p, 21), u8(p, 25), u8(p, 26), u8(p, 27),
            u8(p, 28), u8(p, 29),
        )
    }

    const val DETECT_HEADER_LEN = 11
    const val DETECT_RECORD_LEN = 29
    const val DETECT_MAX_RECORDS = 7
    const val UUID16_REMOTE_ID = 0xFFFA

    class DetectRecord(
        val ageMs: Int, val windowDs: Int,
        /** Bluetooth address, least significant byte first as on the wire. */
        val macLsbFirst: ByteArray,
        val meta: Int, val rssiPort: Int, val rssiStbd: Int, val hits: Int, val companyId: Int,
        val fpHash: Int, val rxMask: Int, val pduType: Int, val mfgLen: Int, val mfg: ByteArray, val uuid16: Int,
    ) {
        val addressType: Int get() = meta and 0x03
        val deviceClass: Int get() = (meta ushr 2) and 0x0F
        val longRange: Boolean get() = meta and 0x40 != 0
        val hasManufacturerData: Boolean get() = meta and 0x80 != 0
        val isRemoteId: Boolean get() = uuid16 == UUID16_REMOTE_ID
        /** Printed address, most significant byte first, e.g. 11:22:33:44:55:66. */
        val address: String get() = macLsbFirst.reversed().joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        /** Last 4 characters of the address (decision 3): never log or show the full address. */
        val addressTail: String get() = address.replace(":", "").takeLast(4)
    }

    class Detect(
        val windowEndMs: Long, val rollX2: Int, val pitchX2: Int, val rollRateDps: Int,
        val attitudeFlags: Int, val tiltCode: Int, val records: List<DetectRecord>,
    ) {
        val tilted: Boolean get() = attitudeFlags and 0x01 != 0
        val imuOk: Boolean get() = attitudeFlags and 0x02 != 0
        val imuMissing: Boolean get() = attitudeFlags and 0x04 != 0
    }

    fun parseDetect(p: ByteArray): Detect {
        need(p, DETECT_HEADER_LEN, "DETECT")
        val n = u8(p, 10)
        if (n > DETECT_MAX_RECORDS || p.size < DETECT_HEADER_LEN + n * DETECT_RECORD_LEN) {
            throw PayloadException("DETECT payload truncated")
        }
        val records = (0 until n).map { i ->
            val o = DETECT_HEADER_LEN + i * DETECT_RECORD_LEN
            val mfgLen = u8(p, o + 18)
            DetectRecord(
                ageMs = u16(p, o), windowDs = u8(p, o + 2), macLsbFirst = p.copyOfRange(o + 3, o + 9),
                meta = u8(p, o + 9), rssiPort = i8(p, o + 10), rssiStbd = i8(p, o + 11), hits = u8(p, o + 12),
                companyId = u16(p, o + 13), fpHash = u8(p, o + 15), rxMask = u8(p, o + 16), pduType = u8(p, o + 17),
                mfgLen = mfgLen, mfg = p.copyOfRange(o + 19, o + 19 + minOf(mfgLen, 8)), uuid16 = u16(p, o + 27),
            )
        }
        return Detect(u32(p, 0), i8(p, 4), i8(p, 5), i16(p, 6), u8(p, 8), u8(p, 9), records)
    }

    const val STEER_LEN = 27
    /** STEER RSSI value for "not heard on this patch". */
    const val RSSI_NOT_HEARD = -128

    /**
     * STEER (0x73), 27 bytes, firmware/PROTOCOL.md "STEER": the single strongest source
     * in the pod's last 1 s window. Port is pod B (the IMU board), starboard pod A; port
     * stronger means the source is to the left.
     */
    class Steer(
        val steerSeq: Int, val flags: Int, val windowEndMs: Long, val ageMs: Int, val srcId: Int,
        val rssiPort: Int, val rssiStbd: Int, val peakRssi: Int, val hitsPort: Int, val hitsStbd: Int,
        val companyId: Int, val devClass: Int, val addrType: Int,
        /** Address as received, least significant byte first (as in DETECT). */
        val macLsbFirst: ByteArray,
        val rollX2: Int, val pitchX2: Int,
    ) {
        val target: Boolean get() = flags and STEER_F_TARGET != 0
        val newSource: Boolean get() = flags and STEER_F_NEW != 0
        val linked: Boolean get() = flags and STEER_F_LINKED != 0
        val gated: Boolean get() = flags and STEER_F_GATED != 0
        val imuOk: Boolean get() = flags and STEER_F_IMU_OK != 0
        val muted: Boolean get() = flags and STEER_F_MUTED != 0
        val portHeard: Boolean get() = rssiPort != RSSI_NOT_HEARD
        val stbdHeard: Boolean get() = rssiStbd != RSSI_NOT_HEARD
        /** Last 4 characters of the address: never log or show the full address. */
        val addressTail: String get() = macLsbFirst.reversed().joinToString("") { "%02X".format(it.toInt() and 0xFF) }.takeLast(4)
    }

    fun parseSteer(p: ByteArray): Steer {
        need(p, STEER_LEN, "STEER")
        return Steer(
            steerSeq = u8(p, 0), flags = u8(p, 1), windowEndMs = u32(p, 2), ageMs = u16(p, 6), srcId = u16(p, 8),
            rssiPort = i8(p, 10), rssiStbd = i8(p, 11), peakRssi = i8(p, 12), hitsPort = u8(p, 13), hitsStbd = u8(p, 14),
            companyId = u16(p, 15), devClass = u8(p, 17), addrType = u8(p, 18), macLsbFirst = p.copyOfRange(19, 25),
            rollX2 = i8(p, 25), pitchX2 = i8(p, 26),
        )
    }

    /** STEER encoder (protocol.py pack_steer), for tests and the bench simulator. */
    fun steerPayload(s: Steer): ByteArray {
        val out = ByteArray(STEER_LEN)
        out[0] = s.steerSeq.toByte(); out[1] = s.flags.toByte()
        putU32(out, 2, s.windowEndMs)
        out[6] = s.ageMs.toByte(); out[7] = (s.ageMs ushr 8).toByte()
        out[8] = s.srcId.toByte(); out[9] = (s.srcId ushr 8).toByte()
        out[10] = s.rssiPort.toByte(); out[11] = s.rssiStbd.toByte(); out[12] = s.peakRssi.toByte()
        out[13] = s.hitsPort.toByte(); out[14] = s.hitsStbd.toByte()
        out[15] = s.companyId.toByte(); out[16] = (s.companyId ushr 8).toByte()
        out[17] = s.devClass.toByte(); out[18] = s.addrType.toByte()
        s.macLsbFirst.copyInto(out, 19, 0, minOf(6, s.macLsbFirst.size))
        out[25] = s.rollX2.toByte(); out[26] = s.pitchX2.toByte()
        return out
    }

    data class SteerMute(val op: Int, val srcId: Int)

    fun parseSteerMute(p: ByteArray): SteerMute {
        need(p, 3, "STEER_MUTE")
        return SteerMute(u8(p, 0), u16(p, 1))
    }

    fun muteOpName(op: Int): String = when (op) {
        MUTE_CLEAR -> "clear"
        MUTE_ADD -> "add"
        MUTE_REMOVE -> "remove"
        else -> op.toString()
    }

    // ---- payloads R2C sends ----

    /** STEER_MUTE (0x74) payload: op u8, src_id u16 (protocol.py pack_steer_mute). */
    fun steerMutePayload(op: Int, srcId: Int = 0): ByteArray = byteArrayOf(op.toByte(), srcId.toByte(), (srcId ushr 8).toByte())

    /**
     * A complete STEER_MUTE frame. R2C sends it to the paired Drone Pod's node (the
     * starboard board, which builds STEER); the header carries that pod's pod_id.
     */
    fun steerMuteFrame(seq: Int, podId: Long, op: Int, srcId: Int = 0): ByteArray =
        encodeFrame(STEER_MUTE, seq = seq, podId = podId, payload = steerMutePayload(op, srcId))

    fun roleGetFrame(seq: Int): ByteArray = encodeFrame(ROLE_GET, seq = seq)

    const val PAIR_CONFIRM_LEN = 61
    const val PSK_MAX = 32
    const val NAME_LEN = 12

    /**
     * PAIR_CONFIRM (0x11), 61 bytes: pod_id u32, nonce 8, role_a, role_b, confirm,
     * channel, psk_len, psk 32, name 12 (protocol.py pack_pair_confirm). Version 1
     * always sends channel 0 with no key: a key moves the starboard board to a
     * channel the Desk Pod does not have. [channel] and [psk] exist only so the
     * firmware test vector can be reproduced.
     */
    fun pairConfirmPayload(
        podId: Long, nonce: ByteArray, roleA: Int = ROLE_STARBOARD, roleB: Int = ROLE_PORT,
        confirm: Int = 1, channel: Int = 0, psk: ByteArray = ByteArray(0), name: String = "",
    ): ByteArray {
        require(nonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes" }
        require(psk.size == 0 || psk.size == 16 || psk.size == PSK_MAX) { "psk must be empty, 16 or 32 bytes" }
        val out = ByteArray(PAIR_CONFIRM_LEN)
        putU32(out, 0, podId)
        nonce.copyInto(out, 4)
        out[12] = roleA.toByte(); out[13] = roleB.toByte(); out[14] = confirm.toByte()
        out[15] = channel.toByte(); out[16] = psk.size.toByte()
        psk.copyInto(out, 17)
        val ascii = name.map { if (it.code in 0x20..0x7E) it.code.toByte() else '?'.code.toByte() }.take(NAME_LEN)
        ascii.forEachIndexed { i, b -> out[49 + i] = b }
        return out
    }

    /** A complete PAIR_CONFIRM frame; the header carries the pod_id being assigned, as r2c_sim does. */
    fun pairConfirmFrame(seq: Int, podId: Long, nonce: ByteArray, roleA: Int = ROLE_STARBOARD, roleB: Int = ROLE_PORT, confirm: Int = 1): ByteArray =
        encodeFrame(PAIR_CONFIRM, seq = seq, podId = podId, payload = pairConfirmPayload(podId, nonce, roleA, roleB, confirm))

    /**
     * The pod_id R2C gives a Drone Pod at pairing: FNV-1a (32 bit) over dev_a's four
     * little-endian bytes, never 0 (0 means "keep the current id" and marks unpaired
     * frames). The same on iPad (BtrxProtocol.podId(forDevA:)), so both tablets name a
     * pod the same way. Brief section 6 suggests "the low 32 bits of a hash of dev_a".
     */
    fun podIdFor(devA: Long): Long {
        var h = 0x811C9DC5L
        for (i in 0 until 4) {
            h = h xor ((devA ushr (8 * i)) and 0xFF)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return if (h == 0L) 1L else h
    }

    // ---- little-endian helpers ----

    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    fun i8(b: ByteArray, o: Int): Int = b[o].toInt()
    fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    fun i16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
    fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16))
    fun i32(b: ByteArray, o: Int): Int = u32(b, o).toInt()

    fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun fromHex(s: String): ByteArray {
        val clean = s.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

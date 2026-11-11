package org.ncssar.rid2caltopo.btrx

import java.io.ByteArrayOutputStream

/**
 * The few Meshtastic phone/serial-API messages R2C needs, hand-written (the
 * Meshtastic protobuf definitions are GPL-3.0; decision 7 of the btrx brief).
 * Only the fields below are written or read; every other field is skipped.
 *
 *   ToRadio   1 packet (MeshPacket), 3 want_config_id (uint32), 7 heartbeat (Heartbeat)
 *   FromRadio 2 packet, 3 my_info (MyNodeInfo: 1 my_node_num), 7 config_complete_id
 *   MeshPacket 1 from (fixed32), 2 to (fixed32), 3 channel, 4 decoded (Data), 6 id (fixed32),
 *              8 rx_snr (float), 9 hop_limit (R2C sends 0), 10 want_ack (bool), 12 rx_rssi (int32)
 *   Data       1 portnum (enum), 2 payload (bytes)
 *
 * Stream framing: 0x94 0xC3, 2-byte big-endian length, protobuf.
 */
object MeshtasticSerial {
    const val START1 = 0x94
    const val START2 = 0xC3
    const val MAX_PACKET = 512
    const val BROADCAST: Long = 0xFFFFFFFFL

    /** Wake bytes the reference client sends before its first frame. */
    fun wakeBytes(): ByteArray = ByteArray(32) { START2.toByte() }

    fun frame(proto: ByteArray): ByteArray {
        require(proto.size <= MAX_PACKET) { "protobuf too long: ${proto.size}" }
        val out = ByteArray(4 + proto.size)
        out[0] = START1.toByte(); out[1] = START2.toByte()
        out[2] = (proto.size ushr 8).toByte(); out[3] = proto.size.toByte()
        proto.copyInto(out, 4)
        return out
    }

    // ---- ToRadio ----

    fun toRadioWantConfig(configId: Long): ByteArray = ProtoWriter().varint(3, configId).bytes()

    fun toRadioHeartbeat(): ByteArray = ProtoWriter().bytesField(7, ByteArray(0)).bytes()

    fun toRadioPacket(to: Long, packetId: Long, portnum: Int, payload: ByteArray, channel: Int = 0, hopLimit: Int = 0): ByteArray {
        val data = ProtoWriter().varint(1, portnum.toLong()).bytesField(2, payload).bytes()
        val packet = ProtoWriter()
            .fixed32(2, to)
            .apply { if (channel != 0) varint(3, channel.toLong()) }
            .bytesField(4, data)
            .fixed32(6, packetId)
            // hop_limit 0 (the default, not written) for everything R2C sends: the Desk Pod is
            // in direct range of the Drone Pod and there is no mesh to relay through (btrx firmware 481b6fb).
            .apply { if (hopLimit != 0) varint(9, hopLimit.toLong()) }
            .bytes() // want_ack false is the default and is not written.
        return ProtoWriter().bytesField(1, packet).bytes()
    }

    // ---- FromRadio ----

    sealed class FromRadio {
        data class MyInfo(val nodeNum: Long) : FromRadio()
        data class ConfigComplete(val id: Long) : FromRadio()
        class Packet(
            val from: Long, val to: Long, val channel: Int, val id: Long,
            val rxSnr: Float, val rxRssi: Int, val hopLimit: Int,
            /** Null when the packet was encrypted for a channel this node cannot read. */
            val portnum: Int?, val payload: ByteArray?,
        ) : FromRadio()
        /** Node, config, channel, log and every other record R2C does not use. */
        data class Other(val field: Int) : FromRadio()
    }

    fun parseFromRadio(buf: ByteArray): FromRadio {
        val r = ProtoReader(buf)
        var result: FromRadio? = null
        while (r.hasMore()) {
            val (field, wire) = r.tag()
            when {
                field == 2 && wire == 2 -> result = parseMeshPacket(r.lengthDelimited())
                field == 3 && wire == 2 -> {
                    val info = ProtoReader(r.lengthDelimited())
                    var node = 0L
                    while (info.hasMore()) {
                        val (f, w) = info.tag()
                        if (f == 1 && w == 0) node = info.varint() and 0xFFFFFFFFL else info.skip(w)
                    }
                    result = FromRadio.MyInfo(node)
                }
                field == 7 && wire == 0 -> result = FromRadio.ConfigComplete(r.varint() and 0xFFFFFFFFL)
                field == 1 -> r.skip(wire) // FromRadio.id
                else -> {
                    if (result == null) result = FromRadio.Other(field)
                    r.skip(wire)
                }
            }
        }
        return result ?: FromRadio.Other(0)
    }

    private fun parseMeshPacket(buf: ByteArray): FromRadio.Packet {
        val r = ProtoReader(buf)
        var from = 0L; var to = 0L; var channel = 0; var id = 0L
        var snr = 0f; var rssi = 0; var hop = 0
        var port: Int? = null; var payload: ByteArray? = null
        while (r.hasMore()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 5 -> from = r.fixed32()
                f == 2 && w == 5 -> to = r.fixed32()
                f == 3 && w == 0 -> channel = r.varint().toInt()
                f == 4 && w == 2 -> {
                    val d = ProtoReader(r.lengthDelimited())
                    port = 0
                    while (d.hasMore()) {
                        val (df, dw) = d.tag()
                        when {
                            df == 1 && dw == 0 -> port = d.varint().toInt()
                            df == 2 && dw == 2 -> payload = d.lengthDelimited()
                            else -> d.skip(dw)
                        }
                    }
                    if (payload == null) payload = ByteArray(0)
                }
                f == 6 && w == 5 -> id = r.fixed32()
                f == 8 && w == 5 -> snr = java.lang.Float.intBitsToFloat(r.fixed32().toInt())
                f == 9 && w == 0 -> hop = r.varint().toInt()
                f == 12 && w == 0 -> rssi = r.varint().toInt() // int32: negative values are 10-byte varints
                else -> r.skip(w)
            }
        }
        return FromRadio.Packet(from, to, channel, id, snr, rssi, hop, port, payload)
    }

    /** Splits the serial byte stream into protobuf messages, skipping text before 0x94 0xC3. */
    class StreamDecoder(private val onMessage: (ByteArray) -> Unit, private val onNoise: (Int) -> Unit = {}) {
        private val buf = ByteArrayOutputStream()
        private var state = 0
        private var length = 0
        private var noise = 0

        fun feed(data: ByteArray, len: Int = data.size) {
            for (i in 0 until len) accept(data[i].toInt() and 0xFF)
        }

        private fun accept(b: Int) {
            when (state) {
                0 -> if (b == START1) state = 1 else noise++
                1 -> state = if (b == START2) 2 else { noise += 2; if (b == START1) 1 else 0 }
                2 -> { length = b shl 8; state = 3 }
                3 -> {
                    length = length or b
                    if (length > MAX_PACKET) { noise += 4; state = 0; return }
                    flushNoise()
                    buf.reset()
                    state = if (length == 0) { onMessage(ByteArray(0)); 0 } else 4
                }
                4 -> {
                    buf.write(b)
                    if (buf.size() == length) { state = 0; onMessage(buf.toByteArray()) }
                }
            }
        }

        private fun flushNoise() {
            if (noise > 0) { onNoise(noise); noise = 0 }
        }
    }

    class ProtoWriter {
        private val out = ByteArrayOutputStream()
        private fun rawVarint(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())
        fun varint(field: Int, v: Long) = apply { tag(field, 0); rawVarint(v) }
        fun fixed32(field: Int, v: Long) = apply {
            tag(field, 5)
            for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
        }
        fun bytesField(field: Int, b: ByteArray) = apply { tag(field, 2); rawVarint(b.size.toLong()); out.write(b) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    class ProtoReader(private val b: ByteArray) {
        private var pos = 0
        fun hasMore() = pos < b.size
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (pos >= b.size) throw IllegalArgumentException("truncated varint")
                val x = b[pos++].toInt() and 0xFF
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalArgumentException("varint too long")
            }
        }
        fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }
        fun fixed32(): Long {
            if (pos + 4 > b.size) throw IllegalArgumentException("truncated fixed32")
            var v = 0L
            for (i in 0 until 4) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }
        fun lengthDelimited(): ByteArray {
            val n = varint().toInt()
            if (n < 0 || pos + n > b.size) throw IllegalArgumentException("truncated field")
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> { if (pos + 8 > b.size) throw IllegalArgumentException("truncated fixed64"); pos += 8 }
                2 -> lengthDelimited()
                5 -> fixed32()
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
        }
    }
}

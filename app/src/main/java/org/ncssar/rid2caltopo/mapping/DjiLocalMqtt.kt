package org.ncssar.rid2caltopo.mapping

import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Session-scoped MQTT 3.1.1 broker for Pilot 2 registration/telemetry; no flight-control publisher. */
class DjiLocalMqtt(private val username: String, private val password: String,
    private val valid: () -> Boolean, private val received: (String, ByteArray) -> List<Pair<String, ByteArray>>,
    private val connected: (Boolean) -> Unit) {
    private class Peer(val send: (ByteArray) -> Unit) { val subscriptions = ConcurrentHashMap.newKeySet<String>() }
    private val peers = ConcurrentHashMap.newKeySet<Peer>()
    fun serve(input: InputStream, send: (ByteArray) -> Unit) {
        var peer: Peer? = null; val qos2 = mutableMapOf<Int, Pair<String, ByteArray>>()
        try {
            while (valid()) {
                val first = input.read(); if (first < 0) break
                var length = 0; var multiplier = 1
                for (index in 0..3) { val value = input.read(); require(value >= 0); length += (value and 127) * multiplier; if (value and 128 == 0) break; require(index < 3); multiplier *= 128 }
                require(length <= 256 * 1024) { "MQTT packet too large." }
                val body = ByteArray(length); DataInputStream(input).readFully(body)
                val packet = DataInputStream(ByteArrayInputStream(body))
                fun utf(): String { val count = packet.readUnsignedShort(); val bytes = ByteArray(count); packet.readFully(bytes); return bytes.toString(Charsets.UTF_8) }
                fun id() = packet.readUnsignedShort()
                val type = first shr 4
                if (peer == null) {
                    require(type == 1 && first == 0x10)
                    val protocol = utf(); val version = packet.readUnsignedByte(); val flags = packet.readUnsignedByte(); packet.readUnsignedShort()
                    if (protocol != "MQTT" || version != 4) { send(frame(0x20, byteArrayOf(0,1))); return }
                    require(flags and 1 == 0)
                    val clientId = utf(); require(clientId.length in 1..128)
                    if (flags and 4 != 0) { utf(); utf() }
                    val user = if (flags and 128 != 0) utf() else ""; val pass = if (flags and 64 != 0) utf() else ""
                    if (!same(user, username) || !same(pass, password)) { send(frame(0x20, byteArrayOf(0,4))); return }
                    require(packet.available() == 0)
                    peer = Peer(send); peers.add(peer); send(frame(0x20, byteArrayOf(0,0))); connected(true); continue
                }
                when(type) {
                    3 -> {
                        val topic = utf(); val qos = (first shr 1) and 3; require(qos <= 2 && allowed(topic, false))
                        val packetId = if(qos > 0) id() else 0
                        val payload = packet.readBytes()
                        if (qos == 2) { require(qos2.size < 64 || qos2.containsKey(packetId)); qos2[packetId] = topic to payload; send(frame(0x50, short(packetId))) }
                        else { if(qos == 1) send(frame(0x40, short(packetId))); dispatch(topic, payload) }
                    }
                    6 -> { require(first == 0x62); val packetId = id(); qos2.remove(packetId)?.let { dispatch(it.first,it.second) }; send(frame(0x70, short(packetId))) }
                    8 -> {
                        require(first == 0x82); val packetId = id(); val granted = ByteArrayOutputStream()
                        while(packet.available() > 0) { val filter = utf(); val qos = packet.readUnsignedByte();
                            if(allowed(filter, true) && qos <= 2) { peer.subscriptions.add(filter); granted.write(qos) } else granted.write(128) }
                        send(frame(0x90, short(packetId) + granted.toByteArray()))
                    }
                    10 -> { require(first == 0xa2); val packetId = id(); while(packet.available() > 0) peer.subscriptions.remove(utf()); send(frame(0xb0, short(packetId))) }
                    12 -> { require(length == 0); send(frame(0xd0, ByteArray(0))) }
                    14 -> return
                    else -> throw IllegalArgumentException("Unsupported MQTT packet.")
                }
            }
        } finally { peer?.let { peers.remove(it); connected(peers.isNotEmpty()) } }
    }
    private fun dispatch(topic: String, payload: ByteArray) { received(topic, payload).forEach { (target, bytes) -> publish(target, bytes) } }
    private fun publish(topic: String, payload: ByteArray) {
        val text = topic.toByteArray(); val packet = frame(0x30, short(text.size) + text + payload)
        peers.filter { peer -> peer.subscriptions.any { matches(it, topic) } }.forEach { runCatching { it.send(packet) } }
    }
    companion object {
        private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(),b.toByteArray())
        private fun short(value: Int) = byteArrayOf((value shr 8).toByte(),value.toByte())
        private fun frame(type: Int, body: ByteArray): ByteArray {
            val buffer = ByteArrayOutputStream(); buffer.write(type); var length = body.size
            do { var value = length % 128; length /= 128; if(length > 0) value = value or 128; buffer.write(value) } while(length > 0)
            buffer.write(body); return buffer.toByteArray()
        }
        private fun allowed(topic: String, filter: Boolean): Boolean {
            val parts = topic.split('/'); if(parts.size < 3 || parts.size > 5 || parts[0] !in listOf("sys","thing") || parts[1] != "product") return false
            if(filter) return parts.drop(2).all { it == "+" || it == "#" || it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } && ("#" !in parts || parts.last() == "#")
            return parts.size == 4 && parts[2].matches(Regex("[A-Za-z0-9_-]{1,128}")) && parts[3] in listOf("status","state","osd","events","requests")
        }
        private fun matches(filter: String, topic: String): Boolean {
            val a = filter.split('/'); val b = topic.split('/')
            a.forEachIndexed { index, value -> if(value == "#") return true; if(index >= b.size || value != "+" && value != b[index]) return false }
            return a.size == b.size
        }
    }
}

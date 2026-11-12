package org.ncssar.rid2caltopo.btrx

import java.security.SecureRandom
import java.util.Random
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.FromRadio

/**
 * Desk Pod client: the Meshtastic phone-API handshake, send/receive on app port
 * 256, every btrx frame decoded into the log, and ROLE_GET to the Desk Pod itself
 * (`^local`). The same client runs over USB and Bluetooth; only [framing] differs:
 *
 * - [Framing.SERIAL] (USB): wake bytes, then 0x94 0xC3 + length framing; the
 *   transport feeds raw serial bytes into [onBytes].
 * - [Framing.PACKET] (Bluetooth): each ToRadio write and each FromRadio read is
 *   one bare protobuf; the transport feeds each read into [onFromRadio].
 *
 * Transport-agnostic so it runs in JVM unit tests: [send] hands bytes to the link
 * without blocking. All entry points are synchronized and only touch memory.
 * Pairing, status and link health live in [BtrxDeskPodModel], fed by [Listener].
 */
class BtrxClient(
    private val send: (ByteArray) -> Unit,
    private val log: Logger,
    private val listener: Listener = object : Listener {},
    private val random: Random = SecureRandom(),
    val framing: Framing = Framing.SERIAL,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Frames arriving within this many ms after config_complete_id are also tagged
     * backlog. The Desk Pod drains up to 32 queued frames in the first 1-2 s of a
     * session and they carry no timestamp; links pass [CONNECT_BURST_MS]. 0 keeps the
     * M1 rule (only frames before config_complete_id are backlog).
     */
    private val connectBurstMs: Long = 0,
) : BtrxFrameSender {
    enum class Framing { SERIAL, PACKET }

    interface Logger {
        fun info(message: String)
        fun warn(message: String)
    }

    /**
     * Arrival details from the Desk Pod for one btrx frame.
     *
     * [backlog] is true for frames delivered before config_complete_id: the Desk Pod
     * replays what it queued while R2C was away, with no marker of its own, so these
     * are old and display-only. Clue code must refuse them. Age of live frames will
     * come from seq plus the TIME_PING clock offset (M4), not from arrival time.
     */
    class RadioMeta(
        val fromNode: Long, val toNode: Long, val rxRssi: Int, val rxSnr: Float, val arrivalMs: Long,
        val backlog: Boolean,
    )

    interface Listener {
        /** The handshake finished; [localNode] is the Desk Pod's node number. */
        fun onConfigured(localNode: Long) {}
        /** ROLE_REPORT from the Desk Pod itself. A role other than 3 means this board is not a Desk Pod. */
        fun onLocalRoleReport(report: P.RoleReport) {}
        /** Every frame that passed the preamble, version and CRC checks. */
        fun onFrame(frame: P.Frame, meta: RadioMeta) {}
        /** A board sent a frame version this app does not understand (pilot alert in M2). */
        fun onVersionRefused(fromNode: Long, version: Int) {}

        // Pairing (DESK_PAIR from the Desk Pod itself, PAIR_REQ / PAIR_ACK from the starboard
        // board) is handled by BtrxDeskPodModel from onFrame. PAIR_CONFIRM assigns a non-zero
        // pod_id (BtrxProtocol.podIdFor); only pod_id 0 had been tried on the boards before.
    }

    enum class State { IDLE, CONFIGURING, READY, CLOSED }

    @get:Synchronized
    var state = State.IDLE
        private set

    @get:Synchronized
    var localNode: Long? = null
        private set

    @get:Synchronized
    var localRole: P.RoleReport? = null
        private set

    private var configId = 0L
    private var seq = 0
    private var readyAtMs = 0L
    private val decoder = MeshtasticSerial.StreamDecoder(::onProto) { skipped ->
        log.info("serial: skipped $skipped byte(s) of non-API output")
    }
    private val frameCounts = sortedMapOf<String, Int>()
    private val backlogCounts = sortedMapOf<String, Int>()
    private val rejectCounts = sortedMapOf<String, Int>()
    private val refusedNodes = mutableSetOf<Long>()
    private var otherPorts = 0

    /** Opens the API session: wake bytes, then want_config_id. */
    @Synchronized
    fun start() {
        if (state != State.IDLE) return
        configId = (random.nextInt() and 0x7FFFFFFF).toLong().coerceAtLeast(1)
        state = State.CONFIGURING
        if (framing == Framing.SERIAL) send(MeshtasticSerial.wakeBytes())
        sendToRadio(MeshtasticSerial.toRadioWantConfig(configId))
        log.info("handshake: want_config_id=$configId sent")
    }

    /** USB: raw serial bytes, split into protobufs by the 0x94 0xC3 framing. */
    @Synchronized
    fun onBytes(data: ByteArray, length: Int = data.size) {
        if (state == State.CLOSED) return
        decoder.feed(data, length)
    }

    /** Bluetooth: one FromRadio protobuf per characteristic read (an empty read means drained). */
    @Synchronized
    fun onFromRadio(proto: ByteArray) {
        if (state == State.CLOSED || proto.isEmpty()) return
        onProto(proto)
    }

    private fun sendToRadio(proto: ByteArray) {
        send(if (framing == Framing.SERIAL) MeshtasticSerial.frame(proto) else proto)
    }

    /** Keep-alive; the transport calls this about every 5 minutes. */
    @Synchronized
    fun sendHeartbeat() {
        if (state != State.READY) return
        sendToRadio(MeshtasticSerial.toRadioHeartbeat())
    }

    /** Asks a board what it is. [to] null means the Desk Pod itself (`^local`). */
    @Synchronized
    fun sendRoleGet(to: Long? = null): Boolean {
        val dest = to ?: localNode ?: run {
            log.warn("ROLE_GET not sent: the Desk Pod's node number is not known yet")
            return false
        }
        sendFrame(dest, P.roleGetFrame(nextSeq()))
        log.info("tx ROLE_GET to ${nodeName(dest)}")
        return true
    }

    /** Sends one btrx frame on port 256 (hop_limit 0). PAIR_CONFIRM, TIME_PING and FILTER_LIST build on this. */
    @Synchronized
    override fun sendFrame(to: Long, frame: ByteArray) {
        val id = (random.nextInt().toLong() and 0xFFFFFFFFL).let { if (it == 0L) 1L else it }
        sendToRadio(MeshtasticSerial.toRadioPacket(to, id, P.PRIVATE_APP_PORT, frame))
    }

    @get:Synchronized
    override val isReady: Boolean get() = state == State.READY

    @Synchronized
    override fun nextSeq(): Int {
        seq = (seq + 1) and 0xFF
        return seq
    }

    @Synchronized
    fun close() {
        state = State.CLOSED
    }

    /** Live and backlog frame counts by type since connecting (copies). */
    @Synchronized
    fun counts(): Pair<Map<String, Int>, Map<String, Int>> = HashMap(frameCounts) to HashMap(backlogCounts)

    /** Frame counts by type since connecting, for comparing with an r2c_sim run. */
    @Synchronized
    fun summary(): String {
        val frames = if (frameCounts.isEmpty()) "none" else frameCounts.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val backlog = if (backlogCounts.isEmpty()) "" else " backlog: " + backlogCounts.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val rejects = if (rejectCounts.isEmpty()) "" else " rejected: " + rejectCounts.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val other = if (otherPorts == 0) "" else " other-port packets=$otherPorts"
        return "frames: $frames$backlog$rejects$other"
    }

    /** Live frames of one type (delivered after config_complete_id). */
    @Synchronized
    fun frameCount(typeName: String): Int = frameCounts[typeName] ?: 0

    /** Backlog frames of one type (delivered before config_complete_id; old, display-only). */
    @Synchronized
    fun backlogCount(typeName: String): Int = backlogCounts[typeName] ?: 0

    private fun onProto(proto: ByteArray) {
        val msg = try {
            MeshtasticSerial.parseFromRadio(proto)
        } catch (e: IllegalArgumentException) {
            log.warn("serial: undecodable FromRadio (${proto.size} bytes): ${e.message}")
            return
        }
        when (msg) {
            is FromRadio.MyInfo -> {
                localNode = msg.nodeNum
                log.info("handshake: my_info node=${nodeName(msg.nodeNum)}")
            }
            is FromRadio.ConfigComplete -> {
                if (msg.id != configId) {
                    log.info("handshake: config_complete_id=${msg.id} ignored (want $configId)")
                    return
                }
                state = State.READY
                readyAtMs = clock()
                val node = localNode
                log.info("handshake: config complete, Desk Pod node ${node?.let(::nodeName) ?: "unknown"}")
                if (node != null) listener.onConfigured(node)
                sendRoleGet()
            }
            is FromRadio.Packet -> onPacket(msg)
            is FromRadio.Other -> Unit
        }
    }

    private fun onPacket(packet: FromRadio.Packet) {
        if (packet.portnum != P.PRIVATE_APP_PORT || packet.payload == null) {
            otherPorts++
            return
        }
        val now = clock()
        val backlog = state != State.READY || (connectBurstMs > 0 && now - readyAtMs < connectBurstMs)
        val meta = RadioMeta(packet.from, packet.to, packet.rxRssi, packet.rxSnr, now, backlog)
        val radio = (if (backlog) "backlog " else "") +
            "from=${nodeName(packet.from)} rssi=${packet.rxRssi} snr=${"%.1f".format(packet.rxSnr)}"
        if (packet.from in refusedNodes) {
            count(rejectCounts, "refused-node")
            return
        }
        val frame = try {
            P.decodeFrame(packet.payload)
        } catch (e: P.FrameException) {
            count(rejectCounts, e.reason.name)
            if (e.reason == P.Rejection.UNSUPPORTED_VERSION) {
                refusedNodes += packet.from
                val v = e.version ?: -1
                val newer = if (v > P.PROTO_VERSION) "newer" else "older"
                log.warn("rx $radio btrx version $v refused (this app understands ${P.PROTO_VERSION}); " +
                    "board firmware is $newer than this app; ignoring that board until reconnect")
                listener.onVersionRefused(packet.from, v)
            } else {
                log.warn("rx $radio bad btrx frame: ${e.message} raw=${P.hex(packet.payload)}")
            }
            return
        }
        count(if (backlog) backlogCounts else frameCounts, frame.typeName)
        log.info("rx $radio ${BtrxFrameLog.format(frame)}")
        if (frame.type == P.ROLE_REPORT && packet.from == localNode) {
            try {
                val report = P.parseRoleReport(frame.payload)
                localRole = report
                if (report.role == P.ROLE_DESK_POD) {
                    val build = if (report.buildHash in P.SUPPORTED_BUILDS) "supported" else "not in the supported list"
                    log.info("Desk Pod confirmed: role=3 build_hash=0x%04x (%s)".format(report.buildHash, build))
                } else {
                    log.warn("this board answered role=${report.role} (${P.roleName(report.role)}), not a Desk Pod (role=3)")
                }
                listener.onLocalRoleReport(report)
            } catch (_: P.PayloadException) {
                // Already logged with decode-error by BtrxFrameLog.
            }
        }
        listener.onFrame(frame, meta)
    }

    private fun count(map: MutableMap<String, Int>, key: String) {
        map[key] = (map[key] ?: 0) + 1
    }

    companion object {
        const val CONNECT_BURST_MS = 2_000L

        fun nodeName(node: Long): String = "!%08x".format(node and 0xFFFFFFFFL)
    }
}

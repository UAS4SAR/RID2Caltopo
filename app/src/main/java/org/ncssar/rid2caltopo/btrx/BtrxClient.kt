package org.ncssar.rid2caltopo.btrx

import java.security.SecureRandom
import java.util.Random
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.FromRadio

/**
 * Minimal Desk Pod client (btrx milestone 1, "Connect and decode"): the
 * Meshtastic serial handshake, send/receive on app port 256, every btrx frame
 * decoded into the log, and ROLE_GET to the Desk Pod itself (`^local`).
 *
 * Transport-agnostic so it runs in JVM unit tests: [send] writes bytes to the
 * serial port without blocking, and the transport feeds received bytes into
 * [onBytes]. All entry points are synchronized and only touch memory.
 *
 * Later milestones hook in through [Listener]: pairing (DESK_PAIR / PAIR_REQ /
 * PAIR_ACK), HEALTH status, TIME_PING clock checks, detections to clues and the
 * FILTER_LIST ignore list are not built yet.
 */
class BtrxClient(
    private val send: (ByteArray) -> Unit,
    private val log: Logger,
    private val listener: Listener = object : Listener {},
    private val random: Random = SecureRandom(),
) {
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

        // M3 pairing hook (not built yet): DESK_PAIR arrives on port 256 with from = to = the
        // Desk Pod node; PAIR_REQ / PAIR_ACK come from the starboard board.
        // TODO(btrx M3): PAIR_CONFIRM should assign a non-zero pod_id (for example the low
        //  32 bits of a hash of dev_a) so later frames say which Drone Pod sent them.
        //  Frames sent before pairing carry pod_id 0. Only pod_id 0 has been tried on the boards.
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
        send(MeshtasticSerial.wakeBytes())
        send(MeshtasticSerial.frame(MeshtasticSerial.toRadioWantConfig(configId)))
        log.info("handshake: want_config_id=$configId sent")
    }

    @Synchronized
    fun onBytes(data: ByteArray, length: Int = data.size) {
        if (state == State.CLOSED) return
        decoder.feed(data, length)
    }

    /** Keep-alive; the transport calls this about every 5 minutes. */
    @Synchronized
    fun sendHeartbeat() {
        if (state != State.READY) return
        send(MeshtasticSerial.frame(MeshtasticSerial.toRadioHeartbeat()))
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

    /** Sends one btrx frame on port 256. Later milestones build PAIR_CONFIRM, TIME_PING and FILTER_LIST on this. */
    @Synchronized
    fun sendFrame(to: Long, frame: ByteArray) {
        val id = (random.nextInt().toLong() and 0xFFFFFFFFL).let { if (it == 0L) 1L else it }
        send(MeshtasticSerial.frame(MeshtasticSerial.toRadioPacket(to, id, P.PRIVATE_APP_PORT, frame)))
    }

    @Synchronized
    fun nextSeq(): Int {
        seq = (seq + 1) and 0xFF
        return seq
    }

    @Synchronized
    fun close() {
        state = State.CLOSED
    }

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
        val backlog = state != State.READY
        val meta = RadioMeta(packet.from, packet.to, packet.rxRssi, packet.rxSnr, System.currentTimeMillis(), backlog)
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
        fun nodeName(node: Long): String = "!%08x".format(node and 0xFFFFFFFFL)
    }
}

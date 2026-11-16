package org.ncssar.rid2caltopo.btrx

import java.security.SecureRandom
import java.util.Locale
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
) : BtrxFrameSender {
    enum class Framing { SERIAL, PACKET }

    interface Logger {
        fun info(message: String)
        fun warn(message: String)
        /** Per-frame detail that arrives every second (STEER); info unless the app logs debug separately. */
        fun debug(message: String) = info(message)
    }

    /**
     * Arrival details from the Desk Pod for one btrx frame.
     *
     * [backlog] is true for frames the Desk Pod queued while R2C was away (Meshtastic
     * keeps the oldest 32 and drops new ones when full, so they can be minutes old).
     * They carry no marker of their own; see [onFromRadio] and [Framing] for how each
     * link tells them apart. Backlog frames are display-only: live health, RSSI, the
     * Desk Pod's role and clue code must refuse them. Age of live frames will
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
        /** A packet that is not a decoded btrx (port 256) packet was dropped; [encrypted]: the node could not decode it. */
        fun onPacketDropped(encrypted: Boolean) {}

        // Pairing (DESK_PAIR from the Desk Pod itself, PAIR_REQ / PAIR_ACK from the starboard
        // board) is handled by BtrxDeskPodModel from onFrame. PAIR_CONFIRM assigns a non-zero
        // pod_id (BtrxProtocol.podIdFor); only pod_id 0 had been tried on the boards before.
    }

    enum class State { IDLE, CONFIGURING, READY, CLOSED }

    /** What the link should do next while waiting for the Desk Pod's ROLE_REPORT (see [roleCheck]). */
    enum class RoleWait {
        /** The Desk Pod answered. */
        DONE,
        /** Check again after [RoleCheck.delayMs]. */
        WAIT,
        /** No answer within [ROLE_TIMEOUT_MS] of ROLE_GET: ROLE_GET was sent again; check after [ROLE_TIMEOUT_MS]. */
        RETRY,
        /** No answer after the retry either. */
        GIVE_UP,
        /** No config_complete_id within [CONFIG_TIMEOUT_MS]. */
        NO_CONFIG,
    }

    data class RoleCheck(val action: RoleWait, val delayMs: Long = 0)

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
    private var startedMs = 0L
    private var readyMs = 0L
    /** When this session's latest ROLE_GET to the Desk Pod went out (null: none yet). */
    private var roleGetSentMs: Long? = null
    /**
     * True from config_complete_id until the connect backlog ends. The Desk Pod delivers
     * its queue right after config_complete (0.5-3 s on the bench), so everything in it
     * is backlog. Bluetooth: it ends at the first empty FromRadio read. USB (no empty
     * read): it ends at the first quiet gap of [SERIAL_QUIET_GAP_MS] with no bytes.
     */
    private var firstDrainPending = false
    private var firstDrainFrames = 0
    /** USB: arrival of the latest serial bytes while [firstDrainPending]. */
    private var lastSerialRxMs = 0L
    /** Last heartbeat nonce sent; the first one sent is 2 (see [sendHeartbeat]). */
    private var heartbeatNonce = 1L
    /** Largest ToRadio this link may write (Bluetooth: ATT MTU - 3, at most [MAX_TO_RADIO_BLE]). */
    private var maxToRadioBytes = MAX_TO_RADIO_BLE
    private val decoder = MeshtasticSerial.StreamDecoder(::onProto) { skipped ->
        log.info("serial: skipped $skipped byte(s) of non-API output")
    }
    private val frameCounts = sortedMapOf<String, Int>()
    private val backlogCounts = sortedMapOf<String, Int>()
    private val rejectCounts = sortedMapOf<String, Int>()
    private val refusedNodes = mutableSetOf<Long>()
    /** Packets dropped before btrx decoding: encrypted for a channel the Desk Pod cannot read, or another port. */
    private var droppedEncrypted = 0
    private var droppedOtherPort = 0
    /** HEALTH/DETECT rx lines written at the session-log level: count per type, and when the last one was. */
    private val rateLogged = HashMap<Int, Int>()
    private val rateLastMs = HashMap<Int, Long>()
    private val rateSkipped = HashMap<Int, Int>()

    /** Opens the API session: wake bytes, then want_config_id. */
    @Synchronized
    fun start() {
        if (state != State.IDLE) return
        configId = (random.nextInt() and 0x7FFFFFFF).toLong().coerceAtLeast(1)
        state = State.CONFIGURING
        startedMs = clock()
        if (framing == Framing.SERIAL) send(MeshtasticSerial.wakeBytes())
        sendToRadio(MeshtasticSerial.toRadioWantConfig(configId))
        log.info("handshake: want_config_id=$configId sent")
    }

    /** USB: raw serial bytes, split into protobufs by the 0x94 0xC3 framing. */
    @Synchronized
    fun onBytes(data: ByteArray, length: Int = data.size) {
        if (state == State.CLOSED) return
        checkQuietGap()
        if (firstDrainPending) lastSerialRxMs = clock()
        decoder.feed(data, length)
    }

    /**
     * USB: ends the connect backlog after [SERIAL_QUIET_GAP_MS] without serial bytes
     * since config_complete_id (or the last bytes of the burst), then sends ROLE_GET.
     * The USB link calls this about every 100 ms; [onBytes] checks it too.
     */
    @Synchronized
    fun checkQuietGap() {
        if (framing != Framing.SERIAL || state != State.READY || !firstDrainPending) return
        if (clock() - lastSerialRxMs >= SERIAL_QUIET_GAP_MS) endConnectBacklog("after a ${SERIAL_QUIET_GAP_MS} ms quiet gap")
    }

    /** True from config_complete_id until the connect backlog has drained. */
    @get:Synchronized
    val connectBacklogPending: Boolean get() = firstDrainPending

    /**
     * The link's ROLE_REPORT wait, about once a second from session start. The 10 s answer
     * timeout runs from this session's ROLE_GET, which goes out only when the connect backlog
     * has drained (a ROLE_REPORT arriving inside the backlog counts as stale). A Desk Pod
     * whose queue is full (Meshtastic keeps 32) can take 10 s or more to drain over Bluetooth,
     * so the wait does not start at connect. A backlog still draining after
     * [MAX_CONNECT_BACKLOG_MS] is ended here so ROLE_GET still goes out. [retried]: ROLE_GET
     * was already repeated once.
     */
    @Synchronized
    fun roleCheck(retried: Boolean): RoleCheck {
        val now = clock()
        if (localRole != null) return RoleCheck(RoleWait.DONE)
        if (state != State.READY) {
            return if (now - startedMs >= CONFIG_TIMEOUT_MS) RoleCheck(RoleWait.NO_CONFIG) else RoleCheck(RoleWait.WAIT, ROLE_POLL_MS)
        }
        if (firstDrainPending) {
            if (now - readyMs < MAX_CONNECT_BACKLOG_MS) return RoleCheck(RoleWait.WAIT, ROLE_POLL_MS)
            log.warn("connect backlog still draining ${MAX_CONNECT_BACKLOG_MS / 1000} s after config_complete ($firstDrainFrames frame(s)); treating what follows as live")
            endConnectBacklog("by the ${MAX_CONNECT_BACKLOG_MS / 1000} s limit")
            return RoleCheck(RoleWait.WAIT, ROLE_TIMEOUT_MS)
        }
        val sent = roleGetSentMs ?: return RoleCheck(RoleWait.WAIT, ROLE_POLL_MS)
        val waited = now - sent
        if (waited < ROLE_TIMEOUT_MS) return RoleCheck(RoleWait.WAIT, ROLE_TIMEOUT_MS - waited)
        if (retried) return RoleCheck(RoleWait.GIVE_UP)
        sendRoleGet()
        return RoleCheck(RoleWait.RETRY, ROLE_TIMEOUT_MS)
    }

    private fun endConnectBacklog(how: String) {
        firstDrainPending = false
        log.info("connect backlog drained $how: $firstDrainFrames frame(s) queued while R2C was away")
        sendRoleGet()
    }

    /**
     * Bluetooth: one FromRadio protobuf per characteristic read. Pass every read,
     * including empty ones: the first empty read after config_complete_id ends the
     * connect backlog, and only then is ROLE_GET sent, so its ROLE_REPORT is live.
     */
    @Synchronized
    fun onFromRadio(proto: ByteArray) {
        if (state == State.CLOSED) return
        if (proto.isEmpty()) {
            if (state == State.READY && firstDrainPending) endConnectBacklog("at the first empty read")
            return
        }
        onProto(proto)
    }

    /** Bluetooth: the negotiated ATT MTU; ToRadio writes must fit in one ATT write (no long writes). */
    @Synchronized
    fun setAttMtu(mtu: Int) {
        maxToRadioBytes = minOf(MAX_TO_RADIO_BLE, mtu - 3)
        if (mtu < MIN_USABLE_MTU) log.warn("ERROR: Bluetooth MTU $mtu is too small for btrx (need at least $MIN_USABLE_MTU); larger ToRadio writes will be refused")
    }

    private fun sendToRadio(proto: ByteArray): Boolean {
        if (framing == Framing.PACKET && proto.size > maxToRadioBytes) {
            log.warn("ERROR: ToRadio of ${proto.size} bytes not sent: the Desk Pod accepts at most $maxToRadioBytes per Bluetooth write")
            return false
        }
        send(if (framing == Framing.SERIAL) MeshtasticSerial.frame(proto) else proto)
        return true
    }

    /**
     * Keep-alive; the transport calls this about every 5 minutes. Each heartbeat
     * carries a new nonce: Meshtastic drops a ToRadio identical to the previous one.
     * Nonces 0 and 1 are never sent: Meshtastic PhoneAPI treats heartbeat nonce 1 as
     * a trigger to broadcast node info over LoRa (default hop limit), so the first
     * nonce is 2 and the counter skips 0 and 1 when it wraps.
     */
    @Synchronized
    fun sendHeartbeat() {
        if (state != State.READY) return
        heartbeatNonce = nextHeartbeatNonce(heartbeatNonce)
        sendToRadio(MeshtasticSerial.toRadioHeartbeat(heartbeatNonce))
    }

    /** Asks a board what it is. [to] null means the Desk Pod itself (`^local`). */
    @Synchronized
    fun sendRoleGet(to: Long? = null): Boolean {
        val dest = to ?: localNode ?: run {
            log.warn("ROLE_GET not sent: the Desk Pod's node number is not known yet")
            return false
        }
        sendFrame(dest, P.roleGetFrame(nextSeq()))
        if (dest == localNode) roleGetSentMs = clock()
        log.info("tx ROLE_GET to ${nodeName(dest)}")
        return true
    }

    /**
     * Sends one btrx frame on port 256 (hop_limit 0). PAIR_CONFIRM, TIME_PING and
     * FILTER_LIST build on this. Frames over [MAX_SEND_FRAME] bytes are refused so the
     * ToRadio stays inside one Bluetooth write (FILTER_LIST must be chunked to fit).
     */
    @Synchronized
    override fun sendFrame(to: Long, frame: ByteArray) {
        if (frame.size > MAX_SEND_FRAME) {
            log.warn("ERROR: btrx frame of ${frame.size} bytes not sent to ${nodeName(to)}: limit is $MAX_SEND_FRAME (split it)")
            return
        }
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
        val dropped = if (droppedEncrypted + droppedOtherPort == 0) "" else " dropped: encrypted=$droppedEncrypted other-port=$droppedOtherPort"
        return "frames: $frames$backlog$rejects$dropped"
    }

    /** Packets dropped before btrx decoding since connecting: (encrypted, other port). */
    @Synchronized
    fun droppedCounts(): Pair<Int, Int> = droppedEncrypted to droppedOtherPort

    /** Live frames of one type (delivered after config_complete_id). */
    @Synchronized
    fun frameCount(typeName: String): Int = frameCounts[typeName] ?: 0

    /** Backlog frames of one type (queued while R2C was away; old, display-only). */
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
                // Queued frames follow; ask for the role once they are through.
                firstDrainPending = true
                firstDrainFrames = 0
                lastSerialRxMs = clock()
                readyMs = clock()
            }
            is FromRadio.Packet -> onPacket(msg)
            is FromRadio.Other -> Unit
        }
    }

    private fun onPacket(packet: FromRadio.Packet) {
        if (packet.portnum != P.PRIVATE_APP_PORT || packet.payload == null) {
            onDropped(packet)
            return
        }
        val now = clock()
        // Before config_complete_id, or in the burst right after it (Bluetooth: up to the
        // first empty read; USB: up to the first quiet gap).
        val backlog = state != State.READY || firstDrainPending
        if (backlog && state == State.READY) firstDrainFrames++
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
        // STEER (every second) and the rare frames a bench test needs are logged at debug,
        // which shows at every app log level; the rest (DETECT, HEALTH...) at info.
        // HEALTH and DETECT: the first few, then one per type every 10 s, at debug too so
        // they reach the session log; the rest at info as before.
        if (frame.type in ALWAYS_LOGGED) log.debug("rx $radio ${BtrxFrameLog.format(frame)}")
        else if (frame.type in RATE_LOGGED) logRateLimited(frame.type, now, "rx $radio ${BtrxFrameLog.format(frame)}")
        else log.info("rx $radio ${BtrxFrameLog.format(frame)}")
        if (frame.type == P.STEER) logSteerFlagChange(packet.from, frame)
        if (frame.type == P.ROLE_REPORT && packet.from == localNode && backlog) {
            log.info("stale ROLE_REPORT in the connect backlog ignored; waiting for the answer to this session's ROLE_GET")
        } else if (frame.type == P.ROLE_REPORT && packet.from == localNode) {
            try {
                val report = P.parseRoleReport(frame.payload)
                localRole = report
                if (report.role == P.ROLE_DESK_POD) {
                    val build = P.buildSupportText(report.buildHash)
                    val since = " %.1f s after connect".format(Locale.US, (clock() - startedMs) / 1000.0)
                    log.info("Desk Pod confirmed: role=3 build_hash=0x%04x (%s)%s".format(report.buildHash, build, since))
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

    /**
     * A packet that is not a decoded port-256 packet. The first [DROPPED_LOG_LIMIT] of a
     * session are logged at debug (which reaches the session log) with the MeshPacket
     * payload_variant, portnum, sender and length; all are counted.
     */
    private fun onDropped(packet: FromRadio.Packet) {
        val encrypted = packet.portnum == null && packet.encryptedLength != null
        if (encrypted) droppedEncrypted++ else droppedOtherPort++
        val n = droppedEncrypted + droppedOtherPort
        if (n <= DROPPED_LOG_LIMIT) {
            log.debug("dropped packet $n/$DROPPED_LOG_LIMIT logged: payload_variant=${packet.payloadVariant} " +
                "portnum=${packet.portnum ?: "-"} from=${nodeName(packet.from)} len=${packet.payloadLength}" +
                if (encrypted) " (encrypted for a channel the Desk Pod cannot read)" else "")
        }
        listener.onPacketDropped(encrypted)
    }

    private fun logRateLimited(type: Int, now: Long, line: String) {
        val logged = rateLogged[type] ?: 0
        val last = rateLastMs[type]
        if (logged < RATE_LOG_FIRST || last == null || now - last >= RATE_LOG_INTERVAL_MS) {
            val skipped = rateSkipped.remove(type) ?: 0
            rateLogged[type] = logged + 1
            rateLastMs[type] = now
            log.debug(if (skipped > 0) "$line (+$skipped not logged since the last one)" else line)
        } else {
            rateSkipped[type] = (rateSkipped[type] ?: 0) + 1
            log.info(line)
        }
    }

    private val steerFlags = HashMap<Long, Int>()

    /** One line whenever a Drone Pod's STEER flags change (bit 6: IMU calibration in use). */
    private fun logSteerFlagChange(from: Long, frame: P.Frame) {
        val flags = runCatching { P.parseSteer(frame.payload).flags }.getOrNull() ?: return
        val old = steerFlags.put(from, flags)
        if (old != flags) {
            log.debug("steer flags from ${nodeName(from)}: ${old?.let { P.steerFlagNames(it) } ?: "(first)"} -> ${P.steerFlagNames(flags)}" +
                " (imu_cal=${if (flags and P.STEER_F_IMU_CAL != 0) "yes" else "no"})")
        }
    }

    private fun count(map: MutableMap<String, Int>, key: String) {
        map[key] = (map[key] ?: 0) + 1
    }

    companion object {
        /** The Desk Pod's ATT MTU is 247 and it rejects long writes: 247 - 3. */
        const val MAX_TO_RADIO_BLE = 244
        /** Largest btrx frame R2C sends; leaves room for the ToRadio/MeshPacket wrapper. */
        const val MAX_SEND_FRAME = 200
        /** Below this ATT MTU even PAIR_CONFIRM's ToRadio does not fit comfortably. */
        const val MIN_USABLE_MTU = 100
        /** USB: the connect backlog ends at the first gap this long with no serial bytes. */
        const val SERIAL_QUIET_GAP_MS = 300L
        /** Frame types logged at debug (shown at every app log level): STEER, and the rare
         *  frames that pairing, the role check and IMU calibration turn on. */
        /** Frame types logged at debug rate-limited: the first [RATE_LOG_FIRST], then one per [RATE_LOG_INTERVAL_MS]. */
        val RATE_LOGGED = setOf(P.HEALTH, P.DETECT)
        const val RATE_LOG_FIRST = 3
        const val RATE_LOG_INTERVAL_MS = 10_000L
        /** Dropped (non-btrx) packets logged per session; all are counted. */
        const val DROPPED_LOG_LIMIT = 5
        val ALWAYS_LOGGED = setOf(P.STEER, P.PAIR_REQ, P.PAIR_ACK, P.DESK_PAIR, P.ROLE_REPORT, P.IMU_CAL_ACK)
        /** ROLE_REPORT answer timeout, counted from this session's ROLE_GET. */
        const val ROLE_TIMEOUT_MS = 10_000L
        /** How often the link checks the role wait. */
        const val ROLE_POLL_MS = 1_000L
        /** No config_complete_id this long after want_config_id: the Desk Pod is not answering. */
        const val CONFIG_TIMEOUT_MS = 30_000L
        /** A connect backlog still draining this long after config_complete is ended (ROLE_GET goes out). */
        const val MAX_CONNECT_BACKLOG_MS = 30_000L

        /** Heartbeat nonce after [previous]: wraps at 32 bits and skips 0 and 1. */
        fun nextHeartbeatNonce(previous: Long): Long {
            val next = (previous + 1) and 0xFFFFFFFFL
            return if (next < 2) 2 else next
        }

        fun nodeName(node: Long): String = "!%08x".format(node and 0xFFFFFFFFL)
    }
}

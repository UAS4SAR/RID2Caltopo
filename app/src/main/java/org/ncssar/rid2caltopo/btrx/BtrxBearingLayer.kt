package org.ncssar.rid2caltopo.btrx

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/** Fade settings (spec section 2.4): Developer Tools > BTRX, saved in the app config. */
data class BtrxBearingSettings(val fadeTotalS: Float = DEFAULT_TOTAL_S, val fadeShapeP: Float = DEFAULT_SHAPE_P) {
    val fadeMinutes: Int get() = (fadeTotalS / 60f).roundToInt()

    fun clamped() = BtrxBearingSettings(fadeTotalS.coerceIn(MIN_TOTAL_S, MAX_TOTAL_S), fadeShapeP.coerceIn(MIN_SHAPE_P, MAX_SHAPE_P))

    companion object {
        /** Slider range and defaults of the UI (Ken: 1-30 min, default 10; p 1-4, linear). */
        const val MIN_TOTAL_S = 60f
        const val MAX_TOTAL_S = 1800f
        const val DEFAULT_TOTAL_S = 600f
        const val MIN_SHAPE_P = 1f
        const val MAX_SHAPE_P = 4f
        const val DEFAULT_SHAPE_P = 1f

        /** Only when the native library is missing: weight 1 - (a/N)^p is 0.5 at N * 0.5^(1/p). */
        fun halfStrengthFallbackS(totalS: Float, shapeP: Float): Float = totalS * 0.5f.pow(1f / shapeP)

        /** "m:ss". */
        fun minSec(seconds: Float): String {
            val s = seconds.roundToInt().coerceAtLeast(0)
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }

        fun shapeLabel(p: Float): String = when {
            p < 1.05f -> "Linear"
            p > 3.95f -> "Accelerating"
            else -> String.format(Locale.US, "p = %.1f", p)
        }
    }
}

data class BtrxBearingEstimate(
    val lat: Double, val lon: Double,
    val axisMajor1sM: Float, val axisMinor1sM: Float, val axisMajorDeg: Float,
    val ambiguous: Boolean, val secondLat: Double, val secondLon: Double, val secondMass: Float,
) {
    /** 2 sigma long axis, the "± m" of the label. */
    val plusMinus2sM: Float get() = 2f * axisMajor1sM
}

data class BtrxBearingRay(
    val apexLat: Double, val apexLon: Double, val centreTrueDeg: Float, val halfWidthDeg: Float,
    val lengthM: Float, val mirrorTrueDeg: Float?, val tUs: Long,
)

data class BtrxBearingSource(
    val index: Int,
    val key: Long,
    val shortId: String,
    val provisional: Boolean,
    val devClass: Int,
    /** 0xRRGGBB. */
    val rgb: Int,
    val flags: Int,
    val firstHeardUs: Long,
    val lastHeardUs: Long,
    val reportsIn: Int,
    val reportsUsed: Int,
    val reportsInWindow: Int,
    val peakAlpha: Float,
    val estimate: BtrxBearingEstimate?,
    val latestRay: BtrxBearingRay?,
) {
    val muted: Boolean get() = flags and BtrxBearingNative.SRC_MUTED != 0
    val ignored: Boolean get() = flags and BtrxBearingNative.SRC_IGNORED != 0
    val className: String get() = BtrxBearingText.className(devClass)
}

data class BtrxBearingLag(val count: Int, val p50Ms: Int, val p90Ms: Int, val p99Ms: Int, val maxMs: Int, val lateDrops: Int)

data class BtrxBearingStats(
    val reportsIn: Int, val entriesIn: Int, val used: Int, val gated: Int, val noHeading: Int, val noPosition: Int,
    val lateDropped: Int, val ringDropped: Int, val bucketsExpiredEarly: Int, val ignored: Int, val backlogHistory: Int,
    val steerTruncated: Int, val extrasUnresolved: Int, val provisionalMerges: Int,
    val deskLostKnown: Boolean, val deskLost: Long, val deskLostSinceConnect: Long, val podALost: Long,
    val tilesInUse: Int, val tilesTotal: Int, val fadeTotalS: Float, val fadeShapeP: Float,
    val podOffsetMs: Int, val podOffsetErrMs: Int, val offsetSource: Int, val podSession: Long, val displayLagMs: Int,
    /** By btrx_bearing_stage_t. */
    val lag: List<BtrxBearingLag>,
)

class BtrxBearingSnapshot(
    val seq: Long, val tUs: Long, val width: Int, val height: Int,
    val north: Double, val south: Double, val east: Double, val west: Double,
    /** width * height * 4, premultiplied RGBA, row 0 north (Bitmap.Config.ARGB_8888 byte order). */
    val rgba: ByteArray,
    val sources: List<BtrxBearingSource>,
    val stats: BtrxBearingStats,
)

/**
 * Why the layer draws nothing (field test, Nov 13): [line] is the short map status, null
 * while shading is on screen; [stats] are the library counters (also before the first
 * snapshot, when no drone pose exists yet).
 */
data class BtrxBearingStatus(val line: String?, val stats: BtrxBearingStats?)

/** Inputs of [BtrxBearingText.statusLine], the same on the iPad. Ages in seconds, null = never. */
data class BtrxBearingStatusInput(
    val stats: BtrxBearingStats?,
    val designator: String?,
    val liveStreams: Int,
    val steerAgeS: Float?,
    val yawAgeS: Float?,
    val positionAgeS: Float?,
    /** Unmuted shading is on screen. */
    val drawing: Boolean,
    /** Shading exists but every source with it is hidden (muted or ignored). */
    val allHidden: Boolean,
)

/**
 * Android boundary of the BTRX bearing layer (spec sections 3 and 7): owns the native
 * handle, stamps and pushes inputs, drives `tick` from the BTRX worker every 200 ms,
 * schedules TIME_PING, and publishes snapshots for the map overlay. Same surface as the
 * iPad's BtrxBearingLayer.swift. Developer Tools only; nothing goes to CalTopo.
 *
 * Threads: [start], [stop], [onFrame], [onConnected] and [tick] run on the BTRX worker.
 * [onYaw] (SEI thread), [onPosition] (RID thread) and [setMuted]/[sourcesAt] (UI) are
 * O(1) or a few microseconds and never wait on grid work.
 */
class BtrxBearingLayer(
    private val engine: BtrxBearingEngine,
    /** Host monotonic clock, microseconds (SystemClock.elapsedRealtimeNanos() / 1000). */
    private val monoUs: () -> Long,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /** BBv1/BBLAG/BBRND lines (lag trace on), to the session log. */
    private val traceLog: (String) -> Unit = {},
    private val incidentKey: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } },
) {
    private val handleLock = ReentrantReadWriteLock()
    @Volatile private var handle = 0L
    @Volatile private var lagTrace = false
    @Volatile var steeringDesignator: String? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _snapshot = MutableStateFlow<BtrxBearingSnapshot?>(null)
    val snapshot: StateFlow<BtrxBearingSnapshot?> = _snapshot.asStateFlow()
    @Volatile var lastError: String? = null
        private set
    private val _status = MutableStateFlow<BtrxBearingStatus?>(null)
    /** Map status line and counters, refreshed about once a second while the layer is on. */
    val status: StateFlow<BtrxBearingStatus?> = _status.asStateFlow()
    /** Live (non-playback) stream count, for the status line (worker, each tick). */
    @Volatile var liveStreamCount = 0

    // Host times (us) of the last STEER from the paired pod, yaw and position accepted; 0 = never.
    @Volatile private var lastSteerUs = 0L
    @Volatile private var lastYawUs = 0L
    @Volatile private var lastPositionUs = 0L
    private var nextStatusUs = 0L
    private val statsBuf = DoubleArray(BtrxBearingNative.STATS_SIZE)

    private val aglByDesignator = ConcurrentHashMap<String, Pair<Float, Long>>()

    // Snapshot copy buffers (worker only) and the lock that keeps sourcesAt's indexes and
    // the published Kotlin snapshot the same snapshot.
    private val snapLock = Any()
    private var rgbaBuffer: ByteBuffer? = null
    private val keys = LongArray(BtrxBearingNative.MAX_SOURCES)
    private val shortIds = ByteArray(8 * BtrxBearingNative.MAX_SOURCES)
    private val meta = DoubleArray(BtrxBearingNative.META_SIZE)
    private val healthOut = LongArray(4)

    // TIME_PING (spec 3.2): every 30 s, plus a burst of 3 at start, pod restart and reconnect.
    private var pingSeq = 0
    private val pingT1Us = LongArray(256)
    private val pingT1Ms = LongArray(256) { -1L }
    private var burstLeft = 0
    private var nextPingUs = Long.MAX_VALUE
    private var lastPodSession = -1L
    private var lastRenderedSeq = -1L

    val isRunning: Boolean get() = handle != 0L

    /** Worker. Creates the native layer; false (and [lastError]) if the library is missing or create fails. */
    fun start(settings: BtrxBearingSettings, trace: Boolean, linkUp: Boolean): Boolean {
        if (handle != 0L) return true
        if (!engine.available) {
            lastError = "The bearing layer library is not in this build."
            log("bearing: library unavailable; layer stays off")
            return false
        }
        val s = settings.clamped()
        val h = engine.create(incidentKey(), s.fadeTotalS, s.fadeShapeP, trace)
        if (h == 0L) {
            lastError = "The bearing layer could not start."
            log("bearing: create failed; layer stays off")
            return false
        }
        lastError = null
        lagTrace = trace
        handleLock.write { handle = h }
        rgbaBuffer = null
        lastPodSession = -1L
        lastRenderedSeq = -1L
        pingT1Ms.fill(-1L)
        lastSteerUs = 0L; lastYawUs = 0L; lastPositionUs = 0L; nextStatusUs = 0L
        val now = monoUs()
        if (linkUp) engine.noteConnected(h, now)
        startBurst(now)
        _running.value = true
        log("bearing: layer on (fade ${BtrxBearingSettings.minSec(s.fadeTotalS)}, p ${"%.1f".format(Locale.US, s.fadeShapeP)}${if (trace) ", lag trace" else ""})")
        if (trace) traceLog("BBv1 F $now ${s.fadeTotalS} ${s.fadeShapeP}")
        return true
    }

    /** Worker. Destroys the native layer and clears the overlay. */
    fun stop() {
        val h = handle
        if (h == 0L) return
        handleLock.write { handle = 0L }
        engine.destroy(h)
        synchronized(snapLock) { _snapshot.value = null }
        _status.value = null
        _running.value = false
        burstLeft = 0
        nextPingUs = Long.MAX_VALUE
        log("bearing: layer off")
    }

    private inline fun <T> withHandle(default: T, block: (Long) -> T): T = handleLock.read {
        val h = handle
        if (h == 0L) default else block(h)
    }

    private fun startBurst(nowUs: Long) {
        burstLeft = PING_BURST
        nextPingUs = nowUs
    }

    /** Host time of a wall-clock stamp taken [wallStampMs]; never in the future, at most [maxBackMs] back. */
    private fun hostUsOfWall(wallStampMs: Long, nowUs: Long, maxBackMs: Long): Long {
        val back = (wallMs() - wallStampMs).coerceIn(0L, maxBackMs)
        return nowUs - back * 1000L
    }

    // ---- inputs ----

    /**
     * Worker (from BtrxClient.Listener.onFrame). [fromPairedPod]: from the paired Drone
     * Pod's node; [fromDeskPod]: the Desk Pod's own frame (its HEALTH). STEER, TIME_PONG
     * and HEALTH only; everything else is ignored.
     */
    fun onFrame(frame: P.Frame, meta: BtrxClient.RadioMeta, fromPairedPod: Boolean, fromDeskPod: Boolean) {
        if (handle == 0L) return
        val now = monoUs()
        val arrivalUs = hostUsOfWall(meta.arrivalMs, now, MAX_ARRIVAL_BACK_MS)
        when (frame.type) {
            P.STEER -> if (fromPairedPod) withHandle(Unit) { h ->
                if (!meta.backlog) lastSteerUs = now
                val st = engine.pushSteer(h, frame.payload, arrivalUs, now, meta.backlog, lagTrace)
                if (st < 0 && st != E_LATE) log("bearing: STEER not used (status $st)")
            }
            P.TIME_PONG -> if (fromPairedPod) onPong(frame.payload, arrivalUs)
            P.HEALTH -> if (fromPairedPod || fromDeskPod) withHandle(Unit) { h ->
                val st = engine.pushHealth(h, arrivalUs, frame.payload, healthOut)
                if (st >= 0 && lagTrace) {
                    val lost = if (healthOut[2] != 0L) healthOut[3].toString() else "unknown"
                    traceLog("BBv1 H $arrivalUs ${healthOut[0]} ${healthOut[1]} $lost")
                }
            }
        }
    }

    private fun onPong(payload: ByteArray, t4Us: Long) {
        val t = try { P.parseTime(payload) } catch (_: P.PayloadException) { return }
        val i = t.pingSeq and 0xFF
        if (pingT1Ms[i] < 0 || pingT1Ms[i] != t.t1) return   // not ours, or a stale sequence
        val t1Us = pingT1Us[i]
        pingT1Ms[i] = -1L
        if (t4Us < t1Us || t4Us - t1Us > MAX_PONG_RTT_US) return
        withHandle(Unit) { h -> engine.pushTimeSync(h, t1Us, t.t2, t.t3, t4Us) }
        if (lagTrace) traceLog("BBv1 S $t1Us ${t.t2} ${t.t3} $t4Us")
    }

    /** Worker: the app (re)connected to the Desk Pod. */
    fun onConnected() {
        if (handle == 0L) return
        val now = monoUs()
        withHandle(Unit) { h -> engine.noteConnected(h, now) }
        startBurst(now)
    }

    /**
     * SEI thread: DJI aircraft yaw, true north ([DjiCameraOrientation.aircraftYawTrueNorthDeg]),
     * for stream [designator] decoded at wall time [decodedAtWallMs]. Only the stream marked
     * "Drone Pod is on this drone" feeds the layer.
     */
    fun onYaw(designator: String, yawTrueDeg: Double, decodedAtWallMs: Long) {
        if (handle == 0L || !yawTrueDeg.isFinite() || !sameDrone(designator)) return
        val now = monoUs()
        val t = hostUsOfWall(decodedAtWallMs, now, MAX_YAW_BACK_MS)
        withHandle(Unit) { h -> engine.pushPose(h, t, now, BtrxBearingNative.POSE_YAW, 0.0, 0.0, 0f, yawTrueDeg.toFloat()) }
        lastYawUs = now
        if (lagTrace) traceLog("BBv1 P $t ${BtrxBearingNative.POSE_YAW} 0 0 0 ${"%.3f".format(Locale.US, yawTrueDeg)} 0 0")
    }

    /** Main thread (DroneAltitudeCoordinator): the latest height above ground for [designator]. */
    fun noteAgl(designator: String, aglM: Double?) {
        if (handle == 0L) return
        val key = designator.trim().uppercase()
        if (aglM == null || !aglM.isFinite()) aglByDesignator.remove(key) else aglByDesignator[key] = aglM.toFloat() to monoUs()
    }

    /** RID thread: a track point for [designator] with its fix time [fixWallMs] (epoch ms). */
    fun onPosition(designator: String, lat: Double, lon: Double, fixWallMs: Long) {
        if (handle == 0L || !lat.isFinite() || !lon.isFinite() || (lat == 0.0 && lon == 0.0) || !sameDrone(designator)) return
        val now = monoUs()
        val t = hostUsOfWall(if (fixWallMs > 0) fixWallMs else wallMs(), now, MAX_POS_BACK_MS)
        var mask = BtrxBearingNative.POSE_POSITION
        val agl = aglByDesignator[designator.trim().uppercase()]?.takeIf { now - it.second <= AGL_MAX_AGE_US }?.first
        if (agl != null) mask = mask or BtrxBearingNative.POSE_AGL
        withHandle(Unit) { h -> engine.pushPose(h, t, now, mask, lat, lon, agl ?: 0f, 0f) }
        lastPositionUs = now
        if (lagTrace) traceLog("BBv1 P $t $mask ${"%.7f".format(Locale.US, lat)} ${"%.7f".format(Locale.US, lon)} ${agl ?: 0f} 0 0 0")
    }

    private fun sameDrone(designator: String): Boolean {
        val chosen = steeringDesignator ?: return false
        return chosen.trim().equals(designator.trim(), ignoreCase = true)
    }

    // ---- work ----

    /**
     * Worker, every 200 ms. Runs the library tick, copies a newly published snapshot,
     * drains the library's log lines, and sends any due TIME_PING through [sendPing]
     * (pingSeq, t1 ms; false when there is no link or paired pod).
     */
    fun tick(sendPing: (Int, Long) -> Boolean) {
        if (handle == 0L) return
        val now = monoUs()
        val r = withHandle(0) { h -> engine.tick(h, now) }
        if (r == 1) copySnapshot()
        if (now >= nextStatusUs) {
            nextStatusUs = now + STATUS_INTERVAL_US
            updateStatus(now)
        }
        withHandle(null) { h -> engine.drainLog(h) }?.let { lines -> lines.split('\n').forEach(traceLog) }
        if (now >= nextPingUs) {
            val seq = pingSeq
            val t1Ms = (now / 1000L) and 0xFFFFFFFFL
            if (sendPing(seq, t1Ms)) {
                pingT1Us[seq] = now
                pingT1Ms[seq] = t1Ms
                pingSeq = (pingSeq + 1) and 0xFF
            }
            // A ping that could not go out is not retried early: the schedule keeps its pace.
            burstLeft = (burstLeft - 1).coerceAtLeast(0)
            nextPingUs = now + if (burstLeft > 0) PING_BURST_SPACING_US else PING_INTERVAL_US
        }
    }

    private fun ageS(t: Long, now: Long): Float? = if (t == 0L) null else (now - t).coerceAtLeast(0L) / 1e6f

    /** Worker, about once a second: the map status line from the stats and the pose feeds. */
    private fun updateStatus(now: Long) {
        val stats = if (withHandle(-1) { h -> engine.copyStats(h, statsBuf) } == 0) decodeStats(statsBuf, 0) else _snapshot.value?.stats
        val snap = _snapshot.value
        val shaded = snap?.sources?.filter { it.peakAlpha > 0f }.orEmpty()
        val drawing = shaded.any { !it.muted && !it.ignored }
        val input = BtrxBearingStatusInput(
            stats = stats, designator = steeringDesignator, liveStreams = liveStreamCount,
            steerAgeS = ageS(lastSteerUs, now), yawAgeS = ageS(lastYawUs, now), positionAgeS = ageS(lastPositionUs, now),
            drawing = drawing, allHidden = shaded.isNotEmpty() && !drawing,
        )
        val next = BtrxBearingStatus(BtrxBearingText.statusLine(input), stats)
        val prev = _status.value
        if (prev?.line != next.line) log("bearing: status ${next.line?.replace('\n', ' ') ?: "drawing"}")
        _status.value = next
    }

    private fun copySnapshot() {
        val buf = rgbaBuffer ?: ByteBuffer.allocateDirect(MAX_RGBA_BYTES).order(ByteOrder.nativeOrder()).also { rgbaBuffer = it }
        synchronized(snapLock) {
            val n = withHandle(-1) { h -> engine.copySnapshot(h, buf, keys, shortIds, meta) }
            if (n < 0) return
            val snap = decode(buf, n)
            _snapshot.value = snap
            if (lastPodSession >= 0 && snap.stats.podSession != lastPodSession) {
                log("bearing: Drone Pod session ${snap.stats.podSession} (pod restart); TIME_PING burst")
                startBurst(monoUs())
            }
            lastPodSession = snap.stats.podSession
        }
    }

    private fun decodeStats(m: DoubleArray, q: Int): BtrxBearingStats {
        fun st(i: Int) = m[q + i]
        val lag = (0 until BtrxBearingNative.STAGE_COUNT).map { k ->
            val o = 27 + 6 * k
            BtrxBearingLag(st(o).toInt(), st(o + 1).toInt(), st(o + 2).toInt(), st(o + 3).toInt(), st(o + 4).toInt(), st(o + 5).toInt())
        }
        return BtrxBearingStats(
            reportsIn = st(0).toInt(), entriesIn = st(1).toInt(), used = st(2).toInt(), gated = st(3).toInt(),
            noHeading = st(4).toInt(), noPosition = st(5).toInt(), lateDropped = st(6).toInt(), ringDropped = st(7).toInt(),
            bucketsExpiredEarly = st(8).toInt(), ignored = st(9).toInt(), backlogHistory = st(10).toInt(),
            steerTruncated = st(11).toInt(), extrasUnresolved = st(12).toInt(), provisionalMerges = st(13).toInt(),
            deskLostKnown = st(14) != 0.0, deskLost = st(15).toLong(), deskLostSinceConnect = st(16).toLong(), podALost = st(17).toLong(),
            tilesInUse = st(18).toInt(), tilesTotal = st(19).toInt(), fadeTotalS = st(20).toFloat(), fadeShapeP = st(21).toFloat(),
            podOffsetMs = st(22).toInt(), podOffsetErrMs = st(23).toInt(), offsetSource = st(24).toInt(), podSession = st(25).toLong(),
            displayLagMs = st(26).toInt(), lag = lag,
        )
    }

    private fun decode(buf: ByteBuffer, count: Int): BtrxBearingSnapshot {
        val m = meta
        val w = m[2].toInt()
        val ht = m[3].toInt()
        val rgba = ByteArray(w * ht * 4)
        buf.position(0)
        buf.get(rgba, 0, minOf(rgba.size, buf.capacity()))
        buf.position(0)
        val stats = decodeStats(m, BtrxBearingNative.META_STATS)
        val sources = (0 until count.coerceAtMost(BtrxBearingNative.MAX_SOURCES)).map { i ->
            val o = BtrxBearingNative.META_SOURCES + BtrxBearingNative.META_PER_SOURCE * i
            fun v(j: Int) = m[o + j]
            val id = String(shortIds, 8 * i, 8, Charsets.US_ASCII).trimEnd('\u0000')
            val est = if (v(13) != 0.0) BtrxBearingEstimate(
                v(15), v(16), v(17).toFloat(), v(18).toFloat(), v(19).toFloat(), v(14) != 0.0, v(20), v(21), v(22).toFloat(),
            ) else null
            val ray = if (v(23) != 0.0) BtrxBearingRay(
                v(24), v(25), v(26).toFloat(), v(27).toFloat(), v(28).toFloat(), v(29).toFloat().takeIf { it.isFinite() }, v(30).toLong(),
            ) else null
            BtrxBearingSource(
                index = i, key = keys[i], shortId = id, provisional = v(0) != 0.0, devClass = v(1).toInt(),
                rgb = (v(2).toInt() shl 16) or (v(3).toInt() shl 8) or v(4).toInt(), flags = v(5).toInt(),
                firstHeardUs = v(6).toLong(), lastHeardUs = v(7).toLong(), reportsIn = v(8).toInt(), reportsUsed = v(9).toInt(),
                reportsInWindow = v(10).toInt(), peakAlpha = v(12).toFloat(), estimate = est, latestRay = ray,
            )
        }
        return BtrxBearingSnapshot(m[0].toLong(), m[1].toLong(), w, ht, m[4], m[5], m[6], m[7], rgba, sources, stats)
    }

    // ---- UI ----

    /** UI thread, microseconds: sources with shading within [radiusM] (muted included), by contribution. */
    fun sourcesAt(lat: Double, lon: Double, radiusM: Float): List<BtrxBearingSource> = synchronized(snapLock) {
        val snap = _snapshot.value ?: return emptyList()
        val out = IntArray(BtrxBearingNative.MAX_SOURCES)
        val n = withHandle(0) { h -> engine.sourcesAt(h, lat, lon, radiusM, out) }
        (0 until n).mapNotNull { snap.sources.getOrNull(out[it]) }
    }

    /** UI thread: panel checkboxes and "Mute all"/"Unmute all" (shown at the next tick). */
    fun setMuted(keys: List<Long>, muted: Boolean) {
        if (keys.isEmpty()) return
        val now = monoUs()
        withHandle(Unit) { h ->
            engine.setSourceFlags(h, keys.toLongArray(), BtrxBearingNative.SRC_MUTED, if (muted) BtrxBearingNative.SRC_MUTED else 0)
        }
        if (lagTrace) keys.forEach { traceLog("BBv1 M $now ${"%016x".format(it)} ${if (muted) 1 else 0}") }
    }

    /** BTRX pane: own aircraft (never accumulated). */
    fun setIgnored(key: Long, ignored: Boolean) {
        withHandle(Unit) { h ->
            engine.setSourceFlags(h, longArrayOf(key), BtrxBearingNative.SRC_IGNORED, if (ignored) BtrxBearingNative.SRC_IGNORED else 0)
        }
    }

    fun setFade(settings: BtrxBearingSettings) {
        val s = settings.clamped()
        withHandle(Unit) { h -> engine.setFade(h, s.fadeTotalS, s.fadeShapeP) }
        if (lagTrace) traceLog("BBv1 F ${monoUs()} ${s.fadeTotalS} ${s.fadeShapeP}")
    }

    fun reset() {
        withHandle(Unit) { h -> engine.reset(h, BtrxBearingNative.RESET_ALL, 0L) }
    }

    /** UI thread: the overlay showing snapshot [seq] is on screen (T7). */
    fun noteRendered(seq: Long) {
        if (seq == lastRenderedSeq) return
        lastRenderedSeq = seq
        val now = monoUs()
        withHandle(Unit) { h -> engine.noteRendered(h, seq, now) }
    }

    fun halfStrengthS(settings: BtrxBearingSettings): Float {
        val s = settings.clamped()
        return engine.halfStrengthS(s.fadeTotalS, s.fadeShapeP)
    }

    /** Test hook: the next TIME_PING time (host us), Long.MAX_VALUE when none is scheduled. */
    internal val nextPingAtUs: Long get() = nextPingUs

    companion object {
        const val TICK_MS = 200L
        const val PING_BURST = 3
        const val PING_BURST_SPACING_US = 1_000_000L
        const val PING_INTERVAL_US = 30_000_000L
        const val MAX_PONG_RTT_US = 10_000_000L
        const val STATUS_INTERVAL_US = 1_000_000L
        private const val MAX_ARRIVAL_BACK_MS = 2_000L
        private const val MAX_YAW_BACK_MS = 2_000L
        private const val MAX_POS_BACK_MS = 3_000L
        private const val AGL_MAX_AGE_US = 10_000_000L
        private const val E_LATE = -5
        /** 192 x 192 cells (config default), with room for a larger grid. */
        private const val MAX_RGBA_BYTES = 256 * 256 * 4
    }
}

/**
 * Where the video and RID code hand yaw, position and AGL to a running bearing layer
 * without touching [BtrxManager]: null while the layer is off, so the SEI and RID paths
 * pay one volatile read.
 */
object BtrxBearingFeed {
    @Volatile var sink: BtrxBearingLayer? = null
}

/** Panel and pane wording, identical to the iPad (BtrxBearingText in BtrxBearingLayer.swift). */
object BtrxBearingText {
    const val PANEL_TITLE = "BTRX sources here"
    const val MUTE_ALL = "Mute all"
    const val UNMUTE_ALL = "Unmute all"
    const val DONE = "Done"
    const val SECTION = "Bearing map layer"
    const val LAYER_SWITCH = "Show bearing layer on map"
    const val LAYER_SWITCH_HINT = "Off at every launch. Shades where each BTRX source is likely to be; tap the shading to show or hide sources."
    const val FADE_TIME = "Fade time"
    const val FADE_SHAPE = "Fade shape"
    const val LAG_TRACE = "Lag trace log (restarts the layer)"
    const val SOURCES = "Sources"
    const val NO_SOURCES = "No sources yet."
    const val IGNORE = "Ignore (own aircraft)"
    const val UNIGNORE = "Stop ignoring"

    fun className(devClass: Int): String = when (devClass) {
        1 -> "phone"
        2 -> "watch"
        3 -> "hearing aid"
        4 -> "tag"
        5 -> "audio"
        else -> "unknown"
    }

    fun minutes(m: Int): String = if (m == 1) "1 min" else "$m min"

    fun halfStrength(seconds: Float): String = "half strength at ${BtrxBearingSettings.minSec(seconds)}"

    fun lastHeard(nowUs: Long, lastUs: Long): String {
        val s = ((nowUs - lastUs) / 1_000_000L).coerceAtLeast(0L)
        return when {
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    fun reports(n: Int): String = if (n == 1) "1 rpt" else "$n rpt"

    /** Map label (spec 5): `phone · Q3x9Kd · 23 rpt · ±18 m`, plus "ambiguous". */
    fun label(s: BtrxBearingSource): String {
        val base = "${s.className} · ${s.shortId} · ${reports(s.reportsInWindow)}"
        val est = s.estimate ?: return base
        val pm = " · ±${est.plusMinus2sM.roundToInt()} m"
        return base + pm + if (est.ambiguous) " · ambiguous" else ""
    }

    /** Status screen (spec 3.4): "Reports lost while no app: N ...". */
    fun lostLine(st: BtrxBearingStats): String =
        if (!st.deskLostKnown) "Reports lost while no app: unknown (older firmware)"
        else "Reports lost while no app: ${st.deskLostSinceConnect} since connect (${st.deskLost} since the Desk Pod started)"

    fun lagLine(st: BtrxBearingStats): String {
        val total = st.lag.getOrNull(7)
        val pod = st.lag.getOrNull(0)
        fun f(l: BtrxBearingLag?) = if (l == null || l.count == 0) "–" else "p50 ${l.p50Ms} / p99 ${l.p99Ms} ms"
        return "Lag: pod→arrival ${f(pod)}; total ${f(total)}; late ${st.lateDropped}, history ${st.backlogHistory}"
    }

    fun countsLine(st: BtrxBearingStats): String =
        "Reports ${st.reportsIn}: used ${st.used}, gated ${st.gated}, no heading ${st.noHeading}, no position ${st.noPosition}"

    /** Older than this, the input counts as missing in the status line. */
    const val STATUS_STEER_STALE_S = 10f
    const val STATUS_YAW_STALE_S = 3f
    const val STATUS_POSITION_STALE_S = 5f

    /**
     * Map status while the layer is on and draws nothing (field test, Nov 13): the first
     * missing input, then the report counts; null while shading is on screen.
     */
    fun statusLine(i: BtrxBearingStatusInput): String? {
        if (i.drawing) return null
        fun stale(age: Float?, limit: Float) = age == null || age > limit
        val reason = when {
            stale(i.steerAgeS, STATUS_STEER_STALE_S) -> "Bearing layer: no reports from the paired Drone Pod"
            i.designator == null && i.liveStreams == 0 ->
                "Bearing layer: no drone video. The heading comes from the live stream of the drone carrying the Drone Pod"
            i.designator == null -> "Bearing layer: on the stream of the drone carrying the Drone Pod, tap \"Drone Pod is on this drone\""
            stale(i.yawAgeS, STATUS_YAW_STALE_S) -> "Bearing layer: no heading from ${i.designator} video (DJI SEI)"
            stale(i.positionAgeS, STATUS_POSITION_STALE_S) -> "Bearing layer: no Remote ID position for ${i.designator}"
            i.allHidden -> "Bearing layer: every source with shading is hidden"
            (i.stats?.used ?: 0) == 0 -> "Bearing layer: no report placed yet"
            else -> "Bearing layer: shading still too faint to show"
        }
        val st = i.stats ?: return reason
        if (st.reportsIn == 0) return reason
        return reason + "\nReports ${st.reportsIn}: placed ${st.used}, no heading ${st.noHeading}, " +
            "no position ${st.noPosition}, late ${st.lateDropped}, gated ${st.gated}"
    }

    fun clockLine(st: BtrxBearingStats): String = when (st.offsetSource) {
        2 -> "Pod clock: TIME_PONG, ±${st.podOffsetErrMs} ms"
        1 -> "Pod clock: arrival minimum (no TIME_PONG yet)"
        else -> "Pod clock: not yet known"
    }
}

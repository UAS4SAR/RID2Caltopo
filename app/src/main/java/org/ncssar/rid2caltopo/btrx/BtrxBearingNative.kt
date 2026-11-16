package org.ncssar.rid2caltopo.btrx

import java.nio.ByteBuffer

/**
 * The calls [BtrxBearingLayer] makes into the shared C library `btrx_bearing`
 * (docs/btrx/BEARING-LAYER-spec.md section 7). [BtrxBearingNative] is the JNI binding;
 * JVM tests pass a fake. Handles are opaque; 0 = none. Status codes are the library's
 * (`btrx_bearing_status_t`: 0 ok, 1 accepted with a ring drop, negative errors).
 */
interface BtrxBearingEngine {
    val available: Boolean
    fun create(incidentKey: ByteArray, fadeTotalS: Float, fadeShapeP: Float, lagTrace: Boolean): Long
    fun destroy(handle: Long)
    fun pushPose(handle: Long, tUs: Long, pushUs: Long, mask: Int, lat: Double, lon: Double, aglM: Float, yawTrueDeg: Float): Int
    fun pushSteer(handle: Long, payload: ByteArray, arrivalUs: Long, pushUs: Long, backlog: Boolean, logInput: Boolean): Int
    fun pushTimeSync(handle: Long, t1HostUs: Long, t2PodMs: Long, t3PodMs: Long, t4HostUs: Long): Int
    /** [out]: role, uptime_s, lost_known (0/1), phone_lost. */
    fun pushHealth(handle: Long, hostUs: Long, payload: ByteArray, out: LongArray): Int
    fun noteConnected(handle: Long, hostUs: Long): Int
    fun noteRendered(handle: Long, seq: Long, hostUs: Long): Int
    fun setSourceFlags(handle: Long, keys: LongArray, mask: Int, value: Int): Int
    fun setFade(handle: Long, totalS: Float, shapeP: Float): Int
    /** Pod build 0x0106+: src_id is a stable hash of the address (btrx_bearing_set_stable_src_ids). */
    fun setStableSrcIds(handle: Long, stable: Boolean): Int
    fun reset(handle: Long, what: Int, key: Long): Int
    /** 1 = a snapshot was published, 0 = not, negative = error. */
    fun tick(handle: Long, nowUs: Long): Int
    /** Source count, or a negative status (-6 before the first publish). Layout: [BtrxBearingNative] META_*. */
    fun copySnapshot(handle: Long, rgba: ByteBuffer, keys: LongArray, shortIds: ByteArray, meta: DoubleArray): Int
    /**
     * The latest stats into [out] (size [BtrxBearingNative.STATS_SIZE], the layout of meta at
     * META_STATS), refreshed by tick about once a second even before the first snapshot.
     * 0, or -6 before the first tick.
     */
    fun copyStats(handle: Long, out: DoubleArray): Int
    /** Indexes into the last copied snapshot's sources, by contribution; returns the count. */
    fun sourcesAt(handle: Long, lat: Double, lon: Double, radiusM: Float, out: IntArray): Int
    fun halfStrengthS(totalS: Float, shapeP: Float): Float
    fun drainLog(handle: Long): String?
    fun logInput(handle: Long, line: String)
}

object BtrxBearingNative : BtrxBearingEngine {
    const val MAX_SOURCES = 8
    const val META_STATS = 16
    const val META_SOURCES = 116
    const val META_PER_SOURCE = 40
    const val META_SIZE = META_SOURCES + META_PER_SOURCE * MAX_SOURCES
    const val STATS_SIZE = META_SOURCES - META_STATS
    const val STAGE_COUNT = 10

    const val POSE_POSITION = 1
    const val POSE_AGL = 2
    const val POSE_YAW = 4

    const val SRC_IGNORED = 1
    const val SRC_MUTED = 2

    const val RESET_ALL = 0

    const val E_NO_SNAPSHOT = -6

    /** Loaded on first use (the layer switch or the fade readout), not at app start. */
    override val available: Boolean by lazy {
        runCatching { System.loadLibrary("btrx_bearing") }.isSuccess &&
            runCatching { nativeAbiVersion() == 1 }.getOrDefault(false)
    }

    override fun create(incidentKey: ByteArray, fadeTotalS: Float, fadeShapeP: Float, lagTrace: Boolean): Long =
        if (available) nativeCreate(incidentKey, fadeTotalS, fadeShapeP, lagTrace) else 0L
    override fun destroy(handle: Long) = nativeDestroy(handle)
    override fun pushPose(handle: Long, tUs: Long, pushUs: Long, mask: Int, lat: Double, lon: Double, aglM: Float, yawTrueDeg: Float) =
        nativePushPose(handle, tUs, pushUs, mask, lat, lon, aglM, yawTrueDeg)
    override fun pushSteer(handle: Long, payload: ByteArray, arrivalUs: Long, pushUs: Long, backlog: Boolean, logInput: Boolean) =
        nativePushSteer(handle, payload, arrivalUs, pushUs, backlog, logInput)
    override fun pushTimeSync(handle: Long, t1HostUs: Long, t2PodMs: Long, t3PodMs: Long, t4HostUs: Long) =
        nativePushTimeSync(handle, t1HostUs, t2PodMs, t3PodMs, t4HostUs)
    override fun pushHealth(handle: Long, hostUs: Long, payload: ByteArray, out: LongArray) = nativePushHealth(handle, hostUs, payload, out)
    override fun noteConnected(handle: Long, hostUs: Long) = nativeNoteConnected(handle, hostUs)
    override fun noteRendered(handle: Long, seq: Long, hostUs: Long) = nativeNoteRendered(handle, seq, hostUs)
    override fun setSourceFlags(handle: Long, keys: LongArray, mask: Int, value: Int) = nativeSetSourceFlags(handle, keys, mask, value)
    override fun setFade(handle: Long, totalS: Float, shapeP: Float) = nativeSetFade(handle, totalS, shapeP)
    override fun setStableSrcIds(handle: Long, stable: Boolean) = nativeSetStableSrcIds(handle, stable)
    override fun reset(handle: Long, what: Int, key: Long) = nativeReset(handle, what, key)
    override fun tick(handle: Long, nowUs: Long) = nativeTick(handle, nowUs)
    override fun copySnapshot(handle: Long, rgba: ByteBuffer, keys: LongArray, shortIds: ByteArray, meta: DoubleArray) =
        nativeCopySnapshot(handle, rgba, keys, shortIds, meta)
    override fun copyStats(handle: Long, out: DoubleArray) = nativeCopyStats(handle, out)
    override fun sourcesAt(handle: Long, lat: Double, lon: Double, radiusM: Float, out: IntArray) = nativeSourcesAt(handle, lat, lon, radiusM, out)
    override fun halfStrengthS(totalS: Float, shapeP: Float): Float =
        if (available) nativeHalfStrengthS(totalS, shapeP) else BtrxBearingSettings.halfStrengthFallbackS(totalS, shapeP)
    override fun drainLog(handle: Long): String? = nativeDrainLog(handle)
    override fun logInput(handle: Long, line: String) = nativeLogInput(handle, line)

    private external fun nativeAbiVersion(): Int
    private external fun nativeCreate(key: ByteArray, fadeTotalS: Float, fadeShapeP: Float, lagTrace: Boolean): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativePushPose(handle: Long, tUs: Long, pushUs: Long, mask: Int, lat: Double, lon: Double, agl: Float, yaw: Float): Int
    private external fun nativePushSteer(handle: Long, payload: ByteArray, arrivalUs: Long, pushUs: Long, backlog: Boolean, logInput: Boolean): Int
    private external fun nativePushTimeSync(handle: Long, t1: Long, t2: Long, t3: Long, t4: Long): Int
    private external fun nativePushHealth(handle: Long, hostUs: Long, payload: ByteArray, out: LongArray): Int
    private external fun nativeNoteConnected(handle: Long, hostUs: Long): Int
    private external fun nativeNoteRendered(handle: Long, seq: Long, hostUs: Long): Int
    private external fun nativeSetSourceFlags(handle: Long, keys: LongArray, mask: Int, value: Int): Int
    private external fun nativeSetFade(handle: Long, totalS: Float, shapeP: Float): Int
    private external fun nativeSetStableSrcIds(handle: Long, stable: Boolean): Int
    private external fun nativeReset(handle: Long, what: Int, key: Long): Int
    private external fun nativeTick(handle: Long, nowUs: Long): Int
    private external fun nativeCopySnapshot(handle: Long, rgba: ByteBuffer, keys: LongArray, shortIds: ByteArray, meta: DoubleArray): Int
    private external fun nativeCopyStats(handle: Long, out: DoubleArray): Int
    private external fun nativeSourcesAt(handle: Long, lat: Double, lon: Double, radiusM: Float, out: IntArray): Int
    private external fun nativeHalfStrengthS(totalS: Float, shapeP: Float): Float
    private external fun nativeDrainLog(handle: Long): String?
    private external fun nativeLogInput(handle: Long, line: String)
}

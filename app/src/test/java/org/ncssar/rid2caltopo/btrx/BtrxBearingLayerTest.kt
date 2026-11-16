package org.ncssar.rid2caltopo.btrx

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/** The Android boundary of the bearing layer (spec 7, 9.4) against a fake native layer. */
class BtrxBearingLayerTest {
    private class FakeEngine(override val available: Boolean = true) : BtrxBearingEngine {
        val calls = mutableListOf<String>()
        var created = 0
        var destroyed = 0
        var publish = false
        var podSession = 1.0
        var sourcesAtResult = intArrayOf()
        val poses = mutableListOf<LongArray>()
        val poseMasks = mutableListOf<Int>()
        val steers = mutableListOf<Pair<Long, Boolean>>()
        val syncs = mutableListOf<List<Long>>()
        val flags = mutableListOf<Triple<List<Long>, Int, Int>>()
        override fun create(incidentKey: ByteArray, fadeTotalS: Float, fadeShapeP: Float, lagTrace: Boolean): Long {
            calls += "create $fadeTotalS $fadeShapeP $lagTrace ${incidentKey.size}"; created++; return 42L
        }
        override fun destroy(handle: Long) { destroyed++ }
        override fun pushPose(handle: Long, tUs: Long, pushUs: Long, mask: Int, lat: Double, lon: Double, aglM: Float, yawTrueDeg: Float): Int {
            poses += longArrayOf(tUs, pushUs); poseMasks += mask; return 0
        }
        override fun pushSteer(handle: Long, payload: ByteArray, arrivalUs: Long, pushUs: Long, backlog: Boolean, logInput: Boolean): Int {
            steers += arrivalUs to backlog; return 0
        }
        override fun pushTimeSync(handle: Long, t1HostUs: Long, t2PodMs: Long, t3PodMs: Long, t4HostUs: Long): Int {
            syncs += listOf(t1HostUs, t2PodMs, t3PodMs, t4HostUs); return 0
        }
        override fun pushHealth(handle: Long, hostUs: Long, payload: ByteArray, out: LongArray): Int {
            calls += "health ${payload.size}"; out[0] = 3; out[1] = 10; out[2] = if (payload.size >= 34) 1 else 0; out[3] = 7; return 0
        }
        override fun noteConnected(handle: Long, hostUs: Long): Int { calls += "connected"; return 0 }
        override fun noteRendered(handle: Long, seq: Long, hostUs: Long): Int { calls += "rendered $seq"; return 0 }
        override fun setSourceFlags(handle: Long, keys: LongArray, mask: Int, value: Int): Int { flags += Triple(keys.toList(), mask, value); return 0 }
        override fun setFade(handle: Long, totalS: Float, shapeP: Float): Int { calls += "fade $totalS $shapeP"; return 0 }
        override fun setStableSrcIds(handle: Long, stable: Boolean): Int { calls += "stable $stable"; return 0 }
        override fun reset(handle: Long, what: Int, key: Long): Int = 0
        override fun setFocus(handle: Long, key: Long): Int { calls += "focus $key"; return 0 }
        override fun tick(handle: Long, nowUs: Long): Int = if (publish) 1.also { publish = false } else 0
        var withFine = false
        override fun copySnapshot(handle: Long, rgba: ByteBuffer, fine: ByteBuffer?, trail: DoubleArray?, keys: LongArray, shortIds: ByteArray, meta: DoubleArray): Int {
            meta.fill(0.0)
            meta[BtrxBearingNative.META_FOCUS_INDEX] = -1.0
            if (withFine) {
                meta[BtrxBearingNative.META_FOCUS_INDEX] = 0.0
                meta[BtrxBearingNative.META_FINE_VALID] = 1.0; meta[BtrxBearingNative.META_FINE_CELLS] = 160.0
                meta[12] = 37.002; meta[13] = 36.998; meta[14] = -121.998; meta[15] = -122.002
                fine!!.put(0, 7); fine.put(3, 200.toByte())
                meta[BtrxBearingNative.META_TRAIL_COUNT] = 2.0
                trail!![0] = 37.0; trail[1] = -122.0; trail[2] = -71.0; trail[3] = 37.0001; trail[4] = -122.0; trail[5] = -64.0
            }
            meta[0] = 5.0; meta[1] = 1e9; meta[2] = 2.0; meta[3] = 2.0
            meta[4] = 37.01; meta[5] = 37.0; meta[6] = -121.99; meta[7] = -122.0; meta[8] = 2.0
            val q = BtrxBearingNative.META_STATS
            meta[q + 0] = 12.0; meta[q + 2] = 9.0; meta[q + 14] = 1.0; meta[q + 15] = 40.0; meta[q + 16] = 3.0
            meta[q + 24] = 2.0; meta[q + 25] = podSession
            meta[q + 27 + 6 * 7] = 4.0; meta[q + 27 + 6 * 7 + 1] = 800.0
            for (i in 0 until 2) {
                keys[i] = 100L + i
                "ID000$i".toByteArray().copyInto(shortIds, 8 * i)
                val o = BtrxBearingNative.META_SOURCES + BtrxBearingNative.META_PER_SOURCE * i
                meta[o + 1] = 1.0; meta[o + 2] = 230.0; meta[o + 3] = 159.0; meta[o + 4] = 0.0
                meta[o + 5] = if (i == 1) BtrxBearingNative.SRC_MUTED.toDouble() else 0.0
                meta[o + 10] = 23.0
                if (i == 0) meta[o + 12] = 0.5
                meta[o + 31] = if (i == 0) 0xF1A3.toDouble() else 7.0
                meta[o + 32] = if (i == 1) 1.0 else 0.0
                if (i == 0) { meta[o + 13] = 1.0; meta[o + 15] = 37.005; meta[o + 16] = -121.995; meta[o + 17] = 9f.toDouble() }
                meta[o + 35] = if (i == 0) -64.0 else -128.0; meta[o + 36] = if (i == 0) -58.0 else -128.0
                if (i == 0) {
                    meta[o + 23] = 1.0; meta[o + 28] = 120.0; meta[o + 29] = Double.NaN; meta[o + 33] = 1.0; meta[o + 34] = 31.5
                    meta[o + 37] = 1.0; meta[o + 38] = 37.0001; meta[o + 39] = -122.0
                    meta[o + 41] = 1.0; meta[o + 42] = 37.004; meta[o + 43] = -121.996
                    meta[o + 44] = 1.0
                }
                if (i == 1) meta[o + 5] = (BtrxBearingNative.SRC_MUTED or BtrxBearingNative.SRC_OWN_SUSPECT).toDouble()
            }
            return 2
        }
        var stats: DoubleArray? = null
        override fun copyStats(handle: Long, out: DoubleArray): Int {
            val st = stats ?: return BtrxBearingNative.E_NO_SNAPSHOT
            st.copyInto(out); return 0
        }
        override fun sourcesAt(handle: Long, lat: Double, lon: Double, radiusM: Float, out: IntArray): Int {
            sourcesAtResult.copyInto(out); return sourcesAtResult.size
        }
        override fun halfStrengthS(totalS: Float, shapeP: Float): Float = BtrxBearingSettings.halfStrengthFallbackS(totalS, shapeP)
        override fun drainLog(handle: Long): String? = null
        override fun logInput(handle: Long, line: String) {}
    }

    private var now = 1_000_000_000L
    private var wall = 1_700_000_000_000L
    private val pings = mutableListOf<Pair<Int, Long>>()

    private fun layer(engine: FakeEngine) = BtrxBearingLayer(engine, monoUs = { now }, wallMs = { wall })

    private fun tickAt(l: BtrxBearingLayer, us: Long, linkOk: Boolean = true) {
        now = us
        l.tick { seq, t1 -> if (linkOk) { pings += seq to t1; true } else false }
    }

    private fun meta(backlog: Boolean = false, arrivalMs: Long = wall) = BtrxClient.RadioMeta(7, 1, -60, 5f, arrivalMs, backlog)
    private fun frame(type: Int, payload: ByteArray) = P.Frame(1, type, 1, 0, 0, payload)

    @Test fun startFailsWithoutLibraryAndUsesSettings() {
        val off = layer(FakeEngine(available = false))
        assertFalse(off.start(BtrxBearingSettings(), trace = false, linkUp = true))
        assertFalse(off.isRunning)
        val e = FakeEngine()
        val l = layer(e)
        assertTrue(l.start(BtrxBearingSettings(1200f, 2f), trace = true, linkUp = true))
        assertEquals("create 1200.0 2.0 true 16", e.calls[0])
        assertTrue("connected" in e.calls)
        l.stop()
        assertEquals(1, e.destroyed)
        assertNull(l.snapshot.value)
    }

    @Test fun settingsAreClamped() {
        val e = FakeEngine()
        val l = layer(e)
        assertTrue(l.start(BtrxBearingSettings(5f, 9f), false, false))
        assertEquals("create 60.0 4.0 false 16", e.calls[0])
        l.setFade(BtrxBearingSettings(99999f, 0f))
        assertTrue("fade 1800.0 1.0" in e.calls)
    }

    @Test fun timePingBurstThenEvery30s() {
        val l = layer(FakeEngine())
        l.start(BtrxBearingSettings(), false, true)
        val t0 = now
        var t = t0
        while (t <= t0 + 65_000_000L) { tickAt(l, t); t += 200_000L }
        // Burst of 3 one second apart, then every 30 s.
        val times = pings.map { it.second }
        assertEquals(5, pings.size)
        assertEquals(listOf(0, 1, 2, 3, 4), pings.map { it.first })
        assertEquals(1000L, times[1] - times[0])
        assertEquals(1000L, times[2] - times[1])
        assertEquals(30_000L, times[3] - times[2])
        assertEquals(30_000L, times[4] - times[3])
        // Reconnect: a new burst at once.
        l.onConnected()
        tickAt(l, now + 200_000L)
        tickAt(l, now + 1_000_000L)
        assertEquals(7, pings.size)
    }

    @Test fun pongMatchesItsPing() {
        val e = FakeEngine()
        val l = layer(e)
        l.start(BtrxBearingSettings(), false, true)
        tickAt(l, now)
        val (seq, t1) = pings.single()
        val t1Us = now
        now += 80_000L
        val pong = ByteArray(16).also { it[0] = seq.toByte(); P.putU32(it, 2, t1); P.putU32(it, 6, 5000); P.putU32(it, 10, 5001) }
        l.onFrame(frame(P.TIME_PONG, pong), meta(), fromPairedPod = true, fromDeskPod = false)
        assertEquals(listOf(t1Us, 5000L, 5001L, now), e.syncs.single())
        // The same pong again (or a wrong t1) is ignored.
        l.onFrame(frame(P.TIME_PONG, pong), meta(), true, false)
        val bad = pong.copyOf().also { P.putU32(it, 2, t1 + 1) }
        l.onFrame(frame(P.TIME_PONG, bad), meta(), true, false)
        assertEquals(1, e.syncs.size)
    }

    @Test fun steerAndHealthRouting() {
        val e = FakeEngine()
        val l = layer(e)
        l.start(BtrxBearingSettings(), false, true)
        wall += 0
        l.onFrame(frame(P.STEER, ByteArray(27)), meta(backlog = true, arrivalMs = wall - 30), fromPairedPod = true, fromDeskPod = false)
        assertEquals(now - 30_000L to true, e.steers.single())
        l.onFrame(frame(P.STEER, ByteArray(27)), meta(), fromPairedPod = false, fromDeskPod = true)
        assertEquals(1, e.steers.size)
        l.onFrame(frame(P.HEALTH, ByteArray(34)), meta(), fromPairedPod = false, fromDeskPod = true)
        assertTrue("health 34" in e.calls)
        assertFalse(e.calls.any { it.startsWith("stable") })
    }

    /** The paired Drone Pod's HEALTH build (offset 4) switches the layer to stable src ids at 0x0106. */
    @Test fun stableSrcIdsFollowThePairedPodBuild() {
        val e = FakeEngine()
        val l = layer(e)
        l.start(BtrxBearingSettings(), false, true)
        fun health(build: Int, size: Int = 44) = ByteArray(size).also { it[4] = build.toByte(); it[5] = (build ushr 8).toByte() }
        l.onFrame(frame(P.HEALTH, health(0x0105, 34)), meta(), fromPairedPod = true, fromDeskPod = false)
        assertFalse(l.stableSrcIds)
        assertFalse(e.calls.any { it.startsWith("stable") })
        l.onFrame(frame(P.HEALTH, health(0x0106, 44)), meta(), fromPairedPod = false, fromDeskPod = true)
        assertFalse("the Desk Pod's build does not decide", l.stableSrcIds)
        l.onFrame(frame(P.HEALTH, health(0x0106, 44)), meta(), fromPairedPod = true, fromDeskPod = false)
        assertTrue(l.stableSrcIds)
        l.onFrame(frame(P.HEALTH, health(0x0107, 44)), meta(), fromPairedPod = true, fromDeskPod = false)
        assertEquals(listOf("stable true"), e.calls.filter { it.startsWith("stable") })
        l.onFrame(frame(P.HEALTH, health(0x0105, 34)), meta(), fromPairedPod = true, fromDeskPod = false)
        assertFalse("an older pod paired later goes back", l.stableSrcIds)
        assertEquals(listOf("stable true", "stable false"), e.calls.filter { it.startsWith("stable") })
    }

    @Test fun poseOnlyFromTheDronePodStream() {
        val e = FakeEngine()
        val l = layer(e)
        l.start(BtrxBearingSettings(), false, true)
        l.onYaw("R2C-1", 10.0, wall)
        assertEquals(0, e.poses.size)
        l.steeringDesignator = "r2c-1"
        l.onYaw("R2C-1", 10.0, wall - 40)
        assertEquals(BtrxBearingNative.POSE_YAW, e.poseMasks.last())
        assertEquals(now - 40_000L, e.poses.last()[0])
        l.onYaw("R2C-2", 10.0, wall)
        assertEquals(1, e.poses.size)
        l.onPosition("R2C-1", 37.0, -122.0, wall - 900)
        assertEquals(BtrxBearingNative.POSE_POSITION, e.poseMasks.last())
        assertEquals(now - 900_000L, e.poses.last()[0])
        l.noteAgl("R2C-1", 45.0)
        l.onPosition("R2C-1", 37.0, -122.0, wall + 5000) // drone clock ahead: arrival time
        assertEquals(BtrxBearingNative.POSE_POSITION or BtrxBearingNative.POSE_AGL, e.poseMasks.last())
        assertEquals(now, e.poses.last()[0])
        l.onPosition("R2C-1", 0.0, 0.0, wall)
        assertEquals(3, e.poses.size)
    }

    @Test fun snapshotDecodeTapAndMute() {
        val e = FakeEngine()
        val l = layer(e)
        l.start(BtrxBearingSettings(), false, true)
        e.publish = true
        tickAt(l, now + 200_000L)
        val s = l.snapshot.value!!
        assertEquals(5L, s.seq)
        assertEquals(2, s.sources.size)
        assertEquals("ID0000", s.sources[0].shortId)
        assertEquals(0xE69F00, s.sources[0].rgb)
        assertFalse(s.sources[0].muted)
        assertTrue(s.sources[1].muted)
        assertEquals(0xF1A3, s.sources[0].srcId)
        assertFalse(s.sources[0].srcKeyed)
        assertEquals(7, s.sources[1].srcId)
        assertTrue(s.sources[1].srcKeyed)
        assertEquals(18f, s.sources[0].estimate!!.plusMinus2sM, 1e-3f)
        assertNull(s.sources[1].estimate)
        assertEquals("phone · ID0000 · 23 rpt · ±18 m", BtrxBearingText.label(s.sources[0]))
        assertEquals("Reports lost while no app: 3 since connect (40 since the Desk Pod started)", BtrxBearingText.lostLine(s.stats))
        assertEquals(800, s.stats.lag[7].p50Ms)
        e.sourcesAtResult = intArrayOf(1, 0)
        assertEquals(listOf(101L, 100L), l.sourcesAt(37.005, -121.995, 50f).map { it.key })
        l.setMuted(listOf(100L, 101L), true)
        assertEquals(Triple(listOf(100L, 101L), BtrxBearingNative.SRC_MUTED, BtrxBearingNative.SRC_MUTED), e.flags.last())
        l.setMuted(listOf(100L), false)
        assertEquals(0, e.flags.last().third)
        l.noteRendered(5L); l.noteRendered(5L)
        assertEquals(1, e.calls.count { it == "rendered 5" })
    }

    @Test fun snapshotDecodesWatchTestFields() {
        val e = FakeEngine()
        val l = layer(e)
        l.start(BtrxBearingSettings(), false, true)
        e.publish = true
        tickAt(l, now + 200_000L)
        var s = l.snapshot.value!!
        assertEquals(0L, s.focusKey)
        assertNull(s.fine)
        assertTrue(s.trail.isEmpty())
        val a = s.sources[0]
        assertTrue(a.pinned)
        assertEquals(-64, a.lastRssiDbm)
        assertEquals(-58, a.maxRssiDbm)
        assertEquals(37.0001, a.strongestLat!!, 1e-9)
        assertEquals(37.004, a.labelLat!!, 1e-9)
        assertTrue(a.latestRay!!.ambiguous)
        assertEquals(31.5f, a.latestRay!!.rangeM!!, 1e-3f)
        assertEquals(120f, a.latestRay!!.lengthM, 1e-3f)
        assertFalse(a.ownSuspect)
        val b = s.sources[1]
        assertTrue(b.ownSuspect)
        assertNull(b.lastRssiDbm)
        assertNull(b.strongestLat)
        assertFalse(b.pinned)
        l.setFocus(100L)
        assertEquals("focus 100", e.calls.last())
        e.withFine = true
        e.publish = true
        tickAt(l, now + 400_000L)
        s = l.snapshot.value!!
        assertEquals(100L, s.focusKey)
        assertEquals(160, s.fine!!.cells)
        assertEquals(37.002, s.fine!!.north, 1e-9)
        assertEquals(7.toByte(), s.fine!!.rgba[0])
        assertEquals(160 * 160 * 4, s.fine!!.rgba.size)
        assertEquals(listOf(-71, -64), s.trail.map { it.rssiDbm })
        l.clearOwnSuspect(101L)
        assertEquals(Triple(listOf(101L), BtrxBearingNative.SRC_OWN_CLEARED or BtrxBearingNative.SRC_OWN_SUSPECT,
            BtrxBearingNative.SRC_OWN_CLEARED), e.flags.last())
    }

    @Test fun podRestartStartsABurst() {
        val e = FakeEngine()
        val l = layer(e)
        l.start(BtrxBearingSettings(), false, true)
        var t = now
        repeat(20) { tickAt(l, t); t += 200_000L }      // burst done
        assertEquals(3, pings.size)
        e.publish = true; tickAt(l, t); t += 200_000L   // session 1 seen
        e.podSession = 2.0
        e.publish = true; tickAt(l, t); t += 200_000L   // session 2: burst
        tickAt(l, t)
        assertEquals(4, pings.size)
    }

    @Test fun pingWithoutLinkKeepsSchedule() {
        val l = layer(FakeEngine())
        l.start(BtrxBearingSettings(), false, false)
        tickAt(l, now, linkOk = false)
        assertEquals(0, pings.size)
        assertEquals(now + BtrxBearingLayer.PING_BURST_SPACING_US, l.nextPingAtUs)
    }

    @Test fun fadeReadout() {
        assertEquals(300f, BtrxBearingSettings.halfStrengthFallbackS(600f, 1f), 1e-3f)
        assertEquals("7:04", BtrxBearingSettings.minSec(BtrxBearingSettings.halfStrengthFallbackS(600f, 2f)))
        assertEquals("Linear", BtrxBearingSettings.shapeLabel(1f))
        assertEquals("Accelerating", BtrxBearingSettings.shapeLabel(4f))
        assertEquals("half strength at 5:00", BtrxBearingText.halfStrength(300f))
        assertEquals("12 s ago", BtrxBearingText.lastHeard(13_000_000L, 1_000_000L))
    }

    // ---- field test Nov 13: status line, pose stream ----

    private fun statsArray(reportsIn: Int, used: Int, noHeading: Int, noPosition: Int) =
        DoubleArray(BtrxBearingNative.STATS_SIZE).also { it[0] = reportsIn.toDouble(); it[2] = used.toDouble(); it[4] = noHeading.toDouble(); it[5] = noPosition.toDouble(); it[24] = 1.0 }

    @Test fun statusExplainsMissingPoseBeforeAnySnapshot() {
        val e = FakeEngine()
        val l = layer(e)
        l.start(BtrxBearingSettings(), false, true)
        e.stats = statsArray(12, 0, 7, 5)
        l.steeringDesignator = null
        l.liveStreamCount = 1
        l.onFrame(frame(P.STEER, ByteArray(28)), meta(), fromPairedPod = true, fromDeskPod = false)
        tickAt(l, now + 200_000L)
        val st = l.status.value!!
        assertEquals(12, st.stats!!.reportsIn)
        assertTrue(st.line!!, st.line!!.startsWith("Bearing layer: on the stream of the drone carrying the Drone Pod"))
        assertTrue(st.line!!.endsWith("Reports 12: placed 0, no heading 7, no position 5, late 0, gated 0"))
        // A stream is now the pose stream, but no SEI yaw has come in.
        l.steeringDesignator = "1sar7djmtrc4td"
        tickAt(l, now + 1_100_000L)
        assertTrue(l.status.value!!.line!!.startsWith("Bearing layer: no heading from 1sar7djmtrc4td video (DJI SEI)"))
        // Yaw arrives (case-insensitive designator), then position: the next reason is "no report placed yet".
        l.onYaw("1SAR7DjMtrc4td", 90.0, wall)
        tickAt(l, now + 1_100_000L)
        assertTrue(l.status.value!!.line!!.startsWith("Bearing layer: no Remote ID position for 1sar7djmtrc4td"))
        l.onPosition("1sar7DjMtrc4td", 39.153047, -121.132901, wall)
        tickAt(l, now + 1_100_000L)
        assertTrue(l.status.value!!.line!!.startsWith("Bearing layer: no report placed yet"))
        // Shading on screen: no status line.
        e.publish = true
        tickAt(l, now + 1_100_000L)
        assertNull(l.status.value!!.line)
        l.stop()
        assertNull(l.status.value)
    }

    @Test fun statusReasonsInOrder() {
        fun line(steer: Float? = 1f, d: String? = "A", live: Int = 1, yaw: Float? = 0.1f, pos: Float? = 0.5f, drawing: Boolean = false, hidden: Boolean = false) =
            BtrxBearingText.statusLine(BtrxBearingStatusInput(null, d, live, steer, yaw, pos, drawing, hidden))
        assertNull(line(drawing = true))
        assertEquals("Bearing layer: no reports from the paired Drone Pod", line(steer = null))
        assertEquals("Bearing layer: no reports from the paired Drone Pod", line(steer = 11f))
        assertTrue(line(d = null, live = 0)!!.startsWith("Bearing layer: no drone video."))
        assertEquals("Bearing layer: no heading from A video (DJI SEI)", line(yaw = 3.5f))
        assertEquals("Bearing layer: no Remote ID position for A", line(pos = null))
        assertEquals("Bearing layer: every source with shading is hidden", line(hidden = true))
        assertEquals("Bearing layer: no report placed yet", line())
    }

    @Test fun effectivePoseStream() {
        assertEquals("A", BtrxSteeringTarget.effective(null, setOf("A")))
        assertNull(BtrxSteeringTarget.effective(null, setOf("A", "B")))
        assertEquals("B", BtrxSteeringTarget.effective("B", setOf("A", "B")))
        assertEquals("A", BtrxSteeringTarget.effective("C", setOf("A")))
        assertNull(BtrxSteeringTarget.effective("C", emptySet()))
    }
}

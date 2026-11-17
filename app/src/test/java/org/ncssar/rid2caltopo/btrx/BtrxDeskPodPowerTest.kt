package org.ncssar.rid2caltopo.btrx

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkKind
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkPhase
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkStatus
import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.DeviceMetrics
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.FromRadio
import org.ncssar.rid2caltopo.btrx.MeshtasticSerial.ProtoWriter

/** Desk Pod power diagnostics (Ken, Nov 17): went silent, restarted, DeviceMetrics, sent-frame log. */
class BtrxDeskPodPowerTest {

    // ---- classification: the Nov 16 training reasons ----

    @Test
    fun trainingDropsAreSilent() {
        listOf(
            "a ToRadio write failed (status 133)", "a FromRadio read failed (status 133)", "disconnected (status 8)",
            "a Bluetooth operation timed out", "connection timed out", "connection failed (status 147)",
            // iPad texts
            "no connection after 20 s", "a FromRadio read got no answer in 10 s", "disconnected",
        ).forEach { assertTrue(it, BtrxDeskPodPower.isSilentDrop(it)) }
    }

    @Test
    fun deliberateOrSetupEndsAreNotSilent() {
        listOf(
            "background connect replaced by a direct connect", "USB debug link turned off", "USB detached",
            "Android refused a Bluetooth operation", "not a Desk Pod (role=1)",
            "the device does not offer the Meshtastic phone API (status 0)",
            "connection failed: not bonded (status 5). Enter the PIN", "disconnected (status 19)", "Bluetooth state 4", "first",
        ).forEach { assertFalse(it, BtrxDeskPodPower.isSilentDrop(it)) }
    }

    // ---- silent ----

    @Test
    fun silentUntilTheNextSession() {
        val p = BtrxDeskPodPower()
        assertEquals("Desk Pod went silent — check its power (a FromRadio read failed (status 133))",
            p.onDrop("a FromRadio read failed (status 133)", 1_000))
        assertEquals(1_000L, p.silentSinceMs)
        p.onSessionStarted()
        assertNull(p.silentSinceMs)
        assertNull(p.onDrop("USB detached", 2_000))
        assertNull(p.silentSinceMs)
    }

    // ---- restart ----

    @Test
    fun lowUptimeAfterADropIsARestart() {
        val p = BtrxDeskPodPower()
        p.onDrop("a ToRadio write failed (status 133)", 1_000_000)
        // 16:39:47 drop -> 16:44:09.99 HEALTH uptime 16 s (the Nov 16 log)
        val line = p.onDeskHealth(16, 0, 1_000_000 + 262_990)
        assertEquals("Desk Pod restarted (power-on): HEALTH uptime_s=16, 262 s after the link dropped; " +
            "it came back about 246 s after the link dropped", line)
        assertEquals("Desk Pod restarted (power-on)", p.restartNotice(1_000_000 + 262_990))
        assertEquals("Desk Pod restarted (power-on)", p.restartNotice(1_000_000 + 262_990 + 60_000))
        assertNull(p.restartNotice(1_000_000 + 262_990 + 60_001))
        assertEquals(246_990L, p.restart!!.downMs)
        // Once per drop.
        assertNull(p.onDeskHealth(28, 0, 1_000_000 + 275_000))
    }

    @Test
    fun noRestartWithoutADropOrWithHighUptimeOrLateHealth() {
        val p = BtrxDeskPodPower()
        assertNull(p.onDeskHealth(12, 0, 5_000)) // app just started next to a fresh pod
        p.onDrop("disconnected (status 8)", 10_000)
        assertNull(p.onDeskHealth(60, 0, 20_000)) // uptime 60 s: not a restart
        assertNull(p.onDeskHealth(5, 0, 10_000 + BtrxDeskPodPower.RESTART_AFTER_DROP_MS + 1))
        assertNull(p.restart)
    }

    @Test
    fun restartTextNamesTheResetReason() {
        assertEquals("Desk Pod restarted (power-on)", BtrxDeskPodPower.restartText(0))
        assertEquals("Desk Pod restarted (watchdog)", BtrxDeskPodPower.restartText(2))
        assertEquals("Desk Pod restarted (soft-reset)", BtrxDeskPodPower.restartText(4))
        assertEquals("Desk Pod restarted", BtrxDeskPodPower.restartText(null))
    }

    @Test
    fun metricsLines() {
        assertEquals("Desk Pod power: external (battery_level 101), 5.02 V", BtrxDeskPodPower.metricsLine(DeviceMetrics(101, 5.02f, null)))
        assertEquals("Desk Pod battery: 87%, 3.95 V", BtrxDeskPodPower.metricsLine(DeviceMetrics(87, 3.95f, 10)))
        assertEquals("Desk Pod battery: 40%", BtrxDeskPodPower.metricsLine(DeviceMetrics(40, null, null)))
        assertEquals("Desk Pod voltage: 4.10 V", BtrxDeskPodPower.metricsLine(DeviceMetrics(null, 4.1f, null)))
        assertNull(BtrxDeskPodPower.metricsLine(DeviceMetrics(null, null, 5)))
    }

    // ---- map chip ----

    private fun text(link: LinkStatus, silent: Boolean = false, notice: String? = null, reporting: Boolean = true) =
        BtrxMapStatus.status(true, link, bluetoothOff = false, pausedForUsb = false, dronePodReporting = reporting,
            deskPodSilent = silent, deskPodNotice = notice)

    private fun ble(phase: LinkPhase, failed: Int = 0) = LinkStatus(LinkKind.BLUETOOTH, phase, failedAttempts = failed)

    @Test
    fun chipSaysTheDeskPodWentSilent() {
        val silent = "BTRX: Inactive - Desk Pod went silent — check its power"
        assertEquals(silent, text(ble(LinkPhase.RETRY_WAIT, 1), silent = true)!!.text)
        assertEquals(silent, text(ble(LinkPhase.SCANNING, 2), silent = true)!!.text)
        assertEquals(silent, text(ble(LinkPhase.CONNECTING), silent = true)!!.text)
        assertEquals("BTRX: Inactive - BT Down", text(ble(LinkPhase.RETRY_WAIT, 1))!!.text)
        // Answering again (configuring), Bluetooth off, link off and USB keep their own reasons.
        assertEquals("BTRX: Inactive - Connecting", text(ble(LinkPhase.CONFIGURING), silent = true)!!.text)
        assertEquals("BTRX: Inactive - Bluetooth off",
            BtrxMapStatus.status(true, ble(LinkPhase.RETRY_WAIT, 1), true, false, false, deskPodSilent = true)!!.text)
        assertEquals("BTRX: Inactive - Link off", text(LinkStatus(LinkKind.NONE, LinkPhase.OFF), silent = true)!!.text)
        assertEquals("BTRX: Inactive - Connecting", text(LinkStatus(LinkKind.USB, LinkPhase.CONFIGURING), silent = true)!!.text)
        assertFalse(text(ble(LinkPhase.RETRY_WAIT), silent = true)!!.active)
    }

    @Test
    fun chipWarnsOfARestartWhileActive() {
        val s = text(ble(LinkPhase.READY), notice = "Desk Pod restarted (power-on)")!!
        assertEquals("BTRX: Active - Desk Pod restarted (power-on)", s.text)
        assertTrue(s.active); assertTrue(s.warning)
        assertEquals("BTRX: Active", text(ble(LinkPhase.READY))!!.text)
    }

    // ---- the model ----

    private var now = 1_000_000L
    private val logs = mutableListOf<String>()
    private val logger = object : BtrxClient.Logger {
        override fun info(message: String) { logs += message }
        override fun warn(message: String) { logs += "W $message" }
    }
    private val desk = 0x66C842CAL

    private class FakeSender : BtrxFrameSender {
        override var isReady = true
        override fun nextSeq() = 1
        override fun sendFrame(to: Long, frame: ByteArray) {}
    }

    private fun deskHealth(model: BtrxDeskPodModel, uptime: Long, reset: Long = 0) {
        val h = ByteArray(P.HEALTH_EXT_LEN).also { P.putU32(it, 0, uptime); it[6] = 3; P.putU32(it, 34, reset) }
        model.onFrame(P.decodeFrame(P.encodeFrame(P.HEALTH, payload = h)), BtrxClient.RadioMeta(desk, desk, 0, 0f, now, false))
    }

    @Test
    fun modelReplaysTheTrainingDrop() {
        val model = BtrxDeskPodModel(logger, clock = { now })
        val first = FakeSender()
        model.attach(first); model.onConfigured(desk)
        deskHealth(model, 12)
        now += 22_800
        model.detach(first, "a ToRadio write failed (status 133)")
        var s = model.snapshot()
        assertTrue(s.deskSilent)
        assertTrue(logs.toString(), logs.contains("W Desk Pod went silent — check its power (a ToRadio write failed (status 133))"))
        assertTrue(BtrxDeskPodText.deskPowerLines(s).contains("Desk Pod went silent — check its power (link lost: a ToRadio write failed (status 133))"))
        // Ken powers it again; the next session's first HEALTH says uptime 16 s.
        now += 259_000
        val second = FakeSender()
        model.attach(second); model.onConfigured(desk)
        assertFalse(model.snapshot().deskSilent)
        deskHealth(model, 16)
        s = model.snapshot()
        assertEquals("Desk Pod restarted (power-on)", s.deskRestartNotice)
        assertTrue(logs.any { it.startsWith("W Desk Pod restarted (power-on): HEALTH uptime_s=16, 259 s after the link dropped") })
        assertEquals("Desk Pod restarted (power-on) 0 s ago (uptime 16 s; back about 243 s after the link dropped)",
            BtrxDeskPodText.deskPowerLines(s).single())
        now += 61_000
        assertNull(model.snapshot().deskRestartNotice)
        assertNotNull(model.snapshot().deskRestart)
    }

    @Test
    fun modelKeepsTheDeskPodsDeviceMetrics() {
        val model = BtrxDeskPodModel(logger, clock = { now })
        model.onDeskDeviceMetrics(DeviceMetrics(101, 5.01f, 30), "node_info")
        now += 5_000
        val s = model.snapshot()
        assertEquals(DeviceMetrics(101, 5.01f, 30), s.deskDeviceMetrics)
        assertEquals(listOf("Desk Pod power: external (battery_level 101), 5.01 V, 5 s ago"), BtrxDeskPodText.deskPowerLines(s))
    }

    // ---- phone API: standard DeviceMetrics ----

    private fun deviceMetrics(level: Int?, volts: Float?, uptime: Long?) = ProtoWriter().apply {
        level?.let { varint(1, it.toLong()) }
        volts?.let { float32(2, it) }
        varint(3, 7) // channel_utilization as a varint would be wrong; real nodes send a float (wire 5): skipped either way
        uptime?.let { varint(5, it) }
    }.bytes()

    private fun nodeInfo(num: Long, metrics: ByteArray?) = ProtoWriter().bytesField(4, ProtoWriter().varint(1, num)
        .bytesField(2, byteArrayOf(10, 2, 'h'.code.toByte(), 'i'.code.toByte())) // user, skipped
        .apply { metrics?.let { bytesField(6, it) } }.bytes()).bytes()

    @Test
    fun nodeInfoCarriesDeviceMetrics() {
        val m = MeshtasticSerial.parseFromRadio(nodeInfo(desk, deviceMetrics(101, 4.98f, 120)))
        assertEquals(FromRadio.NodeInfo(desk, DeviceMetrics(101, 4.98f, 120)), m)
        assertEquals(FromRadio.NodeInfo(desk, null), MeshtasticSerial.parseFromRadio(nodeInfo(desk, null)))
        assertEquals("battery_level=101 voltage=4.98 uptime_s=120", DeviceMetrics(101, 4.98f, 120).logText())
        assertEquals("battery_level=- voltage=- uptime_s=9", DeviceMetrics(null, null, 9).logText())
    }

    @Test
    fun telemetryDeviceMetricsOnly() {
        val tel = ProtoWriter().fixed32(1, 1234).bytesField(2, deviceMetrics(55, 3.8f, null)).bytes()
        assertEquals(DeviceMetrics(55, 3.8f, null), MeshtasticSerial.parseTelemetryDeviceMetrics(tel))
        val env = ProtoWriter().fixed32(1, 1234).bytesField(3, byteArrayOf(0x0d, 0, 0, 0, 0)).bytes()
        assertNull(MeshtasticSerial.parseTelemetryDeviceMetrics(env))
    }

    // ---- the client ----

    private val debugs = mutableListOf<String>()
    private val infos = mutableListOf<String>()
    private val metricsSeen = mutableListOf<Pair<DeviceMetrics, String>>()
    private val clientLog = object : BtrxClient.Logger {
        override fun info(message: String) { infos += message }
        override fun warn(message: String) { infos += message }
        override fun debug(message: String) { debugs += message }
    }
    private var clientNow = 50_000L
    private val sent = mutableListOf<ByteArray>()
    private val client = BtrxClient(
        send = { sent += it }, log = clientLog,
        listener = object : BtrxClient.Listener {
            override fun onDeskDeviceMetrics(metrics: DeviceMetrics, source: String) { metricsSeen += metrics to source }
        },
        random = Random(1), clock = { clientNow },
    )

    private fun feed(proto: ByteArray) = client.onBytes(MeshtasticSerial.frame(proto))

    private fun handshake() {
        client.start()
        val out = mutableListOf<ByteArray>()
        val d = MeshtasticSerial.StreamDecoder({ out += it })
        sent.forEach { d.feed(it) }
        val configId = MeshtasticSerial.ProtoReader(out.single()).let { r -> r.tag(); r.varint() }
        feed(ProtoWriter().bytesField(3, ProtoWriter().varint(1, desk).bytes()).bytes())
        feed(nodeInfo(0x11111111L, deviceMetrics(20, 3.6f, null))) // another node: not the Desk Pod
        feed(nodeInfo(desk, deviceMetrics(101, 5.0f, 40)))
        feed(ProtoWriter().varint(7, configId).bytes())
    }

    @Test
    fun clientPassesOnlyTheDeskPodsMetrics() {
        handshake()
        feed(MeshtasticSerialTest.fromRadioPacket(desk, 0xFFFFFFFFL, 0, 0f, MeshtasticSerial.TELEMETRY_APP_PORT,
            ProtoWriter().fixed32(1, 1).bytesField(2, deviceMetrics(100, 4.2f, 60)).bytes()))
        feed(MeshtasticSerialTest.fromRadioPacket(0x22222222L, 0xFFFFFFFFL, 0, 0f, MeshtasticSerial.TELEMETRY_APP_PORT,
            ProtoWriter().fixed32(1, 1).bytesField(2, deviceMetrics(10, 3.4f, 60)).bytes()))
        assertEquals(listOf(DeviceMetrics(101, 5.0f, 40) to "node_info", DeviceMetrics(100, 4.2f, 60) to "telemetry"), metricsSeen)
        assertTrue(debugs.contains("Desk Pod device metrics (node_info): battery_level=101 voltage=5.00 uptime_s=40"))
        assertTrue(debugs.contains("Desk Pod device metrics (telemetry): battery_level=100 voltage=4.20 uptime_s=60"))
    }

    @Test
    fun sentFramesAreLoggedAndTheKeepaliveIsRateLimited() {
        handshake()
        val keepalive = { P.confirmRspFrame(1, 0, P.CFM_KEEPALIVE) }
        repeat(5) { client.sendFrame(desk, keepalive()); clientNow += 5_000 }
        val ka = "tx to=!66c842ca ${BtrxFrameLog.format(P.decodeFrame(keepalive()))}"
        assertEquals(3, debugs.count { it == ka })
        assertEquals(2, infos.count { it == ka })
        clientNow += BtrxClient.TX_LOG_INTERVAL_MS
        client.sendFrame(desk, keepalive())
        assertTrue(debugs.toString(), debugs.contains("$ka (+2 like it not logged since the last one)"))
        // A real answer (confirm) is always logged; so is every other frame type.
        val confirm = P.confirmRspFrame(2, 7, P.CFM_CONFIRM)
        repeat(4) { client.sendFrame(desk, confirm) }
        assertEquals(4, debugs.count { it == "tx to=!66c842ca ${BtrxFrameLog.format(P.decodeFrame(confirm))}" })
        val ping = P.timePingFrame(3, 0x45d35af0L, 9, 123_456L)
        repeat(4) { client.sendFrame(0x018968beL, ping) }
        val pingLine = "tx to=!018968be ${BtrxFrameLog.format(P.decodeFrame(ping))}"
        assertEquals(3, debugs.count { it == pingLine })
        assertTrue(pingLine, pingLine.contains("TIME_PING") && pingLine.contains("ping_seq=9"))
        assertTrue(ka, ka.contains("CONFIRM_RSP") && ka.contains("answer=keepalive"))
    }
}

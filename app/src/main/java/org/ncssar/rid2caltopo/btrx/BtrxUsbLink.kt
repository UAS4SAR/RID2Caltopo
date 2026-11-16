package org.ncssar.rid2caltopo.btrx

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * USB CDC-ACM link to one Desk Pod (usb-serial-for-android, MIT), the debug link on
 * Android; Bluetooth ([BtrxBleLink]) is the primary one. Reads and writes run on the
 * library's own I/O threads; timers run on a private scheduler. Nothing here runs
 * on, or waits for, the main thread. While this link is open the Desk Pod firmware
 * keeps Bluetooth off, so [BtrxManager] stops the Bluetooth link first.
 */
class BtrxUsbLink(
    private val usbManager: UsbManager,
    val device: UsbDevice,
    private val log: BtrxClient.Logger,
    private val forward: BtrxClient.Listener = object : BtrxClient.Listener {},
    private val onClosed: (BtrxUsbLink, String) -> Unit,
) {
    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "btrx-link").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    val client = BtrxClient(
        send = { bytes -> io?.writeAsync(bytes) ?: log.warn("write dropped: link not open") },
        log = log,
        framing = BtrxClient.Framing.SERIAL,
        listener = object : BtrxClient.Listener {
            override fun onConfigured(localNode: Long) = forward.onConfigured(localNode)
            override fun onLocalRoleReport(report: BtrxProtocol.RoleReport) {
                forward.onLocalRoleReport(report)
                if (report.role != BtrxProtocol.ROLE_DESK_POD) {
                    // All three boards share VID/PID; only the Desk Pod may be used.
                    timers.execute { close("not a Desk Pod (role=${report.role})") }
                }
            }
            override fun onFrame(frame: BtrxProtocol.Frame, meta: BtrxClient.RadioMeta) = forward.onFrame(frame, meta)
            override fun onVersionRefused(fromNode: Long, version: Int) = forward.onVersionRefused(fromNode, version)
            override fun onPacketDropped(encrypted: Boolean) = forward.onPacketDropped(encrypted)
        },
    )

    /** Opens the port and starts the handshake. Call on a worker thread. */
    @Throws(IOException::class)
    fun open() {
        val connection = usbManager.openDevice(device) ?: throw IOException("openDevice returned null (no permission?)")
        val driver = CdcAcmSerialDriver(device)
        val p = driver.ports.firstOrNull() ?: run { connection.close(); throw IOException("no CDC-ACM port") }
        try {
            p.open(connection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Assert DTR/RTS like pyserial does for r2c_sim; the board's USB serial waits for DTR.
            runCatching { p.dtr = true }
            runCatching { p.rts = true }
        } catch (e: IOException) {
            runCatching { p.close() }
            throw e
        }
        port = p
        val manager = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = client.onBytes(data)
            override fun onRunError(e: Exception) {
                if (!closed) timers.execute { close("serial error: ${e.message}") }
            }
        })
        io = manager
        manager.start()
        log.info("USB open: ${describe(device)}")
        client.start()
        later(ROLE_TIMEOUT_S) { checkRole(retry = true, configWaits = 0) }
        // Ends the connect backlog at the first quiet gap after config_complete (then ROLE_GET).
        timers.scheduleWithFixedDelay({ client.checkQuietGap() }, QUIET_POLL_MS, QUIET_POLL_MS, TimeUnit.MILLISECONDS)
        timers.scheduleWithFixedDelay({ client.sendHeartbeat() }, HEARTBEAT_S, HEARTBEAT_S, TimeUnit.SECONDS)
        timers.scheduleWithFixedDelay({ log.info("summary: ${client.summary()}") }, SUMMARY_S, SUMMARY_S, TimeUnit.SECONDS)
    }

    /**
     * The self-addressed ROLE_REPORT reaching USB is untested on the boards, so a
     * missing reply is logged and never blocks the link: frames keep being decoded.
     */
    private fun checkRole(retry: Boolean, configWaits: Int) {
        if (closed) return
        when {
            client.state != BtrxClient.State.READY -> {
                val waited = (configWaits + 1) * ROLE_TIMEOUT_S
                log.warn("no Meshtastic config_complete after ${waited}s; is r2c_sim or another program holding the board?")
                if (configWaits + 1 < MAX_CONFIG_WAITS) later(ROLE_TIMEOUT_S) { checkRole(retry, configWaits + 1) }
            }
            client.localRole != null -> Unit
            retry -> {
                log.warn("no ROLE_REPORT from the Desk Pod after ${ROLE_TIMEOUT_S}s; asking again")
                client.sendRoleGet()
                later(ROLE_TIMEOUT_S) { checkRole(retry = false, configWaits = configWaits) }
            }
            else -> log.warn("no ROLE_REPORT from Desk Pod after a retry; Desk Pod role and build not confirmed. " +
                "Keeping the link open and decoding frames.")
        }
    }

    private fun later(seconds: Long, task: () -> Unit) {
        if (closed) return
        try {
            timers.schedule(Runnable { task() }, seconds, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Closed meanwhile.
        }
    }

    @Synchronized
    fun close(reason: String) {
        if (closed) return
        closed = true
        log.info("USB close ($reason); ${client.summary()}")
        if (reason != "detached") sendDisconnect()
        client.close()
        runCatching { io?.stop() }
        runCatching { port?.close() }
        io = null
        port = null
        timers.shutdownNow()
        onClosed(this, reason)
    }

    /**
     * ToRadio{disconnect=true} before the port closes: the firmware ends the API session
     * and turns Bluetooth back on within seconds instead of after its serial timeout
     * (up to 15 min). Written synchronously so it is not lost when the I/O thread stops.
     */
    private fun sendDisconnect() {
        val p = port ?: return
        runCatching { p.write(MeshtasticSerial.frame(MeshtasticSerial.toRadioDisconnect()), DISCONNECT_WRITE_TIMEOUT_MS) }
            .onSuccess { log.info("USB: sent disconnect so the Desk Pod re-enables Bluetooth") }
            .onFailure { log.warn("USB: disconnect not sent (${it.message}); the Desk Pod may keep Bluetooth off for up to 15 min") }
    }

    companion object {
        const val DISCONNECT_WRITE_TIMEOUT_MS = 200
        const val BAUD = 115200
        const val ROLE_TIMEOUT_S = 10L
        const val MAX_CONFIG_WAITS = 6
        const val HEARTBEAT_S = 300L
        const val SUMMARY_S = 60L
        const val QUIET_POLL_MS = 100L

        fun describe(d: UsbDevice): String =
            "%s %04x:%04x %s".format(d.deviceName, d.vendorId, d.productId, d.productName ?: "")
    }
}

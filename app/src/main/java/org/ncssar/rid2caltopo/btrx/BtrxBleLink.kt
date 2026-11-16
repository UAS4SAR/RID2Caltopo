package org.ncssar.rid2caltopo.btrx

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkPhase

/**
 * Bluetooth link to the Desk Pod over the Meshtastic phone API (the primary link;
 * USB is for debugging). Long-lived: [start] keeps a session up, reconnecting with
 * [BtrxReconnectBackoff] whenever it drops, until [stop]. The firmware never
 * reconnects by itself.
 *
 * Everything runs on one private HandlerThread, never on the main thread. GATT
 * callbacks arrive on binder threads and are posted there; every GATT operation goes
 * through one [BtrxGattQueue], so a heartbeat and a command never overlap.
 *
 * Session: connect, MTU 247 (the Desk Pod's maximum), discover, enable FromNum notifications, write
 * want_config_id, then drain FromRadio until empty ([BtrxFromRadioDrain]) after the
 * handshake and after every FromNum notification. The phone API is the one USB
 * uses, so [BtrxClient] decodes the frames unchanged ([BtrxClient.Framing.PACKET]).
 *
 * Bonding: the Desk Pod uses a fixed PIN (printed on the unit; 123456 by default for
 * now). An unbonded Desk Pod gets createBond() and Android shows its PIN dialog.
 * Runs with the screen locked: the RID scanning foreground service keeps the process
 * alive, and the scan uses a service-UUID filter, which Android requires when the
 * screen is off.
 *
 * Patient reconnect (Nov 16 field test: after a drop the Desk Pod stopped advertising
 * for minutes and every 10 s direct connect failed with status 147): after
 * [DIRECT_TRIES] direct connects to the remembered Desk Pod fail, one background
 * connectGatt(autoConnect = true) stays pending, with no timeout, until it connects or
 * the link stops. A Meshtastic-UUID scan every [PATIENT_SCAN_MS] still finds the Desk Pod
 * (or another one) and connects to it directly.
 *
 * The phase and the open GATT go to [BtrxRadioCoordinator], which holds off the Remote
 * ID scan's periodic restart while the link is busy and guards against adapter resets.
 */
@SuppressLint("MissingPermission") // every entry point checks hasPermissions() first
class BtrxBleLink(
    private val context: Context,
    private val log: BtrxClient.Logger,
    private val events: Events,
) {
    interface Events {
        /** [failedAttempts]/[lastFailure]: connects failing in a row and why the latest did (0/null once READY). */
        fun onStatus(phase: LinkPhase, detail: String, deviceName: String?, failedAttempts: Int, lastFailure: String?)
        /** Forwarded client callbacks (the model). */
        val clientListener: BtrxClient.Listener
        fun onSessionStarted(client: BtrxClient)
        fun onSessionEnded(client: BtrxClient, reason: String)
        fun rememberedAddress(): String?
        fun rememberDevice(address: String, name: String?)
    }

    private sealed class Op {
        data object RequestMtu : Op()
        data object Discover : Op()
        class EnableNotify(val descriptor: BluetoothGattDescriptor) : Op()
        class Write(val bytes: ByteArray) : Op()
        data object ReadFromRadio : Op()
    }

    private val thread = HandlerThread("btrx-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val backoff = BtrxReconnectBackoff()

    // Handler-confined state.
    private var running = false
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var client: BtrxClient? = null
    private var queue: BtrxGattQueue<Op>? = null
    private var drain: BtrxFromRadioDrain? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var handshakeWritten = false
    /** ATT MTU of the current session; 23 until onMtuChanged reports the negotiated one. */
    private var attMtu = 23
    private var scanning = false
    private var scanRound = 0
    private var directTries = 0
    /** A background (autoConnect) connect to the remembered Desk Pod is pending. */
    private var patient = false
    /** The running scan is a patient-wait scan (no status change, one round). */
    private var patientScanning = false
    private val found = LinkedHashMap<String, Pair<BluetoothDevice, Int>>()
    private val foundNames = HashMap<String, String?>()
    private val rejected = mutableSetOf<String>()
    private var bondReceiver: BroadcastReceiver? = null
    private var sessionSerial = 0

    private val attempt = Runnable { attempt() }
    private val scanDone = Runnable { finishScan() }
    private val nextScan = Runnable { adapter()?.let { if (running && gatt == null && !scanning) scan(it) } }
    private val patientScan = Runnable { adapter()?.let { if (running && patient && client == null && !scanning) scan(it, patientRound = true) } }
    private val opTimeout = Runnable { sessionLost("a Bluetooth operation timed out") }
    private val connectTimeout = Runnable { sessionLost("connection timed out") }
    private val bondTimeout = Runnable { bondFinished(false, "bonding did not finish within ${BOND_TIMEOUT_MS / 1000} s") }
    /**
     * Bond state poll while bonding. Samsung (A5 Pro, Android 15) never delivered
     * ACTION_BOND_STATE_CHANGED to the receiver: the bond finished 12 s after the PIN dialog
     * opened and the link sat out the full 90 s timeout. Polling makes the receiver optional.
     */
    private val bondPoll = object : Runnable {
        override fun run() {
            if (bondReceiver == null) return
            if (device?.bondState == BluetoothDevice.BOND_BONDED) bondFinished(true, "bonded (state poll)")
            else handler.postDelayed(this, BOND_POLL_MS)
        }
    }
    private val heartbeat = object : Runnable {
        override fun run() { client?.sendHeartbeat(); handler.postDelayed(this, HEARTBEAT_MS) }
    }
    private val summary = object : Runnable {
        override fun run() { client?.let { log.info("ble summary: ${it.summary()}") }; handler.postDelayed(this, SUMMARY_MS) }
    }

    /** Starts (or keeps) the reconnect loop. [delayMs] lets USB release the Desk Pod first. */
    fun start(delayMs: Long = 0) {
        handler.post {
            if (running) return@post
            running = true
            backoff.reset()
            scanRound = 0
            directTries = 0
            rejected.clear()
            handler.removeCallbacks(attempt)
            handler.postDelayed(attempt, delayMs)
        }
    }

    /** Stops scanning and closes the GATT session; returns once it is closed (at most 2 s). */
    fun stop(reason: String, finalPhase: LinkPhase = LinkPhase.OFF, detail: String = "") {
        val done = CountDownLatch(1)
        handler.post {
            running = false
            handler.removeCallbacks(attempt)
            handler.removeCallbacks(nextScan)
            stopScan()
            closeSession(reason)
            status(finalPhase, detail, null)
            done.countDown()
        }
        if (Thread.currentThread() !== thread) done.await(2, TimeUnit.SECONDS)
    }

    // ---- connect loop ----

    private fun adapter(): BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasPermissions(): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun attempt() {
        if (!running || gatt != null || scanning) return
        val adapter = adapter() ?: run {
            status(LinkPhase.UNAVAILABLE, "This tablet has no Bluetooth.", null)
            return
        }
        if (!hasPermissions()) { retryLater(LinkPhase.NO_PERMISSION, "RID2Caltopo needs the Nearby devices permission."); return }
        if (!adapter.isEnabled) { retryLater(LinkPhase.UNAVAILABLE, "Bluetooth is off."); return }
        val remembered = events.rememberedAddress()
        if (remembered != null && remembered !in rejected && BluetoothAdapter.checkBluetoothAddress(remembered)) {
            val dev = adapter.getRemoteDevice(remembered)
            if (dev.bondState == BluetoothDevice.BOND_BONDED) {
                if (directTries < DIRECT_TRIES) {
                    directTries++
                    connect(dev, foundNames[remembered] ?: dev.name)
                } else {
                    startPatient(dev, foundNames[remembered] ?: dev.name)
                }
                return
            }
        }
        directTries = 0
        scan(adapter)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            handler.post { onFound(result.device, result.rssi, name) }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }
        override fun onScanFailed(errorCode: Int) {
            handler.post {
                log.warn("ble: scan failed (code $errorCode)")
                scanning = false
                handler.removeCallbacks(scanDone)
                retryLater(LinkPhase.RETRY_WAIT, "Bluetooth scan failed (code $errorCode).")
            }
        }
    }

    /**
     * [patientRound]: one scan while a background connect is pending; it keeps the
     * CONNECTING status and the pending connect unless it finds a Desk Pod.
     */
    private fun scan(adapter: BluetoothAdapter, patientRound: Boolean = false) {
        val scanner = adapter.bluetoothLeScanner ?: run {
            if (patientRound) { handler.postDelayed(patientScan, PATIENT_SCAN_MS); return }
            retryLater(LinkPhase.UNAVAILABLE, "Bluetooth scanning is unavailable."); return
        }
        found.clear()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(MeshtasticBle.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (e: Exception) {
            if (patientRound) { handler.postDelayed(patientScan, PATIENT_SCAN_MS); return }
            retryLater(LinkPhase.RETRY_WAIT, "Bluetooth scan could not start (${e.message}).")
            return
        }
        scanning = true
        patientScanning = patientRound
        if (patientRound) {
            log.debug("ble: scanning for the Desk Pod while the background connect waits")
        } else {
            scanRound++
            status(LinkPhase.SCANNING, "Looking for the Desk Pod (scan $scanRound of $SCAN_ROUNDS).", null)
        }
        handler.postDelayed(scanDone, SCAN_MS)
    }

    private fun onFound(dev: BluetoothDevice, rssi: Int, name: String?) {
        if (!scanning || dev.address in rejected) return
        // Debug level: the session log drops INFO lines at the default level.
        if (dev.address !in found) log.debug("ble: found ${name ?: "unnamed"} ${dev.address.takeLast(5)} rssi=$rssi")
        found[dev.address] = dev to rssi
        foundNames[dev.address] = name
        if (dev.address == events.rememberedAddress()) finishScan()
    }

    private fun stopScan() {
        handler.removeCallbacks(scanDone)
        if (!scanning) return
        scanning = false
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun finishScan() {
        if (!scanning) return
        val wasPatient = patientScanning
        patientScanning = false
        stopScan()
        if (!running) return
        val remembered = events.rememberedAddress()
        val pick = found[remembered]?.first ?: found.values.maxByOrNull { it.second }?.first
        if (wasPatient) {
            if (!patient || client != null) return
            if (pick == null) {
                log.debug("ble: Desk Pod not advertising; the background connect keeps waiting")
                handler.postDelayed(patientScan, PATIENT_SCAN_MS)
                return
            }
            // A direct connect is quicker than Android's background connect.
            log.debug("ble: ${foundNames[pick.address] ?: "Desk Pod"} advertising again; connecting directly")
            closeSession("background connect replaced by a direct connect")
            connect(pick, foundNames[pick.address] ?: pick.name)
            return
        }
        if (pick == null) {
            if (scanRound < SCAN_ROUNDS) {
                // A single short scan sometimes missed the Desk Pod on the bench. Space the
                // rounds out: Android stops apps that start more than 5 scans in 30 s.
                handler.postDelayed(nextScan, SCAN_GAP_MS)
            } else {
                scanRound = 0
                retryLater(LinkPhase.RETRY_WAIT, "No Desk Pod found. Check that it is powered and nearby.")
            }
            return
        }
        scanRound = 0
        if (found.size > 1) log.info("ble: ${found.size} Meshtastic devices found; using ${foundNames[pick.address] ?: "the strongest"}")
        connect(pick, foundNames[pick.address] ?: pick.name)
    }

    private fun connect(dev: BluetoothDevice, name: String?) {
        device = dev
        if (dev.bondState != BluetoothDevice.BOND_BONDED) {
            status(LinkPhase.BONDING, "Pairing with ${name ?: "the Desk Pod"}: enter the PIN printed on the Desk Pod (${MeshtasticBle.DEFAULT_PIN} by default).", name)
            registerBondReceiver(dev)
            handler.postDelayed(bondTimeout, BOND_TIMEOUT_MS)
            handler.postDelayed(bondPoll, BOND_POLL_MS)
            if (!dev.createBond() && dev.bondState != BluetoothDevice.BOND_BONDING) {
                bondFinished(false, "Android refused to start bonding")
            }
            return
        }
        openGatt(dev, name)
    }

    private fun registerBondReceiver(dev: BluetoothDevice) {
        unregisterBondReceiver()
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != dev.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                handler.post {
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> bondFinished(true, "bonded")
                        BluetoothDevice.BOND_NONE -> bondFinished(false, "bonding failed or was cancelled")
                    }
                }
            }
        }
        bondReceiver = r
        // The Bluetooth stack (not the system UID) sends this protected broadcast, so a
        // RECEIVER_NOT_EXPORTED receiver may never get it; only the system can send it anyway.
        ContextCompat.registerReceiver(context, r, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
        handler.removeCallbacks(bondTimeout)
        handler.removeCallbacks(bondPoll)
    }

    private fun bondFinished(ok: Boolean, why: String) {
        if (bondReceiver == null) return
        unregisterBondReceiver()
        val dev = device ?: return
        if (!running) return
        if (ok) {
            log.info("ble: bonded with ${dev.name ?: "the Desk Pod"}")
            openGatt(dev, dev.name)
        } else {
            log.warn("ble: $why")
            retryLater(LinkPhase.RETRY_WAIT, "Bluetooth pairing with the Desk Pod did not complete ($why). Enter the PIN printed on the unit (${MeshtasticBle.DEFAULT_PIN} by default).")
        }
    }

    /**
     * [DIRECT_TRIES] direct connects failed: one background connect (autoConnect = true,
     * no timeout) waits for the Desk Pod to advertise again, until it connects or [stop].
     */
    private fun startPatient(dev: BluetoothDevice, name: String?) {
        log.debug("ble: $DIRECT_TRIES direct connects failed; background connect to ${name ?: "the Desk Pod"} " +
            "pending until it advertises (scan every ${PATIENT_SCAN_MS / 1000} s)")
        openGatt(dev, name, background = true)
    }

    private fun openGatt(dev: BluetoothDevice, name: String?, background: Boolean = false) {
        status(
            LinkPhase.CONNECTING,
            if (background) "Waiting for ${name ?: "the Desk Pod"} to come back; it reconnects as soon as the Desk Pod advertises."
            else "Connecting to ${name ?: "the Desk Pod"}.",
            name,
        )
        handshakeWritten = false
        attMtu = 23
        val serial = ++sessionSerial
        val g = dev.connectGatt(context, background, GattCallback(serial), BluetoothDevice.TRANSPORT_LE)
        if (g == null) { retryLater(LinkPhase.RETRY_WAIT, "Android could not open a Bluetooth connection."); return }
        gatt = g
        BtrxRadioCoordinator.onGattOpen(true)
        if (background) {
            patient = true
            handler.postDelayed(patientScan, PATIENT_SCAN_MS)
        } else {
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        }
    }

    private fun status(phase: LinkPhase, detail: String, deviceName: String?) {
        BtrxRadioCoordinator.onLinkPhase(phase)
        events.onStatus(phase, detail, deviceName, backoff.failures, backoff.lastFailure)
    }

    /** [failure]: short reason for the Desk Pod screen's "reconnecting" line. */
    private fun retryLater(phase: LinkPhase, detail: String, failure: String = detail.trimEnd('.')) {
        if (!running) return
        val delay = backoff.nextDelayMs(failure)
        status(phase, "$detail Retrying in ${delay / 1000} s.", null)
        handler.removeCallbacks(attempt)
        handler.postDelayed(attempt, delay)
    }

    // ---- session ----

    private inner class GattCallback(private val serial: Int) : BluetoothGattCallback() {
        private fun on(block: () -> Unit) {
            handler.post { if (serial == sessionSerial && gatt != null) block() }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = on {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) onConnected(g)
            // Any non-success status (e.g. 133, 8, 22) ends the attempt now instead of after the 30 s timeout.
            else if (status != BluetoothGatt.GATT_SUCCESS) sessionLost(authHint(status, "connection failed"))
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) sessionLost("disconnected (status $status)")
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = on {
            log.info("ble: mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) attMtu = mtu
            if (attMtu < BtrxClient.MIN_USABLE_MTU) log.warn("ERROR: ble: negotiated MTU $attMtu is below ${BtrxClient.MIN_USABLE_MTU}; btrx writes may not fit")
            completeOp()
        }
        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) = on {
            log.debug(BtrxBleLinkTuning.phyText("phy update", txPhy, rxPhy, status))
        }
        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) = on {
            log.debug(BtrxBleLinkTuning.phyText("phy read", txPhy, rxPhy, status))
        }
        /**
         * Hidden in the SDK (BluetoothGattCallback.onConnectionUpdated, @hide) but called on
         * every connection-parameter change; no `override`, it matches by signature.
         */
        @Keep
        @Suppress("unused")
        fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) = on {
            log.debug(BtrxBleLinkTuning.connectionUpdateText(interval, latency, timeout, status))
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = on { onDiscovered(g, status) }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = on {
            completeOp()
            if (status != BluetoothGatt.GATT_SUCCESS) { sessionLost(authHint(status, "FromNum notifications could not be enabled")); return@on }
            startClient()
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = on {
            completeOp()
            if (status != BluetoothGatt.GATT_SUCCESS) { sessionLost(authHint(status, "a ToRadio write failed")); return@on }
            if (!handshakeWritten) { handshakeWritten = true; drain?.kick() }
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = on {
            onRead(value, status)
        }
        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val v = c.value ?: ByteArray(0)
            on { onRead(v, status) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = on {
            if (c.uuid == FROM_NUM) drain?.kick()
        }
        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = on {
            if (c.uuid == FROM_NUM) drain?.kick()
        }
    }

    private fun onConnected(g: BluetoothGatt) {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(patientScan)
        if (patient) log.debug("ble: background connect succeeded")
        patient = false
        log.info("ble: connected to ${device?.name ?: "Desk Pod"}")
        // The config dump and the Desk Pod's queued frames (up to 32) are read one FromRadio
        // per round trip; a short connection interval cuts that from tens of seconds. HIGH
        // stays for the whole link and the PHY is pinned to 1M ([BtrxBleLinkTuning]).
        val fast = runCatching { g.requestConnectionPriority(BtrxBleLinkTuning.CONNECTION_PRIORITY) }.getOrDefault(false)
        log.debug("ble: connection priority high ${if (fast) "requested" else "refused"}")
        val phy = runCatching {
            g.setPreferredPhy(BtrxBleLinkTuning.PREFERRED_PHY_MASK, BtrxBleLinkTuning.PREFERRED_PHY_MASK, BtrxBleLinkTuning.PHY_OPTIONS)
        }
        log.debug("ble: preferred phy 1M ${if (phy.isSuccess) "requested" else "refused (${phy.exceptionOrNull()?.javaClass?.simpleName})"}")
        status(LinkPhase.CONFIGURING, "Connected; reading the Desk Pod's configuration.", device?.name)
        val q = BtrxGattQueue<Op>(start = { startOp(g, it) }, onStartFailed = { handler.post { sessionLost("Android refused a Bluetooth operation") } })
        queue = q
        drain = BtrxFromRadioDrain { q.enqueue(Op.ReadFromRadio) }
        q.enqueue(Op.RequestMtu)
        q.enqueue(Op.Discover)
    }

    private fun onDiscovered(g: BluetoothGatt, status: Int) {
        completeOp()
        val service = g.getService(SERVICE)
        val to = service?.getCharacteristic(TO_RADIO)
        val from = service?.getCharacteristic(FROM_RADIO)
        val num = service?.getCharacteristic(FROM_NUM)
        if (status != BluetoothGatt.GATT_SUCCESS || to == null || from == null || num == null) {
            sessionLost("the device does not offer the Meshtastic phone API (status $status)")
            return
        }
        toRadio = to; fromRadio = from
        g.setCharacteristicNotification(num, true)
        val cccd = num.getDescriptor(CCCD) ?: run { sessionLost("FromNum has no notification descriptor"); return }
        queue?.enqueue(Op.EnableNotify(cccd))
    }

    private fun startClient() {
        val self = this
        val forward = events.clientListener
        val c = BtrxClient(
            send = { bytes -> handler.post { queue?.enqueue(Op.Write(bytes)) } },
            log = log,
            framing = BtrxClient.Framing.PACKET,
            listener = object : BtrxClient.Listener {
                override fun onConfigured(localNode: Long) {
                    handler.post { self.onReady() }
                    forward.onConfigured(localNode)
                }
                override fun onLocalRoleReport(report: BtrxProtocol.RoleReport) {
                    forward.onLocalRoleReport(report)
                    if (report.role != BtrxProtocol.ROLE_DESK_POD) handler.post {
                        device?.address?.let { rejected += it }
                        sessionLost("not a Desk Pod (role=${report.role})")
                    }
                }
                override fun onFrame(frame: BtrxProtocol.Frame, meta: BtrxClient.RadioMeta) = forward.onFrame(frame, meta)
                override fun onVersionRefused(fromNode: Long, version: Int) = forward.onVersionRefused(fromNode, version)
                override fun onPacketDropped(encrypted: Boolean) = forward.onPacketDropped(encrypted)
            },
        )
        client = c
        c.setAttMtu(attMtu)
        events.onSessionStarted(c)
        c.start()
        handler.postDelayed({ checkRole(c, retried = false) }, BtrxClient.ROLE_POLL_MS)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        handler.postDelayed(summary, SUMMARY_MS)
    }

    private fun onReady() {
        backoff.reset()
        directTries = 0
        val dev = device ?: return
        events.rememberDevice(dev.address, dev.name)
        status(LinkPhase.READY, "Connected over Bluetooth.", dev.name)
    }

    /** The ROLE_REPORT wait (timing in [BtrxClient.roleCheck]: from ROLE_GET, after the connect backlog). */
    private fun checkRole(c: BtrxClient, retried: Boolean) {
        if (client !== c) return
        val r = c.roleCheck(retried)
        when (r.action) {
            BtrxClient.RoleWait.DONE -> gatt?.let { g ->
                // Priority stays HIGH (no BALANCED switch); log the PHY in effect once set up.
                BtrxBleLinkTuning.priorityAfterRole?.let { p -> runCatching { g.requestConnectionPriority(p) } }
                runCatching { g.readPhy() }
            }
            BtrxClient.RoleWait.WAIT -> handler.postDelayed({ checkRole(c, retried) }, r.delayMs)
            BtrxClient.RoleWait.RETRY -> {
                log.warn("ble: no ROLE_REPORT from the Desk Pod within ${BtrxClient.ROLE_TIMEOUT_MS / 1000} s of ROLE_GET; asked again")
                handler.postDelayed({ checkRole(c, true) }, r.delayMs)
            }
            BtrxClient.RoleWait.GIVE_UP -> log.warn("ble: no ROLE_REPORT from the Desk Pod after a retry; keeping the link open and decoding frames.")
            BtrxClient.RoleWait.NO_CONFIG -> log.warn("ble: no Meshtastic config_complete after ${BtrxClient.CONFIG_TIMEOUT_MS / 1000}s")
        }
    }

    private fun startOp(g: BluetoothGatt, op: Op): Boolean {
        handler.removeCallbacks(opTimeout)
        handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
        return when (op) {
            Op.RequestMtu -> g.requestMtu(MeshtasticBle.MTU)
            Op.Discover -> g.discoverServices()
            is Op.EnableNotify -> writeDescriptor(g, op.descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            is Op.Write -> toRadio?.let { writeCharacteristic(g, it, op.bytes) } ?: false
            Op.ReadFromRadio -> fromRadio?.let { g.readCharacteristic(it) } ?: false
        }
    }

    private fun completeOp() {
        handler.removeCallbacks(opTimeout)
        queue?.complete()
    }

    private fun onRead(value: ByteArray, status: Int) {
        completeOp()
        val d = drain ?: return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            d.reset()
            sessionLost(authHint(status, "a FromRadio read failed"))
            return
        }
        d.onRead(value)
        // Empty reads too: the first one after config_complete ends the connect backlog.
        client?.onFromRadio(value)
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = bytes
            g.writeCharacteristic(c)
        }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }

    private fun authHint(status: Int, what: String): String =
        if (status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION || status == GATT_AUTH_FAIL) {
            "$what: not bonded (status $status). Enter the PIN printed on the Desk Pod (${MeshtasticBle.DEFAULT_PIN} by default); if it was re-flashed, forget it in Android Bluetooth settings first"
        } else "$what (status $status)"

    private fun sessionLost(reason: String) {
        if (gatt == null && client == null) return
        log.warn("ble: session ended: $reason")
        closeSession(reason)
        retryLater(LinkPhase.RETRY_WAIT, "Bluetooth link lost: $reason.", failure = reason)
    }

    private fun closeSession(reason: String) {
        handler.removeCallbacks(opTimeout)
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(patientScan)
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(summary)
        patient = false
        if (patientScanning) stopScan()
        patientScanning = false
        unregisterBondReceiver()
        queue?.clear(); queue = null
        drain?.reset(); drain = null
        sessionSerial++
        client?.let { c ->
            log.info("ble close ($reason); ${c.summary()}")
            c.close()
            events.onSessionEnded(c, reason)
        }
        client = null
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
        BtrxRadioCoordinator.onGattOpen(false)
        toRadio = null; fromRadio = null
    }

    companion object {
        val SERVICE: UUID = UUID.fromString(MeshtasticBle.SERVICE_UUID)
        val TO_RADIO: UUID = UUID.fromString(MeshtasticBle.TO_RADIO_UUID)
        val FROM_RADIO: UUID = UUID.fromString(MeshtasticBle.FROM_RADIO_UUID)
        val FROM_NUM: UUID = UUID.fromString(MeshtasticBle.FROM_NUM_UUID)
        val CCCD: UUID = UUID.fromString(MeshtasticBle.CCCD_UUID)
        const val SCAN_MS = 12_000L
        const val SCAN_GAP_MS = 3_000L
        const val SCAN_ROUNDS = 3
        const val DIRECT_TRIES = 2
        const val CONNECT_TIMEOUT_MS = 30_000L
        /** While a background connect waits: one Meshtastic-UUID scan this often. */
        const val PATIENT_SCAN_MS = 60_000L
        const val BOND_TIMEOUT_MS = 90_000L
        const val OP_TIMEOUT_MS = 10_000L
        const val BOND_POLL_MS = 1_000L
        const val HEARTBEAT_MS = 300_000L
        const val SUMMARY_MS = 60_000L
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        private const val GATT_INSUFFICIENT_ENCRYPTION = 15
        private const val GATT_AUTH_FAIL = 137
    }
}

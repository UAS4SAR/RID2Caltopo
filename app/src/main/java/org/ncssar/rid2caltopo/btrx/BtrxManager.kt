package org.ncssar.rid2caltopo.btrx

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkKind
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkPhase
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodModel.LinkStatus
import org.ncssar.rid2caltopo.data.CaltopoClient

/**
 * Owns the Desk Pod link and the [BtrxDeskPodModel] behind the Desk Pod screen.
 *
 * - Bluetooth ([BtrxBleLink]) is the primary link. It runs only after the operator
 *   turns it on in the Desk Pod screen (remembered), then reconnects by itself.
 * - USB ([BtrxUsbLink]) is an opt-in debug link ("Use USB link (debug)", off by
 *   default). The Desk Pod has no battery, so a tablet's USB-C port is often just its
 *   power: with the switch off a plugged-in Desk Pod is never opened, the USB attach
 *   activity is disabled, and Bluetooth stays the link. With it on, plugging a Desk
 *   Pod in stops Bluetooth first and opens USB ([BtrxLinkArbiter]); the screen says
 *   "Desk Pod is attached by USB; Bluetooth paused". Closing USB sends
 *   ToRadio{disconnect} so the firmware re-enables Bluetooth, which resumes after a
 *   few seconds. R2C never holds both links.
 *
 * All link work runs on [worker] or the links' own threads; UI calls only post to it.
 */
object BtrxManager {
    private const val TAG = "Btrx"
    const val VENDOR_ID = 0x239A
    const val PRODUCT_ID = 0x8029
    private const val ACTION_USB_PERMISSION = "org.ncssar.rid2caltopo.btrx.USB_PERMISSION"
    private const val PREFS = "btrx_desk_pod"
    private const val KEY_BLE_ENABLED = "ble_enabled"
    private const val KEY_ADDRESS = "ble_address"
    private const val KEY_NAME = "ble_name"
    private const val KEY_PAIRED = "paired_pod"
    private const val KEY_USB_DEBUG = "usb_debug"
    /** Time the Desk Pod takes to advertise again after a clean USB close. */
    private const val BLE_RESUME_AFTER_USB_MS = 3_000L

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "btrx-usb").apply { isDaemon = true } }
    private lateinit var appContext: Context
    @Volatile private var installed = false
    @Volatile private var usbHost = false
    @Volatile private var link: BtrxUsbLink? = null
    @Volatile private var ble: BtrxBleLink? = null
    /** Boards that answered with a role other than Desk Pod, ignored until unplugged. */
    private val rejected = mutableSetOf<String>()
    private val requested = mutableSetOf<String>()
    private val arbiter = BtrxLinkArbiter()
    private var bleStatus = LinkStatus(LinkKind.BLUETOOTH, LinkPhase.OFF)

    val logger = object : BtrxClient.Logger {
        override fun info(message: String) = CaltopoClient.CTInfo(TAG, message)
        override fun warn(message: String) = CaltopoClient.CTWarn(TAG, message)
        override fun debug(message: String) = CaltopoClient.CTDebug(TAG, message)
    }

    val model = BtrxDeskPodModel(logger, onPaired = { pod -> worker.execute { savePairedPod(pod) } })

    private val _snapshot = MutableStateFlow(model.snapshot())
    /** Desk Pod screen state, refreshed about once a second while a link or Bluetooth is on. */
    val snapshot: StateFlow<BtrxDeskPodModel.Snapshot> = _snapshot.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(false)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _usbDebugEnabled = MutableStateFlow(false)
    /** "Use USB link (debug)": off by default; a plugged-in Desk Pod is then power only. */
    val usbDebugEnabled: StateFlow<Boolean> = _usbDebugEnabled.asStateFlow()
    @Volatile private var powerOnlyLogged = false

    private val modelListener = object : BtrxClient.Listener {
        override fun onConfigured(localNode: Long) = model.onConfigured(localNode)
        override fun onLocalRoleReport(report: BtrxProtocol.RoleReport) = model.onLocalRoleReport(report)
        override fun onFrame(frame: BtrxProtocol.Frame, meta: BtrxClient.RadioMeta) {
            model.onFrame(frame, meta)
            // The overlay follows each STEER (about every second), not only the 1 s tick.
            if (frame.type == BtrxProtocol.STEER && !meta.backlog) worker.execute { publish() }
        }
    }

    fun isDeskPodCandidate(d: UsbDevice?): Boolean = d != null && d.vendorId == VENDOR_ID && d.productId == PRODUCT_ID

    /** Current USB link, if any. */
    fun currentLink(): BtrxUsbLink? = link

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        appContext = context.applicationContext
        installed = true
        usbHost = appContext.packageManager.hasSystemFeature("android.hardware.usb.host")
        if (usbHost) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        worker.execute {
            val p = prefs()
            model.restorePairedPod(loadPairedPod())
            val enabled = p.getBoolean(KEY_BLE_ENABLED, false)
            _bluetoothEnabled.value = enabled
            val usbDebug = p.getBoolean(KEY_USB_DEBUG, false)
            _usbDebugEnabled.value = usbDebug
            setAttachActivityEnabled(usbDebug)
            if (usbHost && usbDebug) scanAttached()
            applyArbiter(arbiter.update(bluetoothEnabled = enabled, usbDebugEnabled = usbDebug))
            publish()
        }
        worker.scheduleWithFixedDelay({
            try { model.tick(); updateLinkStatus(); retainSteeringStream(); publish() } catch (e: Exception) { logger.warn("Desk Pod tick failed: ${e.message}") }
        }, 1, 1, TimeUnit.SECONDS)
    }

    // ---- Desk Pod screen actions (post to the worker; never block the caller) ----

    fun setBluetoothEnabled(enabled: Boolean) = worker.execute {
        prefs().edit().putBoolean(KEY_BLE_ENABLED, enabled).apply()
        _bluetoothEnabled.value = enabled
        logger.info("Desk Pod Bluetooth link turned ${if (enabled) "on" else "off"}")
        applyArbiter(arbiter.update(bluetoothEnabled = enabled))
        publish()
    }

    /** The USB debug switch. Off closes an open USB link (Bluetooth then resumes). */
    fun setUsbDebugEnabled(enabled: Boolean) = worker.execute {
        prefs().edit().putBoolean(KEY_USB_DEBUG, enabled).apply()
        _usbDebugEnabled.value = enabled
        powerOnlyLogged = false
        logger.info("Desk Pod USB debug link turned ${if (enabled) "on" else "off"}")
        setAttachActivityEnabled(enabled)
        if (!enabled) {
            // Clear the USB link before Bluetooth starts, so the arbiter and status never see both.
            val closing = synchronized(this) { link.also { link = null } }
            if (closing != null) {
                closing.close("USB debug link turned off")
                model.detach(closing.client, "USB debug link turned off")
                lastUsbCloseMs = System.currentTimeMillis()
            }
        }
        applyArbiter(arbiter.update(usbDebugEnabled = enabled))
        if (enabled && usbHost) scanAttached()
        publish()
    }

    /** Keeps Android from offering RID2Caltopo (and granting it the port) on every plug-in while USB debug is off. */
    private fun setAttachActivityEnabled(enabled: Boolean) {
        runCatching {
            appContext.packageManager.setComponentEnabledSetting(
                ComponentName(appContext, BtrxUsbAttachActivity::class.java),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { logger.warn("USB attach activity not ${if (enabled) "enabled" else "disabled"}: ${it.message}") }
    }

    /** Forgets the remembered Desk Pod so the next connection scans for any Desk Pod. */
    fun forgetDeskPod() = worker.execute {
        prefs().edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
        logger.info("Desk Pod forgotten; the next Bluetooth connection scans for any Desk Pod")
        if (arbiter.active == BtrxLinkArbiter.Link.BLUETOOTH) {
            ble?.stop("Desk Pod forgotten")
            ble?.start()
        }
        publish()
    }

    fun forgetPairedPod() = worker.execute {
        prefs().edit().remove(KEY_PAIRED).apply()
        model.forgetPairedPod()
        publish()
    }

    fun startPairing() = worker.execute { model.startPairing(); publish() }
    fun confirmPod() = worker.execute { model.confirmPod(); publish() }
    fun cancelPairing() = worker.execute { model.cancelPairing(); publish() }
    fun clearPairingResult() = worker.execute { model.clearPairingResult(); publish() }
    /** "Mute" on the stream-view steering overlay. */
    fun muteSteeringSource() = worker.execute { model.muteSteeringSource(); publish() }
    /** "Clear mutes" on the Desk Pod screen. */
    fun clearSteeringMutes() = worker.execute { model.clearSteeringMutes(); publish() }
    /** Drone Pod IMU calibration (Desk Pod screen; design doc section 29.3). */
    fun startImuCal() = worker.execute { model.startImuCal(); publish() }
    fun startImuCalStarboard() = worker.execute { model.startImuCalStarboard(); publish() }
    fun checkImuCal() = worker.execute { model.checkImuCal(); publish() }
    fun clearImuCal() = worker.execute { model.clearImuCal(); publish() }
    fun cancelImuCal() = worker.execute { model.cancelImuCal(); publish() }
    fun closeImuCalResult() = worker.execute { model.closeImuCalResult(); publish() }
    /** Debug: steering roll correction (off until the bench roll check). */
    fun setSteeringRollCorrection(on: Boolean) = worker.execute { model.setSteeringRollCorrection(on); publish() }

    private val _steeringStream = MutableStateFlow<String?>(null)
    /** The stream the operator marked "Drone Pod is on this drone" (see [BtrxSteeringTarget]). */
    val steeringStream: StateFlow<String?> = _steeringStream.asStateFlow()

    fun chooseSteeringStream(designator: String?) {
        if (_steeringStream.value != designator) logger.info("steering: overlay drone ${designator ?: "cleared"}")
        _steeringStream.value = designator
    }

    /** Live, non-playback stream designators (the tiles that can carry the overlay). */
    fun liveSteeringStreams(): Set<String> = org.ncssar.rid2caltopo.video.StreamRegistry.streams.value
        .filterValues { it.state == org.ncssar.rid2caltopo.video.StreamState.LIVE && !it.isLocalPlayback }.keys

    private fun retainSteeringStream() {
        val chosen = _steeringStream.value ?: return
        if (BtrxSteeringTarget.retained(chosen, liveSteeringStreams()) == null) {
            logger.info("steering: stream $chosen ended; overlay drone choice cleared")
            _steeringStream.value = null
        }
    }

    fun rememberedDeskPodName(): String? = if (installed) prefs().getString(KEY_NAME, null) else null

    // ---- link arbitration ----

    private fun publish() {
        _snapshot.value = model.snapshot()
    }

    private fun applyArbiter(actions: List<BtrxLinkArbiter.Action>) {
        for (a in actions) when (a) {
            BtrxLinkArbiter.Action.STOP_BLUETOOTH -> ble?.stop(
                "USB or off",
                if (arbiter.bluetoothPausedForUsb) LinkPhase.PAUSED_FOR_USB else LinkPhase.OFF,
            )
            BtrxLinkArbiter.Action.START_BLUETOOTH -> bleLink().start(if (link != null || lastUsbCloseMs > 0) BLE_RESUME_AFTER_USB_MS else 0)
            BtrxLinkArbiter.Action.CLOSE_USB, BtrxLinkArbiter.Action.OPEN_USB -> Unit // USB opens and closes with the cable
        }
        updateLinkStatus()
    }

    @Volatile private var lastUsbCloseMs = 0L

    private fun updateLinkStatus() {
        val usb = link
        val status = when {
            usb != null -> LinkStatus(LinkKind.USB, if (usb.client.state == BtrxClient.State.READY) LinkPhase.READY else LinkPhase.CONFIGURING, "Attached by USB.")
            arbiter.active == BtrxLinkArbiter.Link.BLUETOOTH -> bleStatus
            else -> LinkStatus(LinkKind.NONE, LinkPhase.OFF, "Bluetooth link is off.")
        }
        model.setLink(status, arbiter.bluetoothPausedForUsb)
    }

    private fun bleLink(): BtrxBleLink = ble ?: BtrxBleLink(appContext, logger, object : BtrxBleLink.Events {
        override fun onStatus(phase: LinkPhase, detail: String, deviceName: String?) {
            bleStatus = LinkStatus(LinkKind.BLUETOOTH, phase, detail, deviceName ?: rememberedDeskPodName())
            updateLinkStatus()
            publish()
        }
        override val clientListener: BtrxClient.Listener = modelListener
        override fun onSessionStarted(client: BtrxClient) = model.attach(client)
        override fun onSessionEnded(client: BtrxClient, reason: String) = model.detach(client, reason)
        override fun rememberedAddress(): String? = prefs().getString(KEY_ADDRESS, null)
        override fun rememberDevice(address: String, name: String?) {
            prefs().edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
        }
    }).also { ble = it }

    // ---- USB ----

    /** Called by the USB attach activity, which Android opens when a Desk Pod is plugged in. */
    fun onDeviceAttached(context: Context, device: UsbDevice?) {
        install(context)
        if (installed && usbHost && isDeskPodCandidate(device)) worker.execute { connect(device!!) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (!isDeskPodCandidate(device)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> worker.execute { connect(device) }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> worker.execute { detached(device) }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    worker.execute {
                        synchronized(this@BtrxManager) { requested.remove(device.deviceName) }
                        if (granted) connect(device) else logger.warn("USB permission denied for ${BtrxUsbLink.describe(device)}")
                    }
                }
            }
        }
    }

    private fun usbManager(): UsbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private fun scanAttached() {
        usbManager().deviceList.values.filter(::isDeskPodCandidate).forEach(::connect)
    }

    private fun connect(device: UsbDevice) {
        if (!_usbDebugEnabled.value) {
            if (!powerOnlyLogged) {
                powerOnlyLogged = true
                logger.info("Desk Pod on USB (${BtrxUsbLink.describe(device)}) left alone: USB debug link is off, so it is power only")
            }
            return
        }
        synchronized(this) {
            if (link != null || device.deviceName in rejected) return
        }
        val usb = usbManager()
        if (!usb.hasPermission(device)) {
            synchronized(this) { if (!requested.add(device.deviceName)) return }
            logger.info("requesting USB permission for ${BtrxUsbLink.describe(device)}")
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(device, PendingIntent.getBroadcast(appContext, 0, intent, flags))
            return
        }
        // Bluetooth must be closed before USB opens: never both links at once.
        applyArbiter(arbiter.update(usbAttached = true))
        val newLink = BtrxUsbLink(usb, device, logger, modelListener) { closedLink, reason ->
            worker.execute { usbClosed(closedLink, reason) }
        }
        synchronized(this) { link = newLink }
        model.attach(newLink.client)
        updateLinkStatus()
        try {
            newLink.open()
        } catch (e: Exception) {
            logger.warn("USB open failed for ${BtrxUsbLink.describe(device)}: ${e.message}")
            newLink.close("open failed")
        }
        publish()
    }

    private fun usbClosed(closedLink: BtrxUsbLink, reason: String) {
        synchronized(this) {
            if (link === closedLink) link = null
            if (reason.startsWith("not a Desk Pod")) rejected += closedLink.device.deviceName
        }
        model.detach(closedLink.client, "USB $reason")
        lastUsbCloseMs = System.currentTimeMillis()
        if (link == null) applyArbiter(arbiter.update(usbAttached = false))
        publish()
    }

    private fun detached(device: UsbDevice) {
        synchronized(this) {
            rejected.remove(device.deviceName)
            requested.remove(device.deviceName)
        }
        val current = link
        if (current != null && current.device.deviceName == device.deviceName) current.close("detached")
    }

    // ---- persistence ----

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun savePairedPod(pod: BtrxDeskPodModel.PairedPod) {
        prefs().edit().putString(KEY_PAIRED, "%08x,%08x,%08x,%08x".format(pod.node, pod.podId, pod.devA, pod.devB)).apply()
    }

    private fun loadPairedPod(): BtrxDeskPodModel.PairedPod? {
        val parts = prefs().getString(KEY_PAIRED, null)?.split(",") ?: return null
        if (parts.size != 4) return null
        return runCatching {
            val v = parts.map { it.toLong(16) }
            BtrxDeskPodModel.PairedPod(v[0], v[1], v[2], v[3])
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    internal fun deviceFrom(intent: Intent): UsbDevice? = intent.usbDevice()
}

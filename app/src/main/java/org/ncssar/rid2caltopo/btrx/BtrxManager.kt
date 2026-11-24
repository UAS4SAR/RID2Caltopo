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
    /** src ids the paired Drone Pod saved on its ignore list (build 0x0106), hex, comma separated. */
    private const val KEY_SAVED_IGNORES = "paired_pod_saved_ignores"
    private const val KEY_USB_DEBUG = "usb_debug"
    private const val KEY_OVERLAY_SHOWN = "steer_overlay_shown"
    /** "Talking": a Desk Pod frame within this long (STEER arrives about once a second). */
    private const val KEY_BEARING_FADE_S = "bearing_fade_total_s"
    private const val KEY_BEARING_FADE_P = "bearing_fade_shape_p"
    private const val KEY_BEARING_LAG_TRACE = "bearing_lag_trace"
    private const val KEY_BEARING_MAX_PM = "bearing_max_plus_minus_m"
    private const val KEY_BEARING_MIN_REPORTS = "bearing_min_reports"
    private const val KEY_BEARING_LAUNCH_MUTE = "bearing_launch_auto_mute"
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

    init {
        BtrxYawLog.sink = { logger.debug(it) }
    }

    val model = BtrxDeskPodModel(
        logger,
        onPaired = { pod -> worker.execute { savePairedPod(pod) } },
        onSavedIgnores = { ids -> worker.execute { saveSavedIgnores(ids) } },
    )

    private val _snapshot = MutableStateFlow(model.snapshot())
    /** Desk Pod screen state, refreshed about once a second while a link or Bluetooth is on. */
    val snapshot: StateFlow<BtrxDeskPodModel.Snapshot> = _snapshot.asStateFlow()

    private val _overlayShown = MutableStateFlow(true)
    /** Steering overlay card shown on the stream tile (hide/show; persisted). */
    val overlayShown: StateFlow<Boolean> = _overlayShown.asStateFlow()
    private val _bluetoothEnabled = MutableStateFlow(false)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _usbDebugEnabled = MutableStateFlow(false)
    /** "Use USB link (debug)": off by default; a plugged-in Desk Pod is then power only. */
    val usbDebugEnabled: StateFlow<Boolean> = _usbDebugEnabled.asStateFlow()
    @Volatile private var powerOnlyLogged = false

    // ---- bearing map layer (Developer Tools; docs/btrx/BEARING-LAYER-spec.md) ----

    /** The BTRX bearing layer. On whenever BTRX is enabled (Nov 23); only the worker starts, ticks and stops it. */
    val bearing = BtrxBearingLayer(
        engine = BtrxBearingNative,
        monoUs = { android.os.SystemClock.elapsedRealtimeNanos() / 1000L },
        // Debug level: the session log drops INFO lines at the default level, so the
        // Nov 13 field test left no trace of the layer at all.
        log = { logger.debug(it) },
        traceLog = { logger.debug(it) },
    )
    private val _bearingOn = MutableStateFlow(false)
    /**
     * "Show bearing layer on map" (Developer Tools > BTRX). Ken, Nov 23: on by default, turned on
     * whenever BTRX is enabled (at launch with a link on, and when a link is turned on); the
     * switch still turns it off for the session. Not persisted, so no install keeps it off.
     */
    val bearingOn: StateFlow<Boolean> = _bearingOn.asStateFlow()
    private val _bearingSettings = MutableStateFlow(BtrxBearingSettings())
    val bearingSettings: StateFlow<BtrxBearingSettings> = _bearingSettings.asStateFlow()
    private val _bearingDisplay = MutableStateFlow(BtrxBearingDisplay())
    /** Map thresholds and launch auto mute (Developer Tools > BTRX, Nov 20). */
    val bearingDisplay: StateFlow<BtrxBearingDisplay> = _bearingDisplay.asStateFlow()
    private val _bearingLagTrace = MutableStateFlow(false)
    val bearingLagTrace: StateFlow<Boolean> = _bearingLagTrace.asStateFlow()
    private var bearingTick: java.util.concurrent.ScheduledFuture<*>? = null
    /** RID track points of every drone; the layer keeps only the "Drone Pod is on this drone" stream. */
    private val bearingTrackListener = org.ncssar.rid2caltopo.data.CaltopoLiveTrack.LocalTrackListener { _, mappedId, lat, lng, _, timestampMsec ->
        bearing.onPosition(mappedId, lat, lng, timestampMsec)
    }

    fun setBearingOn(on: Boolean) {
        _bearingOn.value = on
        worker.execute { applyBearingOn(on) }
    }

    /** Worker: BTRX enabled, so the bearing layer goes on (unless it already is). */
    private fun autoBearingOn(reason: String) {
        if (!BtrxBearingAutoOn.shouldTurnOn(btrxEnabled = true, bearingOn = _bearingOn.value)) return
        logger.info("bearing layer on: $reason")
        _bearingOn.value = true
        applyBearingOn(true)
    }

    private fun applyBearingOn(on: Boolean) {
        if (on) {
            updateBearingSteering()
            val linkUp = _snapshot.value.link.phase == LinkPhase.READY
            if (!bearing.start(_bearingSettings.value, _bearingLagTrace.value, linkUp)) {
                _bearingOn.value = false
                return
            }
            BtrxBearingFeed.sink = bearing
            org.ncssar.rid2caltopo.data.CaltopoLiveTrack.AddLocalTrackListener(bearingTrackListener)
            if (bearingTick == null) {
                bearingTick = worker.scheduleWithFixedDelay({
                    try {
                        updateBearingSteering()
                        bearing.tick { pingSeq, t1Ms -> model.sendTimePing(pingSeq, t1Ms) }
                    } catch (e: Exception) {
                        logger.warn("bearing: tick failed: ${e.message}")
                    }
                }, BtrxBearingLayer.TICK_MS, BtrxBearingLayer.TICK_MS, TimeUnit.MILLISECONDS)
            }
        } else {
            bearingTick?.cancel(false)
            bearingTick = null
            BtrxBearingFeed.sink = null
            org.ncssar.rid2caltopo.data.CaltopoLiveTrack.RemoveLocalTrackListener(bearingTrackListener)
            bearing.stop()
        }
    }

    /**
     * The bearing layer's pose stream: the "Drone Pod is on this drone" choice, else the
     * only live stream (the overlay's own rule, [BtrxSteeringTarget.effective]).
     */
    private fun updateBearingSteering() {
        val live = liveSteeringStreams()
        bearing.liveStreamCount = live.size
        val effective = BtrxSteeringTarget.effective(_steeringStream.value, live)
        if (bearing.steeringDesignator != effective) logger.debug("bearing: pose stream ${effective ?: "none"}")
        bearing.steeringDesignator = effective
    }

    // ---- map double tap opens the Desk Pod screen (Ken, Nov 13) ----

    /** Last live frame from the paired Drone Pod ([BtrxMapStatus.isDronePodReport]); 0 = none. */
    @Volatile private var lastDronePodReportMs = 0L
    private val _deskPodScreenRequests = MutableStateFlow(0)
    /** Increments when the map asks for the Desk Pod screen (double tap while it is talking). */
    val deskPodScreenRequests: StateFlow<Int> = _deskPodScreenRequests.asStateFlow()

    /**
     * The Desk Pod link is READY. (Until Nov 16 this also needed a Desk Pod frame within 10 s,
     * which flapped with no Drone Pod reporting: the Desk Pod's own HEALTH comes every 30 s.)
     */
    fun deskPodTalking(): Boolean = _snapshot.value.link.phase == LinkPhase.READY

    /** Map double tap: opens the Desk Pod screen and returns true only while it is talking. */
    fun openDeskPodScreenFromMap(): Boolean {
        if (!deskPodTalking()) return false
        logger.debug("map double tap: Desk Pod screen")
        _deskPodScreenRequests.value = _deskPodScreenRequests.value + 1
        return true
    }

    /** The BTRX status chip at the top of the map: opens the Desk Pod screen whatever the link state. */
    fun openDeskPodScreenFromStatus() {
        logger.debug("map status tap: Desk Pod screen")
        _deskPodScreenRequests.value = _deskPodScreenRequests.value + 1
    }

    // ---- BTRX status chip at the top of the map (Ken, Nov 16) ----

    private val _mapStatus = MutableStateFlow<BtrxMapStatus.Status?>(null)
    /** "BTRX: Active" / "BTRX: Inactive - <reason>"; null (no chip) unless paired and READY this session or a user connect is under two minutes old. */
    val mapStatus: StateFlow<BtrxMapStatus.Status?> = _mapStatus.asStateFlow()
    private val mapStatusLock = Any()
    private val chipSession = BtrxMapStatus.Session()
    private fun requestChipConnect() = chipSession.request(android.os.SystemClock.elapsedRealtime())
    private fun forgetChipSession() { chipSession.forget(); BtrxRadioCoordinator.forgetSession() }

    private fun updateMapStatus() {
        if (!installed) return
        val snap = _snapshot.value
        chipSession.observe(snap.link.phase == LinkPhase.READY && !snap.bluetoothPausedForUsb)
        val setUp = BtrxMapStatus.setUp(
            bluetoothLinkEnabled = _bluetoothEnabled.value,
            deskPodRemembered = prefs().getString(KEY_ADDRESS, null) != null,
            usbLink = link != null,
            pausedForUsb = snap.bluetoothPausedForUsb,
        )
        val next = BtrxMapStatus.status(
            setUp, snap.link, setUp && bluetoothOff(), snap.bluetoothPausedForUsb,
            BtrxMapStatus.dronePodReporting(lastDronePodReportMs, android.os.SystemClock.elapsedRealtime()),
            deskPodSilent = snap.deskSilent,
            deskPodNotice = snap.deskRestartNotice,
            linkEnabled = _bluetoothEnabled.value || link != null,
            readyEarlier = chipSession.readyEarlier,
            requestedAtMs = chipSession.requestedAtMs,
            nowMs = android.os.SystemClock.elapsedRealtime(),
            deskPodPowerOk = snap.deskSilentPowerOk,
        )
        synchronized(mapStatusLock) {
            if (next == _mapStatus.value) return
            _mapStatus.value = next
        }
        logger.debug("map status: ${next?.text ?: "hidden"}")
    }

    private fun bluetoothOff(): Boolean = runCatching {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.isEnabled == false
    }.getOrDefault(false)

    /** Fade time and shape sliders: applied at once (next tick) and saved. */
    fun setBearingSettings(settings: BtrxBearingSettings) {
        val s = settings.clamped()
        _bearingSettings.value = s
        worker.execute {
            bearing.setFade(s)
            prefs().edit().putFloat(KEY_BEARING_FADE_S, s.fadeTotalS).putFloat(KEY_BEARING_FADE_P, s.fadeShapeP).apply()
        }
    }

    /** Display thresholds and launch auto mute: applied at once and saved. */
    fun setBearingDisplay(display: BtrxBearingDisplay) {
        val d = display.clamped()
        _bearingDisplay.value = d
        worker.execute {
            bearing.setLaunchAutoMute(d.launchAutoMute)
            prefs().edit().putInt(KEY_BEARING_MAX_PM, d.maxPlusMinusM).putInt(KEY_BEARING_MIN_REPORTS, d.minReports)
                .putBoolean(KEY_BEARING_LAUNCH_MUTE, d.launchAutoMute).apply()
        }
    }

    /** Lag trace (BBv1/BBLAG/BBRND lines in the session log); restarts a running layer. */
    fun setBearingLagTrace(on: Boolean) {
        _bearingLagTrace.value = on
        worker.execute {
            prefs().edit().putBoolean(KEY_BEARING_LAG_TRACE, on).apply()
            if (bearing.isRunning) { applyBearingOn(false); applyBearingOn(true) }
        }
    }

    private val modelListener = object : BtrxClient.Listener {
        override fun onConfigured(localNode: Long) {
            model.onConfigured(localNode)
            worker.execute { bearing.onConnected() }
        }
        override fun onLocalRoleReport(report: BtrxProtocol.RoleReport) = model.onLocalRoleReport(report)
        override fun onPacketDropped(encrypted: Boolean) = model.onPacketDropped(encrypted)
        override fun onDeskDeviceMetrics(metrics: MeshtasticSerial.DeviceMetrics, source: String) = model.onDeskDeviceMetrics(metrics, source)
        override fun onFrame(frame: BtrxProtocol.Frame, meta: BtrxClient.RadioMeta) {
            val origin = model.bearingOrigin(frame, meta)
            if (BtrxMapStatus.isDronePodReport(frame.type, meta.backlog, origin == BtrxDeskPodModel.BEARING_FROM_POD)) {
                lastDronePodReportMs = android.os.SystemClock.elapsedRealtime()
            }
            model.onFrame(frame, meta)
            // The overlay follows each STEER (about every second), not only the 1 s tick.
            if (frame.type == BtrxProtocol.STEER && !meta.backlog) worker.execute { publish() }
            if (bearing.isRunning && (frame.type == BtrxProtocol.STEER || frame.type == BtrxProtocol.TIME_PONG || frame.type == BtrxProtocol.HEALTH)) {
                if (origin != BtrxDeskPodModel.BEARING_FROM_OTHER) worker.execute {
                    bearing.onFrame(frame, meta, origin == BtrxDeskPodModel.BEARING_FROM_POD, origin == BtrxDeskPodModel.BEARING_FROM_DESK)
                }
            }
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
            model.restorePairedPod(loadPairedPod(), loadSavedIgnores())
            val enabled = p.getBoolean(KEY_BLE_ENABLED, false)
            _bluetoothEnabled.value = enabled
            val usbDebug = p.getBoolean(KEY_USB_DEBUG, false)
            _usbDebugEnabled.value = usbDebug
            _overlayShown.value = p.getBoolean(KEY_OVERLAY_SHOWN, true)
            _bearingSettings.value = BtrxBearingSettings(
                p.getFloat(KEY_BEARING_FADE_S, BtrxBearingSettings.DEFAULT_TOTAL_S),
                p.getFloat(KEY_BEARING_FADE_P, BtrxBearingSettings.DEFAULT_SHAPE_P),
            ).clamped()
            _bearingLagTrace.value = p.getBoolean(KEY_BEARING_LAG_TRACE, false)
            _bearingDisplay.value = BtrxBearingDisplay(
                p.getInt(KEY_BEARING_MAX_PM, BtrxBearingDisplay.DEFAULT_MAX_PLUS_MINUS_M),
                p.getInt(KEY_BEARING_MIN_REPORTS, BtrxBearingDisplay.DEFAULT_MIN_REPORTS),
                p.getBoolean(KEY_BEARING_LAUNCH_MUTE, true),
            ).clamped()
            bearing.setLaunchAutoMute(_bearingDisplay.value.launchAutoMute)
            setAttachActivityEnabled(usbDebug)
            if (usbHost && usbDebug) scanAttached()
            applyArbiter(arbiter.update(bluetoothEnabled = enabled, usbDebugEnabled = usbDebug))
            if (enabled || usbDebug) autoBearingOn("BTRX enabled at launch")
            publish()
        }
        worker.scheduleWithFixedDelay({
            try { model.tick(); updateLinkStatus(); retainSteeringStream(); publish() } catch (e: Exception) { logger.warn("Desk Pod tick failed: ${e.message}") }
        }, 1, 1, TimeUnit.SECONDS)
    }

    // ---- Desk Pod screen actions (post to the worker; never block the caller) ----

    /**
     * Hide or show the steering overlay card on the stream tile. Only the card: the link,
     * STEER and DETECT handling, mutes and the Steering section keep working. Persisted.
     */
    fun setOverlayShown(shown: Boolean) {
        _overlayShown.value = shown
        worker.execute {
            prefs().edit().putBoolean(KEY_OVERLAY_SHOWN, shown).apply()
            logger.info("steering overlay ${if (shown) "shown" else "hidden"} (link, STEER and mutes unaffected)")
        }
    }

    fun setBluetoothEnabled(enabled: Boolean) = worker.execute {
        prefs().edit().putBoolean(KEY_BLE_ENABLED, enabled).apply()
        _bluetoothEnabled.value = enabled
        if (enabled) requestChipConnect() else forgetChipSession()
        logger.info("Desk Pod Bluetooth link turned ${if (enabled) "on" else "off"}")
        applyArbiter(arbiter.update(bluetoothEnabled = enabled))
        if (enabled) autoBearingOn("Desk Pod Bluetooth link turned on")
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
        if (enabled) autoBearingOn("Desk Pod USB debug link turned on")
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

    /**
     * "Replace Desk Pod" after its confirmation: forgets the remembered Desk Pod so the next
     * connection scans for any Desk Pod. One app uses one Desk Pod at a time.
     */
    fun forgetDeskPod() = worker.execute {
        prefs().edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
        forgetChipSession()
        logger.info("Desk Pod forgotten; the next Bluetooth connection scans for any Desk Pod")
        if (arbiter.active == BtrxLinkArbiter.Link.BLUETOOTH) {
            ble?.stop("Desk Pod forgotten")
            ble?.start()
        }
        publish()
    }

    fun forgetPairedPod() = worker.execute {
        prefs().edit().remove(KEY_PAIRED).remove(KEY_SAVED_IGNORES).apply()
        model.forgetPairedPod()
        publish()
    }

    fun startPairing() = worker.execute { requestChipConnect(); model.startPairing(); publish() }
    /** "Replace Drone Pod" after its confirmation: unpair the current pod here, then pair. */
    fun replacePairedPod() = worker.execute {
        prefs().edit().remove(KEY_PAIRED).remove(KEY_SAVED_IGNORES).apply()
        requestChipConnect()
        model.replacePairedPod()
        publish()
    }
    /** "Re-pair Drone Pod" after its confirmation: PAIR_OPEN plus the Desk Pod window (build 0x0106). */
    fun repairDronePod() = worker.execute { requestChipConnect(); model.repairDronePod(); publish() }
    /** Desk Pod button panel (firmware 0x0108): Confirm / Cancel. */
    fun answerButtonConfirm(confirm: Boolean) = worker.execute { model.answerButtonConfirm(confirm); publish() }
    /** The button panel host is on screen (app in front): keepalive and "shown" go out only then. */
    fun setButtonPanelAvailable(available: Boolean) = worker.execute { model.setButtonPanelAvailable(available); publish() }
    /** "Own aircraft (saved)" / "Remove" on a source (STEER_MUTE op 3 / 4, build 0x0106). */
    fun ignoreSourceSaved(srcId: Int) = worker.execute { model.ignoreSourceSaved(srcId); publish() }
    /** "Ignore all except this" (Nov 17): the planned keys ignored here, the planned src_ids saved on the pod; on the worker. */
    fun ignoreAllExcept(plan: BtrxIgnoreAllPlan) = worker.execute {
        bearing.applyIgnoreAll(plan); model.ignoreAllExcept(plan.podSrcIds, plan.keepId, plan.count); publish()
    }
    /** "Restore ignored": undoes only what "Ignore all except this" ignored, here and on the pod; on the worker. */
    fun restoreIgnoreAll() = worker.execute { bearing.restoreIgnoreAll(); model.restoreIgnoreAll(); publish() }
    fun unignoreSourceSaved(srcId: Int) = worker.execute { model.unignoreSourceSaved(srcId); publish() }
    fun confirmPod() = worker.execute { requestChipConnect(); model.confirmPod(); publish() }
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
        bearing.steeringDesignator = BtrxSteeringTarget.effective(designator, liveSteeringStreams())
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
        updateMapStatus()
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
        override fun onStatus(phase: LinkPhase, detail: String, deviceName: String?, failedAttempts: Int, lastFailure: String?) {
            bleStatus = LinkStatus(LinkKind.BLUETOOTH, phase, detail, deviceName ?: rememberedDeskPodName(), failedAttempts, lastFailure)
            updateLinkStatus()
            publish()
        }
        override val clientListener: BtrxClient.Listener = modelListener
        override fun onSessionStarted(client: BtrxClient) = model.attach(client)
        override fun onSessionEnded(client: BtrxClient, reason: String) = model.detach(client, reason)
        override fun rememberedAddress(): String? = prefs().getString(KEY_ADDRESS, null)
        override fun rememberDevice(address: String, name: String?) {
            if (prefs().getString(KEY_ADDRESS, null) != address) requestChipConnect()
            prefs().edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
        }
    }).also { ble = it }

    // ---- USB ----

    /** Called by the USB attach activity, which Android opens when a Desk Pod is plugged in. */
    fun onDeviceAttached(context: Context, device: UsbDevice?) {
        install(context)
        if (installed && usbHost && isDeskPodCandidate(device)) worker.execute { requestChipConnect(); connect(device!!) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (!isDeskPodCandidate(device)) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> worker.execute { requestChipConnect(); connect(device) }
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

    private fun saveSavedIgnores(ids: List<Int>) {
        val e = prefs().edit()
        if (ids.isEmpty()) e.remove(KEY_SAVED_IGNORES) else e.putString(KEY_SAVED_IGNORES, ids.joinToString(",") { "%04x".format(it) })
        e.apply()
    }

    private fun loadSavedIgnores(): List<Int> =
        prefs().getString(KEY_SAVED_IGNORES, null)?.split(",")?.mapNotNull { it.toIntOrNull(16) }?.filter { it in 1..0xFFFF } ?: emptyList()

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

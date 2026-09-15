import org.ncssar.rid2caltopo.video.StreamFlightActivityRegistry
import org.ncssar.rid2caltopo.video.surface.MeasurementStatus
import org.ncssar.rid2caltopo.video.surface.AolState
import org.ncssar.rid2caltopo.video.surface.SurfaceStore
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import android.content.Context
import android.location.Location
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebugEnabled
import org.ncssar.rid2caltopo.video.MINIMUM_TRAVEL_BEARING_DISPLACEMENT_METERS
import org.ncssar.rid2caltopo.video.autoDownloadBestDemForLocation
import org.ncssar.rid2caltopo.video.demPrefetchCellKey
import org.ncssar.rid2caltopo.video.TerrainPrefetchGate
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import androidx.compose.runtime.snapshotFlow
import java.util.concurrent.TimeUnit

/**
 * Computes AGL, ATO, and heading for all tracked drones, combining RID position/telemetry
 * with DEM ground-elevation lookups.
 *
 * Owned by [StreamsViewModel] — survives MapPane/StreamTile navigation and screen rotation.
 *
 * Consumer-gated: the [CaltopoLiveTrack.LocalTrackListener] and the DEM/display-state update
 * loop are only active while [consumerCount] > 0.  When no UI is displaying these values the
 * coordinator idles, avoiding unnecessary DEM polling and listener overhead.  All cached state
 * (DEM samples, calibration, correction factors, last-known headings) is preserved across
 * attach/detach cycles, so a consumer that re-attaches immediately sees fresh-looking values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DroneAltitudeCoordinator(
    private val scope: CoroutineScope,
    appContext: Context,
    /** Live reference to the ViewModel's snapshot state map — observed reactively. */
    private val droneStates: Map<String, DroneSpecState>,
) {
    private val aolWorkGate = org.ncssar.rid2caltopo.video.surface.SurfaceWorkGate()
    private val aolDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { work ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            work.run()
        }, "Prepared AOL").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val aolLoggedReasons = HashMap<String, String>()
    private val manualAolFlight = HashMap<String,Long>()
    private val aolAnchors = HashMap<String, Pair<Double, Double>>()
    private val aolVideoReferences = HashMap<String, org.ncssar.rid2caltopo.video.surface.AolVideoReferenceContinuity>()
    private val aolResults = HashMap<String, Pair<String, AolState>>()
    private val aolRequests = HashMap<String, String>()
    private val aolRefreshWindows = HashMap<String, org.ncssar.rid2caltopo.video.surface.AolRefreshWindow>()
    private val aolIdentity = HashMap<String, String>()
    private val aolReceived = HashMap<String, Long>()
    private val tag = "DroneAltCoord"
    private val applicationContext = appContext.applicationContext

    // ── Public DEM service (MapPane still needs it for tile downloads, offline prep, stats) ─
    val demElevationService = DemElevationService(applicationContext)
    private val terrainPrefetchGate = TerrainPrefetchGate()
    private val terrainPrefetchMutex = Mutex()
    private val terrainPrefetchClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    // ── Output: read by ViewModel / MapPane / StreamTile ────────────────────────────────────
    val displayStateByDesignator = mutableStateMapOf<String, DroneDisplayState>()

    // ── Calibration: SnapshotState so MapPane/StreamTile recompose when it changes ──────────
    val calibrationByRemoteId = mutableStateMapOf<String, DroneAltitudeCalibration>()

    // ── DEM state — keyed by remoteId (stable across mappedId / designator changes) ─────────
    private val demGroundByRemoteId     = HashMap<String, DroneAglState>()
    private val demKeyByRemoteId        = HashMap<String, String>()
    private val takeoffDemGroundByRemoteId = HashMap<String, Double>()
    private val demCorrectionByRemoteId = HashMap<String, Double>()
    private val demScaleToMetersByRemoteId = HashMap<String, Double>()
    private val demPending              = HashSet<String>()
    private val demCorrectionPending    = HashSet<String>()
    private val demLastAttemptMs        = HashMap<String, Long>()
    private val latestLocalPointByDesignator = HashMap<String, LocalAltitudePoint>()
    private val flightStartByRemoteId   = HashMap<String, Long>()

    // ── Heading state — keyed by designator (mappedId) ──────────────────────────────────────
    private val telemetryHeading      = HashMap<String, Double>()
    private val fallbackHeading       = HashMap<String, Double>()
    private val fallbackHeadingAnchor = HashMap<String, Pair<Double, Double>>() // lat, lng

    // ── Consumer gate ────────────────────────────────────────────────────────────────────────
    private val _consumerCount = MutableStateFlow(0)

    /**
     * Register a UI consumer.  Returns a cleanup lambda; call it from [DisposableEffect]'s
     * [onDispose] block.  The first consumer activates the update loop and LocalTrackListener;
     * the last consumer deactivates them.
     */
    fun addConsumer(): () -> Unit {
        _consumerCount.value += 1
        return { _consumerCount.value -= 1 }
    }

    // ── LocalTrackListener: heading + calibration seeding + DEM refresh ─────────────────────
    private val localTrackListener = CaltopoLiveTrack.LocalTrackListener {
        _, mappedId, lat, lng, altitudeMeters, _ ->
        if (!lat.isFinite() || !lng.isFinite()) return@LocalTrackListener
        if (lat == 0.0 && lng == 0.0) return@LocalTrackListener
        scope.launch(Dispatchers.Main.immediate) {
            aolIdentity[mappedId] = "${droneStates[mappedId]?.remoteId}|${droneStates[mappedId]?.flightStartMsec}"
            aolReceived[mappedId] = System.currentTimeMillis()
            latestLocalPointByDesignator[mappedId] = LocalAltitudePoint(lat, lng, altitudeMeters)
            updateHeading(mappedId, lat, lng)
            updateCalibration(mappedId, lat, lng, altitudeMeters)
            // Schedule DEM fetch using the fresh lat/lng from this position update.
            // The snapshotFlow loop only fires on drone add/remove, not on lat/lng changes,
            // so this is the primary DEM refresh path for in-flight position updates and
            // for re-fetching after onMapReconnect() clears the DEM cache.
            val remoteId = droneStates[mappedId]?.remoteId
            if (remoteId != null) {
                resetAutoAltitudeStateForNewFlight(
                    remoteId = remoteId,
                    designator = mappedId,
                    flightStartMsec = droneStates[mappedId]?.flightStartMsec ?: 0L,
                )
                scheduleDemIfNeeded(remoteId, mappedId, lat, lng, System.currentTimeMillis())
            }
            recomputeDisplayState(mappedId)
        }
    }

    init {
        scope.launch {
            while (true) {
                delay(1000)
                if (_consumerCount.value > 0) droneStates.keys.toList().forEach { recomputeDisplayState(it) }
            }
        }
        // Prewarm DEM cache proactively at ViewModel creation — same work MapPane used to do,
        // now done once regardless of which view is open first.
        scope.launch(Dispatchers.IO) {
            try { demElevationService.prewarm() } catch (_: Exception) { }
        }

        // Register / unregister LocalTrackListener on the 0 ↔ 1 consumer-count boundary.
        scope.launch {
            _consumerCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        CaltopoLiveTrack.AddLocalTrackListener(localTrackListener)
                        if (CTDebugEnabled(tag)) CTDebug(tag, "LocalTrackListener registered")
                    } else {
                        CaltopoLiveTrack.RemoveLocalTrackListener(localTrackListener)
                        if (CTDebugEnabled(tag)) CTDebug(tag, "LocalTrackListener unregistered")
                    }
                }
        }

        // Position-change loop: handles drone add/remove and initial DEM scheduling.
        // snapshotFlow fires when the _droneStates map structure changes (drone added or removed).
        // Per-position DEM refresh is handled by the localTrackListener path above, which has
        // access to the fresh lat/lng coordinates on every position update.
        scope.launch {
            _consumerCount
                .map { it > 0 }
                .distinctUntilChanged()
                .flatMapLatest { active ->
                    if (active) snapshotFlow { droneStates.values.toList() }
                    else emptyFlow()
                }
                .collect { states ->
                    val nowMs = System.currentTimeMillis()
                    val activeDesignators = states.map { it.mappedId }.toSet()

                    // Prune heading maps for drones that have gone away.
                    aolRequests.keys.filter { it !in activeDesignators }.forEach { aolWorkGate.forget(it) }
                    aolRequests.keys.retainAll(activeDesignators)
                    aolResults.keys.retainAll(activeDesignators)
                    aolRefreshWindows.keys.retainAll(activeDesignators)
                    aolLoggedReasons.keys.retainAll(activeDesignators)
                    aolReceived.keys.retainAll(activeDesignators)
                    aolIdentity.keys.retainAll(activeDesignators)
                    telemetryHeading.keys.retainAll(activeDesignators)
                    fallbackHeading.keys.retainAll(activeDesignators)
                    fallbackHeadingAnchor.keys.retainAll(activeDesignators)
                    latestLocalPointByDesignator.keys.retainAll(activeDesignators)
                    displayStateByDesignator.keys
                        .filter { it !in activeDesignators }
                        .forEach { displayStateByDesignator.remove(it) }
                    // Note: DEM + calibration maps are keyed by remoteId and are intentionally
                    // NOT pruned here — they should survive temporary drone drop-outs so that
                    // data is available immediately on reconnect.

                    states.forEach { state ->
                        resetAutoAltitudeStateForNewFlight(
                            remoteId = state.remoteId,
                            designator = state.mappedId,
                            flightStartMsec = state.flightStartMsec,
                        )
                        scheduleDemIfNeeded(state, nowMs)
                        recomputeDisplayState(state.mappedId)
                    }
                }
        }
    }

    fun close() { aolDispatcher.close() }

    // ── Heading ──────────────────────────────────────────────────────────────────────────────

    private fun updateHeading(designator: String, lat: Double, lng: Double) {
        // Telemetry heading: higher-priority source when the drone broadcasts it.
        val trackDeg = droneStates[designator]?.source?.lastPositionTelemetry?.aircraftTrackDeg
        if (trackDeg != null && trackDeg.isFinite()) {
            telemetryHeading[designator] = normalizeDegrees(trackDeg.toDouble())
        }
        // Fallback heading: bearing computed from movement between significant positions.
        val anchor = fallbackHeadingAnchor[designator]
        if (anchor != null) {
            val distAndBearing = FloatArray(2)
            Location.distanceBetween(anchor.first, anchor.second, lat, lng, distAndBearing)
            if (distAndBearing[0] >= MINIMUM_TRAVEL_BEARING_DISPLACEMENT_METERS) {
                fallbackHeading[designator] = normalizeDegrees(distAndBearing[1].toDouble())
                fallbackHeadingAnchor[designator] = Pair(lat, lng)
            }
        } else {
            fallbackHeadingAnchor[designator] = Pair(lat, lng)
        }
    }

    private fun resetAutoAltitudeStateForNewFlight(
        remoteId: String,
        designator: String,
        flightStartMsec: Long,
    ) {
        if (flightStartMsec <= 0L) return
        val priorFlightStartMsec = flightStartByRemoteId.put(remoteId, flightStartMsec)
        if (priorFlightStartMsec == null || priorFlightStartMsec == flightStartMsec) return
        aolAnchors.remove(remoteId)
        aolVideoReferences.remove(remoteId)
        manualAolFlight.remove(remoteId)
        if (!shouldResetAutoCalibrationForFlightChange(calibrationByRemoteId[remoteId])) return
        calibrationByRemoteId.remove(remoteId)
        demGroundByRemoteId.remove(remoteId)
        demKeyByRemoteId.remove(remoteId)
        takeoffDemGroundByRemoteId.remove(remoteId)
        demCorrectionByRemoteId.remove(remoteId)
        demScaleToMetersByRemoteId.remove(remoteId)
        demPending.remove(remoteId)
        demCorrectionPending.remove(remoteId)
        demLastAttemptMs.remove(remoteId)
        if (CTDebugEnabled(tag)) CTDebug(
            tag,
            "Altitude auto-calibration reset for new flight $designator: " +
                "remoteId=$remoteId priorStart=$priorFlightStartMsec newStart=$flightStartMsec"
        )
    }

    // ── Calibration (ATO auto-seed state machine) ────────────────────────────────────────────

    private fun updateCalibration(designator: String, lat: Double, lng: Double, altM: Double) {
        val remoteId = droneStates[designator]?.remoteId ?: return
        val existing = calibrationByRemoteId[remoteId]
        val seedSource = existing?.seedSource
        // AUTO_SEALED and MANUAL entries are locked; never touch them here.
        if (seedSource == AtoSeedSource.AUTO_SEALED || seedSource == AtoSeedSource.MANUAL) return

        val droneSpec = droneStates[designator]?.source
        val ridTakeoff = droneSpec?.getImpliedTakeoffAltM()
        val isSealed   = droneSpec?.isImpliedTakeoffAltSealed() ?: false

        when {
            ridTakeoff != null -> {
                val newSource = if (isSealed) AtoSeedSource.AUTO_SEALED else AtoSeedSource.AUTO
                calibrationByRemoteId[remoteId] = DroneAltitudeCalibration(ridTakeoff, newSource)

                // When EMA seals, refine the correction with the converged takeoff altitude,
                // but always retain the terrain sampled at takeoff. Using terrain under the
                // aircraft at seal time moves the reference point and creates abrupt AGL jumps.
                if (isSealed) {
                    val demGround = takeoffDemGroundByRemoteId[remoteId]
                    if (demGround != null) {
                        val demScaleToMeters = inferAndStoreDemScaleToMeters(remoteId, ridTakeoff, demGround)
                        val refinedCorrF = refinedCorrectionFromTakeoffDem(
                            takeoffTrackAltitudeM = ridTakeoff,
                            takeoffDemGroundRaw = demGround,
                            demScaleToMeters = demScaleToMeters,
                            existingCorrectionM = demCorrectionByRemoteId[remoteId],
                        )!!
                        demCorrectionByRemoteId[remoteId] = refinedCorrF
                        if (CTDebugEnabled(tag)) CTDebug(
                            tag,
                            "AGL correctionF refined at seal for $designator: " +
                                "takeoffAlt=${"%.1f".format(ridTakeoff)}m " +
                                "demGroundRaw=${"%.1f".format(demGround)} " +
                                "demScale=${"%.4f".format(demScaleToMeters)} " +
                                "correctionF=${"%.1f".format(refinedCorrF)}m"
                        )
                    }
                }
                if (CTDebugEnabled(tag) && (existing == null || isSealed)) {
                    val event = if (existing == null) "first" else "converged"
                    CTDebug(
                        tag,
                        "ATO auto-seed ($event RID height) for $designator: " +
                            "takeoffAlt=${"%.1f".format(ridTakeoff)}m " +
                            "(${"%.0f".format(ridTakeoff * METERS_TO_FEET)}ft)"
                    )
                }
            }
            existing == null && altM.isFinite() && altM > -999.0 && altM != 0.0 -> {
                // Fallback: drone doesn't broadcast ATO height; use first fix.
                // altM > -999.0 excludes the RID invalid sentinel (-1000.0).
                calibrationByRemoteId[remoteId] = DroneAltitudeCalibration(altM, AtoSeedSource.AUTO)
                if (CTDebugEnabled(tag)) CTDebug(
                    tag,
                    "ATO auto-seed (first fix) for $designator: " +
                        "alt=${"%.1f".format(altM)}m (${"%.0f".format(altM * METERS_TO_FEET)}ft)"
                )
            }
        }
    }

    // ── Manual calibration ─────────────────────────────────────────────────────────────────

    /**
     * Applies a manual ATO/AGL calibration for the drone identified by [remoteId] at
     * [currentAltM].  Sets ATO so that the displayed value at [currentAltM] equals 50 ft,
     * and derives the AGL correction factor from the current DEM ground elevation.
     */
    suspend fun pointAolFeet(remoteId: String, latitude: Double, longitude: Double): Double? {
        val entry = droneStates.entries.firstOrNull { it.value.remoteId == remoteId } ?: return null
        val display = displayStateByDesignator[entry.key] ?: return null
        if (display.positionStale || display.atoStatus != MeasurementStatus.Available) return null
        val anchor = aolAnchors[remoteId] ?: return null
        val manual = calibrationByRemoteId[remoteId]?.seedSource == AtoSeedSource.MANUAL && manualAolFlight[remoteId] == entry.value.flightStartMsec
        if (!manual && (!entry.value.source.isLastRidHeightAto() || System.currentTimeMillis() - entry.value.source.lastRidHeightReceivedAtMsec !in 0..4999)) return null
        val height = display.atoFt?.times(FT_TO_METERS) ?: return null
        val flight = entry.value.flightStartMsec
        val result = withContext(aolDispatcher) {
            runCatching { SurfaceStore.calculate(applicationContext, latitude, longitude, anchor.first, anchor.second, height, pointOnly = true).feet }.getOrNull()
        }
        return result.takeIf { droneStates[entry.key]?.flightStartMsec == flight && aolAnchors[remoteId] == anchor && System.currentTimeMillis() - entry.value.source.mostRecentMsecTimestamp in 0..4999 }
    }

    fun canCalibrateOverLaunch(designator: String): Boolean {
        val state = droneStates[designator] ?: return false
        val point = latestLocalPointByDesignator[designator] ?: return false
        return System.currentTimeMillis() - state.source.mostRecentMsecTimestamp in 0..4999 && point.altM.isFinite() && point.altM > -999
    }

    fun manualCalibrateOverLaunch(designator: String): Boolean {
        val state=droneStates[designator] ?: return false
        val point=latestLocalPointByDesignator[designator] ?: return false
        if(System.currentTimeMillis()-state.source.mostRecentMsecTimestamp !in 0..4999 || !point.altM.isFinite() || point.altM<=-999) return false
        aolAnchors[state.remoteId]=point.lat to point.lng
        manualAolFlight[state.remoteId]=state.flightStartMsec
        manualCalibrate(state.remoteId,point.altM,designator)
        return true
    }

    fun manualCalibrate(remoteId: String, currentAltM: Double, designator: String) {
        aolVideoReferences.remove(remoteId)
        aolRefreshWindows.remove(designator)
        aolResults.remove(designator)
        aolRequests.remove(designator)
        val calibratedTakeoffAltM = currentAltM - (CALIBRATE_ATO_TARGET_FT * FT_TO_METERS)
        calibrationByRemoteId[remoteId] = DroneAltitudeCalibration(
            takeoffTrackAltitudeM = calibratedTakeoffAltM,
            seedSource = AtoSeedSource.MANUAL
        )
        // Derive AGL correction factor F = (droneAlt − 50ft) − demGround.
        // At render time: AGL = droneAlt_new − demGround_new − F.
        // Clears any existing correction first so it is unconditionally recomputed with the
        // fresh calibration; no stale correctionF can survive a manual recalibration.
        demCorrectionByRemoteId.remove(remoteId)
        val calibrationPoint=latestLocalPointByDesignator[designator]
        val demGround = demGroundByRemoteId[remoteId]?.takeIf { sample ->
            !sample.stale && calibrationPoint != null && demKeyByRemoteId[remoteId]==demElevationService.samplingKey(calibrationPoint.lat,calibrationPoint.lng)
        }?.groundM
        if (demGround != null) {
            val demScaleToMeters = inferAndStoreDemScaleToMeters(remoteId, calibratedTakeoffAltM, demGround)
            val correctionF = calibratedTakeoffAltM - (demGround * demScaleToMeters)
            demCorrectionByRemoteId[remoteId] = correctionF
            if (CTDebugEnabled(tag)) CTDebug(
                tag,
                "ATO+AGL manually calibrated for $designator: " +
                    "droneAlt=${"%.1f".format(currentAltM)}m (${"%.0f".format(currentAltM * METERS_TO_FEET)}ft) " +
                    "takeoffTrackAlt=${"%.1f".format(calibratedTakeoffAltM)}m " +
                    "demGroundRaw=${"%.1f".format(demGround)} " +
                    "demScale=${"%.4f".format(demScaleToMeters)} " +
                    "correctionF=${"%.1f".format(correctionF)}m"
            )
        } else {
            if (CTDebugEnabled(tag)) CTDebug(
                tag,
                "ATO+AGL manually calibrated for $designator (no DEM yet): " +
                    "droneAlt=${"%.1f".format(currentAltM)}m " +
                    "takeoffTrackAlt=${"%.1f".format(calibratedTakeoffAltM)}m " +
                    "correctionF=n/a (pending first DEM fetch)"
            )
        }
        // Recompute immediately so the UI reflects the new calibration without waiting for
        // the next position update.
        recomputeDisplayState(designator)
    }

    // ── Map-reconnect reset (called by MapPane's LaunchedEffect(mapName)) ───────────────────

    /**
     * Resets per-flight DEM and heading state when the Caltopo map reconnects.
     * Preserves MANUAL and AUTO_SEALED calibrations (and their AGL corrections). MANUAL is an
     * explicit operator reference; AUTO_SEALED is the flight's converged takeoff reference.
     * Rebuilding either one from the next current-position DEM sample would silently move the
     * takeoff ground reference to wherever the drone happens to be during the reconnect.
     */
    fun onMapReconnect() {
        demGroundByRemoteId.clear()
        demPending.clear()
        demCorrectionPending.clear()
        demLastAttemptMs.clear()
        demKeyByRemoteId.clear()
        // Preserve locked calibrations and their corrections.
        calibrationByRemoteId.keys.retainAll { key ->
            shouldPreserveCalibrationOnMapReconnect(calibrationByRemoteId[key])
        }
        demCorrectionByRemoteId.keys.retainAll { key ->
            calibrationByRemoteId.containsKey(key)
        }
        demScaleToMetersByRemoteId.keys.retainAll { key ->
            calibrationByRemoteId.containsKey(key)
        }
        takeoffDemGroundByRemoteId.keys.retainAll { key ->
            calibrationByRemoteId.containsKey(key)
        }
        telemetryHeading.clear()
        fallbackHeading.clear()
        fallbackHeadingAnchor.clear()
        latestLocalPointByDesignator.clear()
    }

    // ── DEM lookup ───────────────────────────────────────────────────────────────────────────

    private fun scheduleDemIfNeeded(state: DroneSpecState, nowMs: Long) {
        scheduleDemIfNeeded(state.remoteId, state.mappedId, state.lastLat, state.lastLng, nowMs)
    }

    private fun scheduleDemIfNeeded(
        remoteId: String,
        designator: String,
        lat: Double,
        lng: Double,
        nowMs: Long,
    ) {
        if (lat == 0.0 && lng == 0.0) return
        if (!lat.isFinite() || !lng.isFinite()) return
        prefetchTerrainForLocation(lat, lng)

        val demKey   = demElevationService.samplingKey(lat, lng)
        val priorKey = demKeyByRemoteId[remoteId]
        val aglState = demGroundByRemoteId[remoteId]
        val shouldRetry = priorKey == demKey &&
            (aglState == null || aglState.stale) &&
            (nowMs - (demLastAttemptMs[remoteId] ?: 0L)) >= DEM_RETRY_INTERVAL_MS

        if ((priorKey != demKey || shouldRetry) && !demPending.contains(remoteId)) {
            demPending.add(remoteId)
            demLastAttemptMs[remoteId] = nowMs
            scope.launch(Dispatchers.IO) {
                val sample = demElevationService.sampleElevationMeters(lat, lng)
                withContext(Dispatchers.Main.immediate) {
                    demPending.remove(remoteId)
                    if (sample != null) {
                        // Store only the DEM ground elevation. AGL is computed dynamically
                        // at render time from point.altitudeM − groundM so it always reflects
                        // the live drone altitude rather than the altitude at fetch time.
                        demGroundByRemoteId[remoteId] = DroneAglState(sample.elevationMeters, sample.stale)
                        demKeyByRemoteId[remoteId] = demKey

                        // Derive AGL correction factor as soon as both DEM ground and any
                        // calibration (AUTO, AUTO_SEALED, or MANUAL) are available.
                        //
                        // correctionF bridges the gap between the drone's baro frame and MSL:
                        //   correctionF = takeoffTrackAltM − demGround_at_takeoff
                        // At render time: AGL = altitudeM − demGround_current − correctionF
                        //
                        // Computed only once (on first DEM result after calibration is ready).
                        // The AUTO_SEALED path later refines this with the converged EMA value.
                        if (!demCorrectionByRemoteId.containsKey(remoteId)) {
                            ensureCorrectionFromTakeoffDem(remoteId, designator, lat, lng, sample.elevationMeters)
                        }
                    } else {
                        // DEM fetch failed; mark existing data stale so the UI shows '?'
                        demGroundByRemoteId[remoteId]?.let {
                            demGroundByRemoteId[remoteId] = it.copy(stale = true)
                        }
                    }
                    recomputeDisplayState(designator)
                }
            }
        }
    }

    /**
     * Starts terrain preparation without waiting for a map pane or blocking normal app use.
     * Device requests include the operating radius; aircraft requests cover their location cell.
     * Successful cells are deduplicated and failed transfers retry on later location updates.
     */
    fun prefetchTerrainForLocation(lat: Double, lng: Double, radiusMeters: Double = 0.0) {
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            return
        }
        if (lat == 0.0 && lng == 0.0) return
        // A fresh install can receive a location before archive selection completes. Do not
        // consume the cell in that state; a later tablet/drone update must be allowed to retry.
        if (org.ncssar.rid2caltopo.data.CaltopoClient.GetArchiveDir() == null) return
        val cell = "${demPrefetchCellKey(lat, lng)}:$radiusMeters"
        if (!terrainPrefetchGate.schedule(cell, android.os.SystemClock.elapsedRealtime())) return
        scope.launch(Dispatchers.IO) {
            var complete = false
            try {
                // Make already-downloaded local data usable before any catalog/network wait.
                val localS1mReady = demElevationService.prewarmLocalForLocation(lat, lng)
                terrainPrefetchMutex.withLock {
                    complete = if (radiusMeters == 0.0 && localS1mReady) true else autoDownloadBestDemForLocation(
                        lat, lng, applicationContext, terrainPrefetchClient, demElevationService, radiusMeters,
                    )
                    demElevationService.prewarmLocalForLocation(lat, lng)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CTDebug("TerrainPrefetch", "Terrain preparation failed; retryable error=${e.javaClass.simpleName}")
            } finally {
                terrainPrefetchGate.finish(cell, complete, android.os.SystemClock.elapsedRealtime())
            }
        }
    }

    private fun ensureCorrectionFromTakeoffDem(
        remoteId: String,
        designator: String,
        currentLat: Double,
        currentLng: Double,
        currentDemRaw: Double,
    ) {
        val cal = calibrationByRemoteId[remoteId] ?: return
        val spec = droneStates[designator]?.source
        val manualLaunch=if(manualAolFlight[remoteId]==droneStates[designator]?.flightStartMsec) aolAnchors[remoteId] else null
        val takeoffLat = manualLaunch?.first ?: spec?.takeoffLat ?: 0.0
        val takeoffLng = manualLaunch?.second ?: spec?.takeoffLng ?: 0.0
        if ((manualLaunch != null || spec?.hasTakeoffLocation() == true) &&
            takeoffLat.isFinite() &&
            takeoffLng.isFinite() &&
            !(takeoffLat == 0.0 && takeoffLng == 0.0) &&
            demElevationService.samplingKey(takeoffLat, takeoffLng) !=
                demElevationService.samplingKey(currentLat, currentLng)
        ) {
            if (demCorrectionPending.add(remoteId)) {
                scope.launch(Dispatchers.IO) {
                    val takeoffSample = demElevationService.sampleElevationMeters(takeoffLat, takeoffLng)
                    withContext(Dispatchers.Main.immediate) {
                        demCorrectionPending.remove(remoteId)
                        if (takeoffSample != null) {
                            takeoffDemGroundByRemoteId[remoteId] = takeoffSample.elevationMeters
                            val latestCal = calibrationByRemoteId[remoteId] ?: cal
                            if (latestCal.seedSource != AtoSeedSource.MANUAL || (manualLaunch != null && latestCal==cal)) {
                                setCorrectionFromDem(remoteId, latestCal, takeoffSample.elevationMeters, "takeoff")
                                recomputeDisplayState(designator)
                            }
                        }
                    }
                }
            }
        } else {
            takeoffDemGroundByRemoteId[remoteId] = currentDemRaw
            setCorrectionFromDem(remoteId, cal, currentDemRaw, "current")
        }
    }

    private fun setCorrectionFromDem(
        remoteId: String,
        cal: DroneAltitudeCalibration,
        demGroundRaw: Double,
        sourceLabel: String,
    ) {
        val demScaleToMeters = inferAndStoreDemScaleToMeters(
            remoteId,
            cal.takeoffTrackAltitudeM,
            demGroundRaw
        )
        val correctionF = cal.takeoffTrackAltitudeM - (demGroundRaw * demScaleToMeters)
        demCorrectionByRemoteId[remoteId] = correctionF
        if (CTDebugEnabled(tag)) CTDebug(
            tag,
            "AGL correctionF set for remoteId=$remoteId (${cal.seedSource}/$sourceLabel): " +
                "takeoffTrackAlt=${"%.1f".format(cal.takeoffTrackAltitudeM)}m " +
                "demGroundRaw=${"%.1f".format(demGroundRaw)} " +
                "demScale=${"%.4f".format(demScaleToMeters)} " +
                "correctionF=${"%.1f".format(correctionF)}m"
        )
    }

    // ── Display state recompute ───────────────────────────────────────────────────────────────

    private fun recomputeDisplayState(designator: String) {
        val state       = droneStates[designator] ?: return
        val remoteId    = state.remoteId
        val localPoint  = latestLocalPointByDesignator[designator]
        val altM        = localPoint?.altM ?: state.lastAlt
        val aglState    = demGroundByRemoteId[remoteId]
        val freshAgl    = aglState?.takeUnless { it.stale }
        val correctionM = demCorrectionByRemoteId[remoteId]
        val calibration = calibrationByRemoteId[remoteId]
        val demLat      = localPoint?.lat ?: state.lastLat
        val demLng      = localPoint?.lng ?: state.lastLng
        val demKey      = demElevationService.samplingKey(demLat, demLng)
        val priorKey    = demKeyByRemoteId[remoteId]
        val locationChanged = priorKey != null && priorKey != demKey
        val demIsPending    = demPending.contains(remoteId)

        // AGL is 'stale' only when we are using DEM-based AGL (correctionM != null) and the
        // DEM data is not fresh for the current position.  When falling back to ridHeight the
        // '?' indicator is not appropriate because there is no DEM involved.
        val usingDemAgl = correctionM != null
        val aglStale = usingDemAgl &&
            aglState != null && (freshAgl == null || locationChanged || demIsPending)

        val ridHeightAtoM = state.source?.let { spec ->
            val h = spec.getLastRidHeightM()
            if (spec.isLastRidHeightAto() && h != -1000.0) h else null
        }

        // AGL = droneAlt − demGround − correctionF when calibrated.
        // Before calibration, fall back to ridHeight (relative baro from takeoff point).
        val aglFt = (if (correctionM != null) {
            (freshAgl ?: aglState)?.let {
                val demScaleToMeters = demScaleToMetersByRemoteId[remoteId] ?: 1.0
                val aglMeters = calculateDemBackedAglMeters(
                    altM = altM,
                    ridHeightAtoM = ridHeightAtoM,
                    calibration = calibration,
                    correctionM = correctionM,
                    demGroundRaw = it.groundM,
                    demScaleToMeters = demScaleToMeters,
                )
                aglMeters * METERS_TO_FEET
            }
        } else {
            (if(calibration?.seedSource==AtoSeedSource.MANUAL) displayAtoMeters(altM,calibration,null) else ridHeightAtoM)?.let { it * METERS_TO_FEET }
        })?.coerceAtLeast(0.0)

        val atoFt = displayAtoMeters(altM, calibration, ridHeightAtoM)?.times(METERS_TO_FEET)
        val headingDeg = telemetryHeading[designator] ?: fallbackHeading[designator]
        val rangeFt = state.source.takeIf { it.hasTakeoffLocation() }?.let { spec ->
            val result = FloatArray(1)
            Location.distanceBetween(spec.takeoffLat, spec.takeoffLng, demLat, demLng, result)
            result[0].takeIf { it.isFinite() }?.toDouble()?.times(METERS_TO_FEET)
        }

        val identityMatches = aolIdentity[designator] == "$remoteId|${state.flightStartMsec}"
        val heightFresh = identityMatches && System.currentTimeMillis() - state.source.lastRidHeightReceivedAtMsec in 0..4999
        val manualAol = calibration?.seedSource == AtoSeedSource.MANUAL && manualAolFlight[remoteId]==state.flightStartMsec
        val aolHeight = if(manualAol) displayAtoMeters(altM,calibration,null) else ridHeightAtoM?.takeIf { it.isFinite() && it > -999 && heightFresh }
        val positionFresh = identityMatches && System.currentTimeMillis() - state.source.mostRecentMsecTimestamp in 0..4999
        val videoReferenceContinuity = aolVideoReferences.getOrPut(remoteId) {
            org.ncssar.rid2caltopo.video.surface.AolVideoReferenceContinuity()
        }
        val retainedVideoReference = if (manualAol) null else videoReferenceContinuity.observe(state.flightStartMsec, null)
        var automaticVideoReference = retainedVideoReference != null
        if (retainedVideoReference != null) aolAnchors[remoteId] = retainedVideoReference
        if (!manualAol && !automaticVideoReference && org.ncssar.rid2caltopo.video.surface.canObserveAolLaunch(
                state.source.hasFreshAolGroundStatus(System.currentTimeMillis()),
                state.source.lastRidHeightM,heightFresh,positionFresh)) {
            if (aolAnchors[remoteId] == null) CTDebug(tag,"AOL launch reference observed; heightReference=${if(state.source.isLastRidHeightAto()) "takeoff" else "ground"}")
            aolAnchors[remoteId] = demLat to demLng
        }
        if (!manualAol && heightFresh && identityMatches &&
            StreamFlightActivityRegistry.boundRemoteId(designator) == remoteId &&
            StreamFlightActivityRegistry.hasFreshPosition(remoteId, System.currentTimeMillis())) {
            val video = org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetryRegistry.freshOperationalPosition(designator)
            val videoReference = org.ncssar.rid2caltopo.video.surface.videoAolReference(
                video?.referenceLatitudeDeg, video?.referenceLongitudeDeg, video?.relativeUpMeters,
                video != null && video.receivedAtMs == state.source.lastRidHeightReceivedAtMsec)
            if (videoReference != null) {
                automaticVideoReference = true
                videoReferenceContinuity.observe(state.flightStartMsec, videoReference)
                if (aolAnchors[remoteId] != videoReference) CTDebug(tag, "AOL using DJI stream altitude reference")
                aolAnchors[remoteId] = videoReference
            }
        }
        val anchor = aolAnchors[remoteId]
        val aolKey = "$remoteId|${state.flightStartMsec}|$demLat|$demLng|$aolHeight|$anchor|${SurfaceStore.generation}"
        val telemetryAge = System.currentTimeMillis() - state.source.mostRecentMsecTimestamp
        val prerequisite = org.ncssar.rid2caltopo.video.surface.aolPrerequisiteState(
            anchor != null, aolHeight != null,
            !manualAol && !heightFresh && state.source.lastRidHeightReceivedAtMsec > 0L)
        val refreshWindow = aolRefreshWindows.getOrPut(designator) { org.ncssar.rid2caltopo.video.surface.AolRefreshWindow() }
        val reference = org.ncssar.rid2caltopo.video.surface.aolRefreshReference(
            "$remoteId|${state.flightStartMsec}", anchor, automaticVideoReference,
            if (manualAol) calibration?.takeoffTrackAltitudeM else null, SurfaceStore.generation.toString())
        if (prerequisite != null) {
            aolRequests.remove(designator)
            if (aolLoggedReasons.put(designator, prerequisite.reason) != prerequisite.reason)
                CTDebug(tag, "AOL state: ${prerequisite.reason}; launchObserved=${anchor != null}; heightFresh=$heightFresh; positionFresh=$positionFresh")
        }
        if (prerequisite == null && aolResults[designator]?.first != aolKey && telemetryAge in 0..4999) {
            // Coalesce movement while work runs; invalidating on every frame starves results.
            val startedAt = android.os.SystemClock.elapsedRealtime()
            if (aolWorkGate.begin(designator, true, startedAt)) {
                aolRequests[designator] = aolKey
                val flightStart = state.flightStartMsec
                scope.launch {
                    try {
                        val result = withContext(aolDispatcher) {
                            runCatching {
                                if (!manualAol && !heightFresh && state.source.lastRidHeightReceivedAtMsec > 0L) {
                                    AolState(reason = "Aircraft height stale", status = MeasurementStatus.Stale)
                                } else if (anchor == null) {
                                    AolState(reason = "Takeoff ground reference not observed; receive ground status and near-zero height before flight")
                                } else {
                                    SurfaceStore.calculate(applicationContext, demLat, demLng, anchor.first, anchor.second, aolHeight)
                                }
                            }.getOrElse { AolState(reason = "Surface analysis unavailable") }
                        }
                        if (aolRequests[designator] == aolKey && droneStates[designator]?.remoteId == remoteId &&
                            droneStates[designator]?.flightStartMsec == flightStart) {
                            if (aolLoggedReasons.put(designator,result.reason) != result.reason) CTDebug(tag,"AOL state: ${result.reason}; launchObserved=${anchor != null}; heightFresh=$heightFresh; positionFresh=$positionFresh")
                            aolResults[designator] = aolKey to result
                            refreshWindow.completed(reference, result, startedAt, android.os.SystemClock.elapsedRealtime())
                            recomputeDisplayState(designator)
                        }
                    } finally { aolWorkGate.finish() }
                }
            }
        }
        val matchingResult = prerequisite ?: aolResults[designator]?.takeIf { it.first == aolKey }?.second
        val displayedResult = refreshWindow.display(reference, matchingResult, aolHeight != null,
            android.os.SystemClock.elapsedRealtime())
        val aol = if (telemetryAge !in 0..4999) AolState(reason = "Aircraft position/altitude stale", status = MeasurementStatus.Stale)
            else displayedResult
        displayStateByDesignator[designator] = DroneDisplayState(
            aol = aol,
            positionStale = telemetryAge !in 0..4999,
            atoStatus = if(calibration?.seedSource != AtoSeedSource.MANUAL && ridHeightAtoM != null && !heightFresh) MeasurementStatus.Stale else if(atoFt==null) MeasurementStatus.Unknown else MeasurementStatus.Available,
            aglStatus = when {
                calibration?.seedSource != AtoSeedSource.MANUAL && ridHeightAtoM != null && !heightFresh -> MeasurementStatus.Stale
                anchor == null && !(heightFresh && state.source.lastRidHeightM.isFinite() && !state.source.isLastRidHeightAto()) -> MeasurementStatus.CalibrationRequired
                aglStale && demIsPending -> MeasurementStatus.Pending
                aglStale -> MeasurementStatus.Unknown
                aglFt==null && demIsPending -> MeasurementStatus.Pending
                aglFt==null -> MeasurementStatus.Unknown
                else -> MeasurementStatus.Available
            },
            headingDeg = headingDeg,
            derivedHeadingDeg = fallbackHeading[designator],
            aglFt = aglFt,
            aglStale = aglStale,
            aglUsesDem = usingDemAgl,
            atoFt = atoFt,
            rangeFt = rangeFt,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private fun displayAtoMeters(altM: Double, cal: DroneAltitudeCalibration?, ridHeightAtoM: Double?): Double? {
        // Manual calibration is an explicit user override and always takes precedence.
        if (cal?.seedSource == AtoSeedSource.MANUAL) return altM - cal.takeoffTrackAltitudeM
        return ridHeightAtoM ?: cal?.let { altM - it.takeoffTrackAltitudeM }
    }

    private fun inferAndStoreDemScaleToMeters(
        remoteId: String,
        referenceGroundM: Double,
        demGroundRaw: Double
    ): Double {
        demScaleToMetersByRemoteId[remoteId]?.let { return it }
        val scale = inferDemScaleToMeters(
            droneAltMeters = referenceGroundM,
            knownGroundMeters = referenceGroundM,
            droneDemRaw = demGroundRaw
        )
        demScaleToMetersByRemoteId[remoteId] = scale
        return scale
    }

    private fun normalizeDegrees(value: Double): Double {
        val normalized = value % 360.0
        return if (normalized < 0.0) normalized + 360.0 else normalized
    }

    companion object {
        private const val FT_TO_METERS = 0.3048
        private const val DEM_RETRY_INTERVAL_MS = 2_000L
        private const val METERS_TO_FEET = 3.28084
        private const val CALIBRATE_ATO_TARGET_FT = 50.0

        internal fun calculateDemBackedAglMeters(
            altM: Double,
            ridHeightAtoM: Double?,
            calibration: DroneAltitudeCalibration?,
            correctionM: Double,
            demGroundRaw: Double,
            demScaleToMeters: Double,
        ): Double {
            val demGroundM = demGroundRaw * demScaleToMeters
            val calculated = if (ridHeightAtoM != null && calibration != null && calibration.seedSource != AtoSeedSource.MANUAL) {
                val takeoffGroundM = calibration.takeoffTrackAltitudeM - correctionM
                ridHeightAtoM + takeoffGroundM - demGroundM
            } else {
                altM - demGroundM - correctionM
            }
            // DEM, barometric altitude, and their calibration can each be imperfect, but a
            // negative AGL is not an actionable physical value for an aircraft track.
            return calculated.coerceAtLeast(0.0)
        }

        internal fun shouldPreserveCalibrationOnMapReconnect(
            calibration: DroneAltitudeCalibration?
        ): Boolean {
            return calibration?.seedSource == AtoSeedSource.MANUAL ||
                calibration?.seedSource == AtoSeedSource.AUTO_SEALED
        }

        internal fun shouldResetAutoCalibrationForFlightChange(
            calibration: DroneAltitudeCalibration?
        ): Boolean {
            return calibration?.seedSource != AtoSeedSource.MANUAL
        }

        internal fun refinedCorrectionFromTakeoffDem(
            takeoffTrackAltitudeM: Double,
            takeoffDemGroundRaw: Double?,
            demScaleToMeters: Double,
            existingCorrectionM: Double?,
        ): Double? {
            return takeoffDemGroundRaw?.let {
                takeoffTrackAltitudeM - (it * demScaleToMeters)
            } ?: existingCorrectionM
        }
    }
}

private data class LocalAltitudePoint(
    val lat: Double,
    val lng: Double,
    val altM: Double,
)

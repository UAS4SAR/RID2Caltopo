package org.ncssar.rid2caltopo.landrestrictions

import android.content.Context
import android.location.Location
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap

object LandRestrictionCenter {
    private const val LOOP_DELAY_MS = 60_000L
    private const val REFRESH_INTERVAL_MS = 15 * 60_000L
    private const val STALE_INTERVAL_MS = 24 * 60 * 60_000L
    private const val MOVEMENT_REFRESH_METERS = 926f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _uiState = MutableStateFlow(LandRestrictionUiState())
    val uiState: StateFlow<LandRestrictionUiState> = _uiState.asStateFlow()
    private val refreshing = AtomicBoolean(false)

    @Volatile private var initialized = false
    private var repository: LandRestrictionRepository? = null
    private var refreshJob: Job? = null
    private var lastAreas: List<LandRestrictionArea> = emptyList()
    private var lastErrors: List<String> = emptyList()
    private var lastCoordinate: Location? = null
    private var lastAttemptEpochMs = 0L
    private var oldestDataEpochMs: Long? = null

    internal val isMonitoring: Boolean get() = refreshJob?.isActive == true

    @Synchronized
    fun initialize(context: Context) {
        if (initialized && isMonitoring) return
        repository = LandRestrictionRepository(context.applicationContext)
        initialized = true
        startLoop()
        org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("LandRules", "Monitoring started or resumed")
    }

    fun requestImmediateRefresh() {
        if (initialized) scope.launch { refresh(force = true) }
    }

    fun settingsChanged() {
        if (initialized) scope.launch { refresh(force = true) }
    }

    @Synchronized
    fun shutdown() {
        refreshJob?.cancel()
        refreshJob = null
        initialized = false
    }

    private fun startLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                refresh(force = false)
                delay(LOOP_DELAY_MS)
            }
        }
    }

    private suspend fun refresh(force: Boolean) {
        val enabled = CaltopoClient.GetLandRestrictionsEnabled()
        if (!enabled) {
            _uiState.value = disabledLandRestrictionUiState()
            return
        }
        val location = CaltopoMap.GetMyLocation()
        if (location == null) {
            rebuild(
                loading = false,
                waitingForLocation = true,
                statusOverride = "Waiting for GPS location"
            )
            return
        }
        if (!force && !shouldRefresh(location)) {
            rebuild(loading = false)
            return
        }
        if (!refreshing.compareAndSet(false, true)) return
        try {
            lastAttemptEpochMs = System.currentTimeMillis()
            rebuild(loading = true)
            val result = repository?.fetch(location, CaltopoClient.GetLandRestrictionsRadiusNm()) ?: return
            lastAreas = result.areas
            lastErrors = result.sourceErrors
            lastCoordinate = Location(location)
            oldestDataEpochMs = result.oldestDataEpochMs
            result.newestDataEpochMs?.let(CaltopoClient::SetLandRestrictionsLastUpdatedEpochMs)
            rebuild(loading = false)
            CaltopoClient.CTDebug(
                "LandRules",
                "Loaded ${lastAreas.size} protected-land area(s); sourceErrors=${lastErrors.size}"
            )
        } catch (error: Exception) {
            lastErrors = listOf(error.message ?: "Protected-land lookup unavailable")
            rebuild(loading = false)
            CaltopoClient.CTWarn("LandRules", "Protected-land refresh failed", error)
        } finally {
            refreshing.set(false)
        }
    }

    private fun shouldRefresh(location: Location): Boolean {
        val now = System.currentTimeMillis()
        if (lastAttemptEpochMs == 0L) return true
        if (CaltopoClient.GetLandRestrictionsLastUpdatedEpochMs() <= 0L && now - lastAttemptEpochMs >= 15_000L) return true
        if (!CaltopoClient.GetLandRestrictionsAutoRefresh()) return false
        if (now - lastAttemptEpochMs >= REFRESH_INTERVAL_MS) return true
        return lastCoordinate?.distanceTo(location)?.let { it >= MOVEMENT_REFRESH_METERS } ?: true
    }

    private fun rebuild(
        loading: Boolean,
        waitingForLocation: Boolean = false,
        statusOverride: String? = null
    ) {
        val nearby = lastAreas.filter { it.intersectsOperatingArea }
        val updated = CaltopoClient.GetLandRestrictionsLastUpdatedEpochMs().takeIf { it > 0L }
        val stale = oldestDataEpochMs?.let { System.currentTimeMillis() - it > STALE_INTERVAL_MS } ?: false
        _uiState.value = LandRestrictionUiState(
            visible = true,
            enabled = true,
            loading = loading,
            stale = stale,
            severity = LandRestrictionPolicy.severity(
                nearby,
                lastErrors.isNotEmpty(),
                waitingForLocation
            ),
            chipLabel = LandRestrictionPolicy.chipLabel(
                nearby,
                loading,
                lastErrors.isNotEmpty(),
                waitingForLocation
            ),
            statusLine = statusOverride ?: statusLine(nearby, loading),
            lastUpdatedEpochMs = updated,
            areas = nearby,
            sourceErrors = lastErrors
        )
    }

    private fun statusLine(nearby: List<LandRestrictionArea>, loading: Boolean): String = when {
        loading -> "Checking federal and Colorado protected lands…"
        nearby.isEmpty() && lastErrors.isNotEmpty() -> "Some protected-land sources could not be checked."
        nearby.isEmpty() -> "No protected-land boundaries intersect the one-mile operating area."
        else -> "${nearby.size} protected-land area(s) intersect or border the operating area."
    }
}

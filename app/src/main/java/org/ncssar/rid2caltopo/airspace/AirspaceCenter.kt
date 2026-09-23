package org.ncssar.rid2caltopo.airspace

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap

object AirspaceCenter {
    private const val LOOP_DELAY_MS = 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = AirspaceRepository()
    private val _uiState = MutableStateFlow(AirspaceUiState())
    val uiState: StateFlow<AirspaceUiState> = _uiState.asStateFlow()

    @Volatile
    private var initialized = false
    private var refreshJob: Job? = null
    private var lastRecords: List<FaaUasFacilityMapRecord> = emptyList()
    private var lastError: String? = null
    private var lastSuccessfulCheck = "No successful check this session"
    private var queryCoordinate: AirspaceCoordinate? = null
    private var hasCompletedRefresh = false
    private val refreshMutex = Mutex()

    internal val isMonitoring: Boolean get() = refreshJob?.isActive == true

    @Synchronized
    fun initialize(context: Context) {
        if (initialized && isMonitoring) return
        initialized = true
        startLoop()
        org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("Airspace", "Monitoring started or resumed")
    }

    fun requestImmediateRefresh() {
        if (!initialized) return
        scope.launch { refresh() }
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
                refresh()
                delay(LOOP_DELAY_MS)
            }
        }
    }

    private suspend fun refresh() = refreshMutex.withLock {
        if (!CaltopoClient.GetNotamEnabled()) {
            lastRecords = emptyList()
            lastError = null
            lastSuccessfulCheck = "No successful check this session"
            queryCoordinate = null
            hasCompletedRefresh = false
            _uiState.value = AirspaceUiState(visible = false)
            return@withLock
        }
        val location = CaltopoMap.GetMyLocation()
        if (location == null) {
            CaltopoClient.CTDebug("Airspace", "Controlled-airspace refresh waiting for GPS location")
            _uiState.value = AirspacePolicy.buildUiState(
                records = lastRecords,
                loading = false,
                errorMessage = lastError ?: "Waiting for GPS location",
                pilotCoordinate = null
            ).copy(lastSuccessfulCheck = lastSuccessfulCheck, queryCoordinate = queryCoordinate)
            return@withLock
        }
        if (!hasCompletedRefresh) {
            _uiState.value = AirspacePolicy.buildUiState(
                records = lastRecords,
                loading = true,
                errorMessage = lastError,
                pilotCoordinate = AirspaceCoordinate(location.latitude, location.longitude)
            )
        }
        try {
            lastRecords = repository.fetch(location)
            lastSuccessfulCheck = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            queryCoordinate = AirspaceCoordinate(location.latitude, location.longitude)
            lastError = null
            CaltopoClient.CTDebug(
                "Airspace",
                "Loaded ${lastRecords.size} FAA facility-map grid(s); " +
                    "controlled=${lastRecords.count { it.airspaceClasses.isNotEmpty() }} " +
                    "laanc=${lastRecords.count { it.laancAvailable }}"
            )
        } catch (e: Exception) {
            lastError = e.message ?: "Controlled-airspace lookup unavailable"
            CaltopoClient.CTWarn("Airspace", lastError, e)
        }
        hasCompletedRefresh = true
        val refreshedState = AirspacePolicy.buildUiState(
            records = lastRecords,
            loading = false,
            errorMessage = lastError,
            pilotCoordinate = AirspaceCoordinate(location.latitude, location.longitude)
        )
        _uiState.value = refreshedState.copy(lastSuccessfulCheck = lastSuccessfulCheck, queryCoordinate = queryCoordinate)
    }
}

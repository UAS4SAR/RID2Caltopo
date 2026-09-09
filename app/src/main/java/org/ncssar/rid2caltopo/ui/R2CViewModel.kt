/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.app.R2CApplication
import org.ncssar.rid2caltopo.app.ScanningService
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.CaltopoCredentials
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoNode
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DelayedExec
import org.ncssar.rid2caltopo.data.R2CMqttManager
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
import org.ncssar.rid2caltopo.data.SimpleTimer
import org.ncssar.rid2caltopo.data.TabletPilotCallsignPrefs
import java.util.concurrent.atomic.AtomicBoolean

enum class ActiveScreen {
    MAIN,
    STREAMS,
    SETTINGS,
    SCANNER
}

sealed class CaltopoConnectionState(val displayName: String) {
    override fun toString(): String = displayName
    // Standard operating mode: local logging only
    object StandAlone : CaltopoConnectionState("StandAlone")

    object NoNetwork : CaltopoConnectionState("NoNetwork")

    // Transient state: UI shows "Verifying Team Access..." with an animation
    object CheckingCredentials : CaltopoConnectionState("CheckingCredentials")

    // Credentials verified, we have the hierarchy, waiting for user to pick a map
    object CredentialsVerified : CaltopoConnectionState("CredentialsVerified")

    object CredentialsLoaded : CaltopoConnectionState("CredentialsLoaded")

    // Transition state: Handing the MapNode to your Java openMap() logic
    object Connecting : CaltopoConnectionState("Connecting")

    // Active state: Map is UP
    data class MapSelected(val map: CaltopoNode.MapNode) : CaltopoConnectionState("MapSelected")
}

sealed class UIEvent(val displayName: String) {
    override fun toString(): String = displayName
    // User Actions
    object HeaderClicked : UIEvent("HeaderClicked")
    object ConnectionRequested: UIEvent("ConnectionRequested")
    object DismissRequested : UIEvent("DismissRequested")
    object DisconnectRequested : UIEvent("DisconnectRequested")
    object SwitchMapRequested: UIEvent("SwitchMapRequested")
    data class BrowseProfileSelected(val profileId: String) : UIEvent("BrowseProfileSelected")
    object ConfigFileLoaded: UIEvent("ConfigFileLoaded")
    object NotAbleToReadConfigFile: UIEvent("NotAbleToReadConfigFile")
    data class MapSelected(val map: CaltopoNode.MapNode) : UIEvent("MapSelected")

    // System/Network Notifications
    data class ConnectionStatusChanged(val mapStatus: CaltopoMap.MapStatusListener.mapStatus) : UIEvent("ConnectionStatusChanged")
}

sealed class OverlayState(val displayName: String) {
    override fun toString(): String = displayName
    object None : OverlayState("None")
    object ConnectionSetup : OverlayState("ConnectionSetup") // Formerly showStandAlonePopup
    object RequestConfigFile : OverlayState("RequestConfigFile")
    object Connecting: OverlayState("Connecting")      // spinning icon.
    object MapBrowser : OverlayState("MapBrowser")     // Formerly showMapBrowser
    object Management : OverlayState("Management")     // Formerly showConnectedOptions
    data class Error(val message: String) : OverlayState("Error") // Added for the error dialog
}

data class MapBrowserProfileOption(
    val profileId: String,
    val label: String
)

data class OperationalProfileUiOption(
    val profileId: String,
    val credentialLabel: String,
    val description: String,
    val expiresAtEpochMs: Long
)

data class PendingProfileSwitchUiState(
    val profileId: String,
    val label: String,
    val activeFlightCount: Int
)

private class DroneSpecUiList(
    private val delegate: List<CtDroneSpec>
) : java.util.AbstractList<CtDroneSpec>() {
    override val size: Int
        get() = delegate.size

    override fun get(index: Int): CtDroneSpec = delegate[index]

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

private data class DroneSpecUiSignature(
    val remoteId: String,
    val mappedId: String,
    val publishingLocally: Boolean,
    val bt4Count: Int,
    val bt4Rssi: Int,
    val bt5Count: Int,
    val bt5Rssi: Int,
    val wifiCount: Int,
    val wifiRssi: Int,
    val wnanCount: Int,
    val wnanRssi: Int,
    val totalCount: Int,
    val duration: String
)

private data class DroneSpecUiSnapshot(
    val drones: List<CtDroneSpec>,
    val signature: List<DroneSpecUiSignature>
)

private fun normalizedDroneSpecUiSnapshot(
    droneSpecs: List<CtDroneSpec>,
    tag: String
): DroneSpecUiSnapshot {
    val duplicates = droneSpecs
        .groupBy { it.remoteId }
        .filterValues { it.size > 1 }
    if (duplicates.isNotEmpty()) {
        CTDebug(
            tag,
            "Normalizing duplicate DroneItem remoteIds: " +
                duplicates.entries.joinToString { (remoteId, specs) ->
                    "$remoteId=${specs.map { it.mappedId }}"
                }
        )
    }
    val normalized = droneSpecs
        .distinctBy { it.remoteId }
        .sortedWith { left, right -> left.compareToAge(right) }
    val signature = normalized.map { drone ->
        DroneSpecUiSignature(
            remoteId = drone.remoteId,
            mappedId = drone.mappedId,
            publishingLocally = drone.publishingLocally(),
            bt4Count = drone.getTransportCount(CtDroneSpec.TransportTypeEnum.BT4),
            bt4Rssi = drone.getLastRssi(CtDroneSpec.TransportTypeEnum.BT4),
            bt5Count = drone.getTransportCount(CtDroneSpec.TransportTypeEnum.BT5),
            bt5Rssi = drone.getLastRssi(CtDroneSpec.TransportTypeEnum.BT5),
            wifiCount = drone.getTransportCount(CtDroneSpec.TransportTypeEnum.WIFI),
            wifiRssi = drone.getLastRssi(CtDroneSpec.TransportTypeEnum.WIFI),
            wnanCount = drone.getTransportCount(CtDroneSpec.TransportTypeEnum.WNAN),
            wnanRssi = drone.getLastRssi(CtDroneSpec.TransportTypeEnum.WNAN),
            totalCount = drone.totalCount,
            duration = drone.getDurationInSecAsString()
        )
    }
    return DroneSpecUiSnapshot(normalized, signature)
}

class R2CViewModel(val uptimeTimer: SimpleTimer) : ViewModel(),
    CtDroneSpec.DroneSpecsChangedListener, CaltopoMap.MapStatusListener,
    CtDroneSpec.DroneConfirmationCandidateListener,
    CaltopoLiveTrack.LocalTrackListener,
    CaltopoLiveTrack.LocalTrackFinishedListener {
    private val tag = "R2CViewModel"
    private val _drones = MutableStateFlow<List<CtDroneSpec>>(emptyList())
    private val _appUptime = MutableStateFlow(ScanningService.UpTime())
    private val delayedUptimePoll : DelayedExec = DelayedExec()
    val drones: StateFlow<List<CtDroneSpec>> = _drones.asStateFlow()
    val appUpTime = _appUptime.asStateFlow()
    private val _hostname = MutableStateFlow("")
    val hostname = _hostname.asStateFlow()
    private val _pendingDroneConfirmation = MutableStateFlow<DroneSpecConfirmationUiState?>(null)
    val pendingDroneConfirmation = _pendingDroneConfirmation.asStateFlow()
    private val _activeScreen = MutableStateFlow(ActiveScreen.MAIN)
    val activeScreen : StateFlow<ActiveScreen> = _activeScreen.asStateFlow()
    private val promptedCurrentFlightRemoteIds = linkedSetOf<String>()
    private val confirmedCurrentFlightRemoteIds = linkedSetOf<String>()
    private var pendingDroneConfirmationRequestedByOperator = false
    private var screenBeforeConfirmation: ActiveScreen? = null
    private var screenBeforeConnectionOverlay: ActiveScreen? = null
    private var lastDroneListSignature: List<DroneSpecUiSignature>? = null
    private var lastUnknownDroneConfirmationOrganization = ""
    private val credentialRecoveryScheduled = AtomicBoolean(false)

    var mapHierarchy by mutableStateOf<List<CaltopoNode>?>(null)
        private set
    var mapBrowserProfiles by mutableStateOf<List<MapBrowserProfileOption>>(emptyList())
        private set
    var selectedMapBrowserProfileId by mutableStateOf("")
        private set
    var operationalProfiles by mutableStateOf<List<OperationalProfileUiOption>>(emptyList())
        private set
    var selectedOperationalProfileId by mutableStateOf("")
        private set
    var pendingProfileSwitch by mutableStateOf<PendingProfileSwitchUiState?>(null)
        private set

    val hasCredentials: Boolean
        get() = CaltopoCredentials.sniffTest(CaltopoClient.GetCaltopoCredentials())

    val hasNetwork: Boolean
        get() = !R2CMqttManager.GetMyIpAddress().isEmpty()

    // Map and U/I State management
    var connectionState by mutableStateOf<CaltopoConnectionState>(CaltopoConnectionState.StandAlone)
        private set
    var overlay by mutableStateOf<OverlayState>(OverlayState.None)
        private set

    init {
        CaltopoMap.AddMapStatusListener(this)
        CaltopoLiveTrack.AddLocalTrackListener(this)
        CaltopoLiveTrack.AddLocalTrackFinishedListener(this)
        overlay = OverlayState.None
        connectionState = CaltopoConnectionState.StandAlone
        refreshMapBrowserProfiles()
        delayedUptimePoll.start(this::uptimePoll, 1000, 1000)
    }

    private fun refreshMapBrowserProfiles() {
        mapBrowserProfiles = CaltopoClient.GetMapBrowserProfileOptions().map { option ->
            MapBrowserProfileOption(
                profileId = option.first.orEmpty(),
                label = option.second.orEmpty()
            )
        }
        selectedMapBrowserProfileId = CaltopoClient.GetActiveCaltopoProfileId().orEmpty()
        refreshOperationalProfiles()
    }

    fun refreshOperationalProfiles() {
        operationalProfiles = CaltopoClient.GetOperationalProfileOptions()
            .mapNotNull { fields ->
                if (fields.size < 4) return@mapNotNull null
                OperationalProfileUiOption(
                    profileId = fields[0],
                    credentialLabel = fields[1],
                    description = fields[2],
                    expiresAtEpochMs = fields[3].toLongOrNull() ?: 0L
                )
            }
        selectedOperationalProfileId = CaltopoClient.GetActiveCaltopoProfileId().orEmpty()
    }

    fun selectOperationalProfile(profileId: String) {
        onUIEvent(UIEvent.BrowseProfileSelected(profileId))
    }

    private fun performMapBrowserProfileSwitch(profileId: String) {
        if (profileId == CaltopoClient.GetActiveCaltopoProfileId()) return
        if (CaltopoMap.GetMapStatus() != CaltopoMap.MapStatusListener.mapStatus.down) {
            CaltopoMap.OpenMap(null)
        }
        if (CaltopoClient.SetActiveCaltopoProfileId(profileId, false)) {
            refreshMapBrowserProfiles()
            overlay = OverlayState.Connecting
            CaltopoMap.Init()
        }
    }

    fun confirmPendingProfileSwitch() {
        val pending = pendingProfileSwitch ?: return
        pendingProfileSwitch = null
        performMapBrowserProfileSwitch(pending.profileId)
    }

    fun dismissPendingProfileSwitch() {
        pendingProfileSwitch = null
    }

    fun openConnectionOverlayFromCurrentScreen() {
        val currentScreen = _activeScreen.value
        screenBeforeConnectionOverlay = if (currentScreen != ActiveScreen.MAIN) {
            currentScreen
        } else {
            null
        }
        if (currentScreen != ActiveScreen.MAIN) {
            CTDebug(tag, "openConnectionOverlayFromCurrentScreen(): $currentScreen -> ${ActiveScreen.MAIN}")
            showMain()
        }
        onUIEvent(UIEvent.HeaderClicked)
    }

    fun onUIEvent(uiEvent: UIEvent) {
        val previousOverlay = overlay
        val oldState = "      PRE: Overlay='${overlay}' ConnectionState: '${connectionState}'"

        when (uiEvent) {
            is UIEvent.HeaderClicked -> {
                refreshMapBrowserProfiles()
                overlay = when (connectionState) {
                    is CaltopoConnectionState.StandAlone -> OverlayState.ConnectionSetup
                    is CaltopoConnectionState.NoNetwork -> {
                        if (R2CMqttManager.GetMyIpAddress().isEmpty()) {
                            OverlayState.ConnectionSetup
                        } else {
                            OverlayState.None
                        }
                    }
                    is CaltopoConnectionState.CredentialsLoaded -> OverlayState.MapBrowser
                    is CaltopoConnectionState.CredentialsVerified -> OverlayState.MapBrowser
                    is CaltopoConnectionState.MapSelected -> OverlayState.Management
                    else -> overlay // do nothing if busy
                }
            }
            is UIEvent.NotAbleToReadConfigFile -> {
                CaltopoClient.ShowToast("Not able to read Config File")
                overlay = OverlayState.None
                connectionState = CaltopoConnectionState.StandAlone
            }
            is UIEvent.ConfigFileLoaded -> {
                if (overlay is OverlayState.None) {
                    // then just loaded a configfile independent of our fancy widget.
                } else {
                    if (this.hasCredentials) {
                        CTDebug(
                            tag,
                            "onUIEvent(${uiEvent}): have credentials, fetching team info..."
                        )
                        overlay = OverlayState.Connecting
                        CaltopoMap.Init() // Use credentials to fetch/update the CaltopoNode List
                    } else {
                        CTDebug(
                            tag,
                            "onUIEvent(${uiEvent}): No credentials loaded requesting credentials..."
                        )
                        overlay = OverlayState.RequestConfigFile
                    }
                }
            }
            is UIEvent.ConnectionRequested -> {
                refreshMapBrowserProfiles()
                if (R2CMqttManager.GetMyIpAddress().isEmpty()) {
                    connectionState = CaltopoConnectionState.NoNetwork
                } else {
                    if (this.hasCredentials) {
                        overlay = OverlayState.Connecting
                        CaltopoMap.Init() // Use credentials to fetch/update the CaltopoNode List
                    } else {
                        // We don't have creds, go get the file
                        overlay = OverlayState.RequestConfigFile
                    }
                }
            }

            is UIEvent.SwitchMapRequested -> {
                refreshMapBrowserProfiles()
                overlay = OverlayState.MapBrowser // Close the management dialog and open the browser
            }

            is UIEvent.BrowseProfileSelected -> {
                if (uiEvent.profileId != CaltopoClient.GetActiveCaltopoProfileId()) {
                    val activeFlightCount = CaltopoClient.GetActiveFlightCount()
                    val targetOption = mapBrowserProfiles.firstOrNull { it.profileId == uiEvent.profileId }
                    if (activeFlightCount > 0 &&
                        CaltopoMap.GetMapStatus() == CaltopoMap.MapStatusListener.mapStatus.up) {
                        pendingProfileSwitch = PendingProfileSwitchUiState(
                            profileId = uiEvent.profileId,
                            label = targetOption?.label ?: uiEvent.profileId,
                            activeFlightCount = activeFlightCount
                        )
                    } else {
                        performMapBrowserProfileSwitch(uiEvent.profileId)
                    }
                }
            }

            is UIEvent.DisconnectRequested -> {
                overlay = OverlayState.None
                CaltopoMap.OpenMap(null) // Java call to drop connection
                // Note: connectionState will update via the MapStatusListener callback
            }

            is UIEvent.MapSelected -> {
                CaltopoMap.OpenMap(uiEvent.map)
            }

            is UIEvent.ConnectionStatusChanged -> {
                // Directly map the Java Enum to our Sealed Class state
                val status = uiEvent.mapStatus
                CTDebug(tag, "onUIEvent(${uiEvent}): ConnectionStatus: '${status.name}'")
                connectionState = when (status) {
                    CaltopoMap.MapStatusListener.mapStatus.credentialsVerified -> {
                        mapHierarchy = CaltopoMap.GetSessionNodeMap()
                        refreshMapBrowserProfiles()
                        overlay = OverlayState.MapBrowser
                        CaltopoConnectionState.CredentialsVerified
                    }
                    CaltopoMap.MapStatusListener.mapStatus.up -> {
                        if (previousOverlay is OverlayState.Connecting) {
                            overlay = OverlayState.None
                        }
                        CaltopoConnectionState.MapSelected(CaltopoMap.GetMapNode())
                    }
                    CaltopoMap.MapStatusListener.mapStatus.down -> {
                        overlay = OverlayState.None
                        CaltopoConnectionState.StandAlone
                    }
                    CaltopoMap.MapStatusListener.mapStatus.connecting -> {
                        overlay = OverlayState.Connecting
                        CaltopoConnectionState.Connecting
                    }
                }
            }

            is UIEvent.DismissRequested -> {
                connectionState = CaltopoConnectionState.StandAlone
                overlay = OverlayState.None
            }
        }

        val newState = "     POST: Overlay='${overlay}' ConnectionState: '${connectionState}'"
        CTDebug(tag, "onUIEvent(${uiEvent})\n$oldState\n$newState")
        restoreScreenAfterConnectionOverlay(uiEvent, previousOverlay)
    }

    override fun mapStatusUpdate(status: CaltopoMap.MapStatusListener.mapStatus, mapNode: CaltopoNode.MapNode?, optErrmsg: String?) {
        viewModelScope.launch(Dispatchers.Main) {
            onUIEvent(UIEvent.ConnectionStatusChanged(status))
        }
    }

    fun showStreams() {
        CTDebug(tag, "showStreams(): ${_activeScreen.value} -> ${ActiveScreen.STREAMS}")
        _activeScreen.value = ActiveScreen.STREAMS
    }
    fun showMain() {
        CTDebug(tag, "showMain(): ${_activeScreen.value} -> ${ActiveScreen.MAIN}")
        _activeScreen.value = ActiveScreen.MAIN
    }
    fun showSettings() {
        CTDebug(tag, "showSettings(): ${_activeScreen.value} -> ${ActiveScreen.SETTINGS}")
        _activeScreen.value = ActiveScreen.SETTINGS
    }
    fun showScanner() {
        CTDebug(tag, "showScanner(): ${_activeScreen.value} -> ${ActiveScreen.SCANNER}")
        _activeScreen.value = ActiveScreen.SCANNER
    }
    // Clean up the listener when the ViewModel is no longer in use.
    override fun onCleared() {
        super.onCleared()
        CaltopoMap.RemoveMapStatusListener(this)
        CaltopoLiveTrack.RemoveLocalTrackListener(this)
        CaltopoLiveTrack.RemoveLocalTrackFinishedListener(this)
        delayedUptimePoll.stop()
    }

    override fun onLocalTrackPoint(
        remoteId: String,
        mappedId: String,
        lat: Double,
        lng: Double,
        altitudeMeters: Double,
        timestampMsec: Long
    ) {
        if (canPublishLocalTrackPointInline()) {
            publishLocalTrackPointToUi(remoteId, mappedId, timestampMsec)
        } else {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                publishLocalTrackPointToUi(remoteId, mappedId, timestampMsec)
            }
        }
    }

    private fun canPublishLocalTrackPointInline(): Boolean {
        val mainLooper = Looper.getMainLooper() ?: return true
        return Looper.myLooper() == mainLooper
    }

    private fun publishLocalTrackPointToUi(
        remoteId: String,
        mappedId: String,
        timestampMsec: Long
    ) {
        val drone = CaltopoClient.GetDroneSpec(remoteId) ?: return
        val currentDrones = _drones.value
        val nextDrones = (currentDrones.filterNot { it.remoteId == remoteId } + drone)
            .sortedWith { left, right -> left.compareToAge(right) }
        CTDebug(
            tag,
            "onLocalTrackPoint(): publishing screen update remoteId=$remoteId mappedId=$mappedId " +
                "count=${nextDrones.size} droneTs=$timestampMsec"
        )
        onDroneSpecsChanged(nextDrones)
    }

    override fun onLocalTrackFinished(remoteId: String, mappedId: String, reason: String) {
        clearFinishedFlightConfirmationState(remoteId, "local track finished: $reason")
    }

    fun requestDroneConfirmation(drone: CtDroneSpec) {
        val remoteId = currentFlightRemoteId(drone)
        if (remoteId != null) {
            promptedCurrentFlightRemoteIds.add(remoteId)
        }
        CTDebug(
            tag,
            "requestDroneConfirmation(): remoteId=${drone.remoteId} active=${drone.isActive} mappedId='${drone.mappedId}'"
        )
        _pendingDroneConfirmation.value = buildConfirmationState(drone)
        pendingDroneConfirmationRequestedByOperator = true
        screenBeforeConfirmation = null
        refreshPendingDroneConfirmationValidation()
    }

    fun updatePendingDroneConfirmation(
        organization: String? = null,
        pilotCallsign: String? = null,
        droneDescription: String? = null
    ) {
        val current = _pendingDroneConfirmation.value ?: return
        _pendingDroneConfirmation.value = current.copy(
            organization = organization ?: current.organization,
            pilotCallsign = pilotCallsign ?: current.pilotCallsign,
            droneDescription = droneDescription ?: current.droneDescription
        )
        refreshPendingDroneConfirmationValidation()
    }

    /**
     * The operator doesn't recognise this drone. Keep logging local waypoints for this flight,
     * but suppress CalTopo ownership/publishing and tracker-site reporting.
     * The active remoteId was already added to [promptedCurrentFlightRemoteIds] when the
     * dialog was first shown, so the panel will not reappear until this active lifecycle ends.
     */
    fun markPendingDroneConfirmationUnknown() {
        val current = _pendingDroneConfirmation.value ?: return
        val remoteId = current.remoteId.trim()
        CTDebug(tag, "markPendingDroneConfirmationUnknown(): remoteId=$remoteId")
        CaltopoClient.SaveDroneSpecUnknownConfirmation(remoteId)
        _pendingDroneConfirmation.value = null
        pendingDroneConfirmationRequestedByOperator = false
        restoreScreenAfterConfirmation()
    }

    fun savePendingDroneConfirmation() {
        val current = _pendingDroneConfirmation.value ?: return
        val remoteId = current.remoteId.trim()
        val organization = current.organization.trim()
        val callsign = current.pilotCallsign.trim()
        val droneDescription = current.droneDescription.trim()
        CTDebug(
            tag,
            "savePendingDroneConfirmation(): requested remoteId=$remoteId org='$organization' callsign='$callsign' model='$droneDescription'"
        )
        if (remoteId.isEmpty() || organization.isEmpty() || callsign.isEmpty() || droneDescription.isEmpty()) {
            CTDebug(
                tag,
                "savePendingDroneConfirmation(): blocked by empty field remoteIdEmpty=${remoteId.isEmpty()} orgEmpty=${organization.isEmpty()} callsignEmpty=${callsign.isEmpty()} modelEmpty=${droneDescription.isEmpty()}"
            )
            return
        }
        findActivePilotCallsignConflict(remoteId, callsign)?.let { conflict ->
            CTWarn(
                tag,
                "savePendingDroneConfirmation(): allowing pilot callsign conflict remoteId=$remoteId callsign='$callsign' conflictRemoteId=${conflict.remoteId}"
            )
        }
        if (current.usesUnknownOrganizationDefault) {
            lastUnknownDroneConfirmationOrganization = organization
        }
        val existingMappedId = CaltopoClient.GetDroneSpec(remoteId)?.mappedId?.trim().orEmpty()
        val mappedId = if (DroneSpecConfirmationLogic.shouldPreserveMappedId(
                existingMappedId = existingMappedId,
                remoteId = remoteId,
                initialPilotCallsign = current.initialPilotCallsign,
                savedPilotCallsign = callsign,
                initialDroneDescription = current.initialDroneDescription,
                savedDroneDescription = droneDescription
            )) {
            // Pilot callsign and description are presentation fields. Preserve the explicit
            // stream routing designator even when either field changes.
            existingMappedId
        } else {
            CtDroneSpec.BuildMappedId(callsign, droneDescription, remoteId)
        }
        val peerCoordinator = R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator
        CTDebug(
            tag,
            "savePendingDroneConfirmation(): notifying peer remoteId=$remoteId mappedId='$mappedId' peer=${peerCoordinator.javaClass.simpleName}"
        )
        confirmedCurrentFlightRemoteIds.add(remoteId)
        CTDebug(tag, "savePendingDroneConfirmation(): saving local confirmation remoteId=$remoteId mappedId='$mappedId'")
        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId,
            organization,
            droneDescription,
            callsign,
            mappedId
        )
        peerCoordinator.onDroneConfirmed(
            remoteId,
            organization,
            droneDescription,
            callsign,
            mappedId
        )
        _pendingDroneConfirmation.value = null
        pendingDroneConfirmationRequestedByOperator = false
        restoreScreenAfterConfirmation()
    }

    fun housekeeping() {
        _appUptime.value = uptimeTimer.durationAsString()
        refreshOperationalProfiles()
        recoverMapBrowserAfterCredentialRestore()
        val newDeviceName = R2CActivity.MyDeviceName
                if (_hostname.value.isEmpty() || _hostname.value != newDeviceName) {
            _hostname.value = newDeviceName
        }
    }

    private fun recoverMapBrowserAfterCredentialRestore() {
        if (!shouldResumeMapConnectionAfterCredentialRestore(overlay, hasCredentials) ||
            !credentialRecoveryScheduled.compareAndSet(false, true)
        ) {
            return
        }
        viewModelScope.launch(Dispatchers.Main) {
            try {
                if (shouldResumeMapConnectionAfterCredentialRestore(overlay, hasCredentials)) {
                    CTDebug(
                        tag,
                        "Managed CalTopo credentials restored; resuming map selection automatically"
                    )
                    onUIEvent(UIEvent.ConfigFileLoaded)
                }
            } finally {
                credentialRecoveryScheduled.set(false)
            }
        }
    }

    /* We only need the uptimePoll() before there are dronespecs.
     * Once there are dronespecs, we will receive frequent updates.
     */
    @Synchronized
    override fun onDroneSpecsChanged(droneSpecs: List<CtDroneSpec>) {
        val droneListSnapshot = normalizedDroneSpecUiSnapshot(droneSpecs, tag)
        if (lastDroneListSignature != droneListSnapshot.signature) {
            lastDroneListSignature = droneListSnapshot.signature
            CTDebug(
                tag,
                "DroneItem list update: count=${droneListSnapshot.drones.size} ids=" +
                    droneListSnapshot.drones.joinToString { "${it.remoteId}:${it.mappedId}" }
            )
            _drones.value = DroneSpecUiList(droneListSnapshot.drones)
        }
        housekeeping()
        refreshPendingDroneConfirmationValidation()
        val activeRemoteIds = droneSpecs.mapNotNullTo(linkedSetOf()) { drone ->
            currentFlightRemoteId(drone)
        }
        promptedCurrentFlightRemoteIds.retainAll(activeRemoteIds)
        activeRemoteIds
            .filter { CaltopoClient.IsCurrentPeerDroneConfirmed(it) }
            .forEach { confirmedCurrentFlightRemoteIds.add(it) }
        var pendingRemoteId = _pendingDroneConfirmation.value?.remoteId
        if (
            pendingRemoteId != null &&
            pendingRemoteId in confirmedCurrentFlightRemoteIds &&
            !pendingDroneConfirmationRequestedByOperator
        ) {
            CTDebug(tag, "Clearing pending confirmation for $pendingRemoteId: confirmed by peer")
            _pendingDroneConfirmation.value = null
            pendingDroneConfirmationRequestedByOperator = false
            restoreScreenAfterConfirmation()
            pendingRemoteId = null
        }
        val pendingStillActive = droneSpecs.any { drone ->
            pendingRemoteId == drone.remoteId && currentFlightRemoteId(drone) != null
        }
        if (!pendingStillActive) {
            if (pendingRemoteId != null) {
                CTDebug(tag, "Clearing pending confirmation for $pendingRemoteId: no longer active/eligible")
            }
            _pendingDroneConfirmation.value = null
            pendingDroneConfirmationRequestedByOperator = false
        }
        if (_pendingDroneConfirmation.value == null) {
            for (drone in droneSpecs) {
                if (currentFlightRemoteId(drone) != null &&
                    queueConfirmationIfNeeded(drone, "active flight")
                ) {
                    break
                }
            }
        }
        if (droneSpecs.isEmpty()) {
            if (!delayedUptimePoll.isRunning) {
                delayedUptimePoll.start(this::uptimePoll, 1000, 1000)
            }
        } else if (delayedUptimePoll.isRunning) {
            delayedUptimePoll.stop()
        }
    }

    fun uptimePoll() {
        housekeeping()
    }

    @Synchronized
    override fun onDroneConfirmationCandidate(droneSpec: CtDroneSpec) {
        queueConfirmationIfNeeded(droneSpec, "first RID sighting")
    }

    /** Active flight state is scoped by remoteId. */
    private fun currentFlightRemoteId(drone: CtDroneSpec): String? {
        if (!drone.isActive) return null
        return drone.remoteId.takeIf { it.isNotBlank() }
    }

    private fun queueConfirmationIfNeeded(drone: CtDroneSpec, reason: String): Boolean {
        if (_pendingDroneConfirmation.value != null) return false
        val remoteId = drone.remoteId.takeIf { it.isNotBlank() } ?: return false
        // A saved confirmation is scoped to the current flight. The track lifecycle keeps a
        // flight active across RID loss while SEI position is live or the drone is distant.
        if (remoteId in promptedCurrentFlightRemoteIds ||
            CaltopoClient.IsSessionUnknownDrone(remoteId) ||
            drone.isLocalArchiveOnly ||
            remoteId in confirmedCurrentFlightRemoteIds ||
            CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId)
        ) {
            return false
        }
        CTDebug(
            tag,
            "Queueing confirmation for ${drone.remoteId}: " +
                "mappedId=${drone.mappedId} " +
                "org='${drone.org}' model='${drone.model}' owner='${drone.owner}' reason=$reason"
        )
        promptedCurrentFlightRemoteIds.add(remoteId)
        _pendingDroneConfirmation.value = buildConfirmationState(drone)
        pendingDroneConfirmationRequestedByOperator = false
        refreshPendingDroneConfirmationValidation()
        if (_activeScreen.value == ActiveScreen.STREAMS) {
            screenBeforeConfirmation = _activeScreen.value
            CaltopoClient.ShowToast("New drone needs confirmation. Returning to main screen.")
            showMain()
        } else {
            screenBeforeConfirmation = null
        }
        return true
    }

    private fun clearInactivePromptOnly(remoteId: String, reason: String) {
        val trimmedRemoteId = remoteId.trim()
        if (trimmedRemoteId.isEmpty()) return
        val removedPrompt = promptedCurrentFlightRemoteIds.remove(trimmedRemoteId)
        if (_pendingDroneConfirmation.value?.remoteId == trimmedRemoteId) {
            CTDebug(tag, "Clearing pending confirmation for $trimmedRemoteId: $reason")
            _pendingDroneConfirmation.value = null
            pendingDroneConfirmationRequestedByOperator = false
            restoreScreenAfterConfirmation()
        } else if (removedPrompt) {
            CTDebug(tag, "Clearing inactive undecided prompt state for $trimmedRemoteId: $reason")
        }
    }

    private fun clearFinishedFlightConfirmationState(remoteId: String, reason: String) {
        val trimmedRemoteId = remoteId.trim()
        if (trimmedRemoteId.isEmpty()) return
        confirmedCurrentFlightRemoteIds.remove(trimmedRemoteId)
        CaltopoClient.ClearCurrentPeerDroneConfirmation(trimmedRemoteId)
        clearInactivePromptOnly(trimmedRemoteId, reason)
    }

    private fun buildConfirmationState(drone: CtDroneSpec): DroneSpecConfirmationUiState {
        return validatePendingDroneConfirmation(
            DroneSpecConfirmationLogic.buildInitialState(
                drone = drone,
                defaultOrganization = CaltopoClient.GetHomeOrgName(),
                defaultUnknownOrganization = lastUnknownDroneConfirmationOrganization,
                defaultPilotCallsign = R2CApplication.getAppCtxt()
                    ?.let(TabletPilotCallsignPrefs::load)
                    .orEmpty()
            )
        )
    }


    private fun refreshPendingDroneConfirmationValidation() {
        _pendingDroneConfirmation.value = _pendingDroneConfirmation.value
            ?.let(::validatePendingDroneConfirmation)
    }

    private fun validatePendingDroneConfirmation(
        state: DroneSpecConfirmationUiState
    ): DroneSpecConfirmationUiState {
        val callsign = state.pilotCallsign.trim()
        val conflict = findActivePilotCallsignConflict(state.remoteId, callsign)
        return state.copy(
            pilotCallsignWarning = conflict?.let { pilotCallsignConflictMessage(callsign, it) }
        )
    }

    private fun findActivePilotCallsignConflict(
        remoteId: String,
        pilotCallsign: String
    ): CtDroneSpec? {
        val normalizedPilot = pilotCallsign.trim()
        if (normalizedPilot.isEmpty()) return null
        return _drones.value.firstOrNull { drone ->
            drone.remoteId != remoteId &&
                drone.isActive &&
                drone.owner.trim().equals(normalizedPilot, ignoreCase = true)
        }
    }

    private fun pilotCallsignConflictMessage(
        pilotCallsign: String,
        conflict: CtDroneSpec
    ): String {
        val label = conflict.mappedId
            .takeIf { it.isNotBlank() && it != conflict.remoteId }
            ?: conflict.remoteId
        return "Warning: pilot callsign $pilotCallsign is already assigned to active drone $label. Confirm only if this is intentional."
    }

    private fun hasKnownDroneSpec(drone: CtDroneSpec): Boolean {
        val cached = CaltopoClient.GetDroneSpec(drone.remoteId)
        return hasMeaningfulDroneSpec(drone) || (cached != null && hasMeaningfulDroneSpec(cached))
    }

    private fun restoreScreenAfterConfirmation() {
        val priorScreen = screenBeforeConfirmation
        screenBeforeConfirmation = null
        if (priorScreen != null && _activeScreen.value == ActiveScreen.MAIN && priorScreen != ActiveScreen.MAIN) {
            CTDebug(tag, "restoreScreenAfterConfirmation(): MAIN -> $priorScreen")
            _activeScreen.value = priorScreen
        }
    }

    private fun restoreScreenAfterConnectionOverlay(
        uiEvent: UIEvent,
        previousOverlay: OverlayState
    ) {
        val shouldRestore = when (uiEvent) {
            is UIEvent.DismissRequested,
            is UIEvent.DisconnectRequested -> overlay == OverlayState.None
            is UIEvent.ConnectionStatusChanged ->
                previousOverlay is OverlayState.Connecting && overlay == OverlayState.None
            else -> false
        }
        if (!shouldRestore) return
        val priorScreen = screenBeforeConnectionOverlay
        screenBeforeConnectionOverlay = null
        if (priorScreen != null && _activeScreen.value == ActiveScreen.MAIN && priorScreen != ActiveScreen.MAIN) {
            CTDebug(tag, "restoreScreenAfterConnectionOverlay(): MAIN -> $priorScreen")
            _activeScreen.value = priorScreen
        }
    }

    private fun hasMeaningfulDroneSpec(drone: CtDroneSpec): Boolean {
        val mappedId = drone.mappedId.trim()
        return drone.org.isNotBlank() ||
            drone.model.isNotBlank() ||
            drone.owner.isNotBlank() ||
            (mappedId.isNotEmpty() && mappedId != drone.remoteId)
    }
}

internal fun shouldResumeMapConnectionAfterCredentialRestore(
    overlay: OverlayState,
    hasCredentials: Boolean
): Boolean = overlay is OverlayState.RequestConfigFile && hasCredentials

class R2CViewModelFactory(private val uptimeTimer: SimpleTimer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(R2CViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return R2CViewModel(uptimeTimer) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

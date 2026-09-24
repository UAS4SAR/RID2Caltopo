/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.ncssar.rid2caltopo.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.app.MediaMTXService
import org.ncssar.rid2caltopo.app.R2CApplication
import org.ncssar.rid2caltopo.app.ScanningService
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoCredentials
import org.ncssar.rid2caltopo.data.ExternalDisplayAlertRouting
import org.ncssar.rid2caltopo.data.ExternalDisplayConfig
import org.ncssar.rid2caltopo.data.ExternalDisplayContentMode
import org.ncssar.rid2caltopo.data.ExternalDisplayMode
import org.ncssar.rid2caltopo.data.ExternalDisplayPrefs
import org.ncssar.rid2caltopo.data.MediaServerAccessPrefs
import org.ncssar.rid2caltopo.data.RemoteVideoControlPrefs
import org.ncssar.rid2caltopo.data.VideoThumbnailRefreshPolicy
import org.ncssar.rid2caltopo.data.VideoThumbnailRefreshPrefs
import org.ncssar.rid2caltopo.data.WifiRidScanPrefs
import org.ncssar.rid2caltopo.data.RidLocationAccuracyPrefs
import org.ncssar.rid2caltopo.notam.NotamAuthManager
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter

internal enum class CaltopoCredentialFieldState {
    BLANK,
    COMPLETE,
    PARTIAL,
}

internal fun caltopoCredentialFieldState(teamId: String, credentialId: String, secret: String): CaltopoCredentialFieldState {
    val populatedCount = listOf(teamId, credentialId, secret).count { it.trim().isNotEmpty() }
    return when (populatedCount) {
        0 -> CaltopoCredentialFieldState.BLANK
        3 -> CaltopoCredentialFieldState.COMPLETE
        else -> CaltopoCredentialFieldState.PARTIAL
    }
}

class CaltopoSettingsViewModel : ViewModel(), CaltopoClient.ClientSettingsListener {
    private var isSaving = false

    // --- Live Data for UI --- //

    private val _organizationName = MutableStateFlow(CaltopoClient.GetHomeOrgName())
    val organizationName = _organizationName.asStateFlow()
    private val _trackFolder = MutableStateFlow(CaltopoClient.GetTrackFolderName())
    val trackFolder = _trackFolder.asStateFlow()

    private val _minDistance = MutableStateFlow(CaltopoClient.GetMinDistanceInFeet().toString())
    val minDistance = _minDistance.asStateFlow()

    private val _newTrackDelay = MutableStateFlow(CaltopoClient.GetNewTrackDelayInSeconds().toString())
    val newTrackDelay = _newTrackDelay.asStateFlow()

    private val _minimumLocationAccuracyCode = MutableStateFlow(
        RidLocationAccuracyPrefs.getMinimumCode(R2CApplication.getAppCtxt())
    )
    val minimumLocationAccuracyCode = _minimumLocationAccuracyCode.asStateFlow()

    private val _maxFlatlineToneDuration = MutableStateFlow(CaltopoClient.GetMaxFlatlineToneDurationInSeconds().toString())
    val maxFlatlineToneDuration = _maxFlatlineToneDuration.asStateFlow()

    private val _bridgeCheckDistanceFeet = MutableStateFlow(CaltopoClient.GetBridgeCheckDistanceFeet().toString())
    val bridgeCheckDistanceFeet = _bridgeCheckDistanceFeet.asStateFlow()

    private val _alarmVolumePercent = MutableStateFlow(CaltopoClient.GetAlarmVolumePercent())
    val alarmVolumePercent = _alarmVolumePercent.asStateFlow()

    private val _usePeers = MutableStateFlow(CaltopoClient.GetUsePeersFlag())
    val usePeers = _usePeers.asStateFlow()

    private val _standaloneR2cCoordinationEnabled = MutableStateFlow(CaltopoClient.GetStandaloneR2cCoordinationEnabled())
    val standaloneR2cCoordinationEnabled = _standaloneR2cCoordinationEnabled.asStateFlow()

    private val _captureIncomingVideo = MutableStateFlow(CaltopoClient.GetCaptureVideoStreamsFlag())
    val captureIncomingVideo = _captureIncomingVideo.asStateFlow()
    private val _mediaServerRestricted = MutableStateFlow(MediaServerAccessPrefs.isRestricted(R2CApplication.getAppCtxt()))
    val mediaServerRestricted = _mediaServerRestricted.asStateFlow()
    private val _wifiRidScanningEnabled = MutableStateFlow(
        WifiRidScanPrefs.isEnabled(R2CApplication.getAppCtxt())
    )
    val wifiRidScanningEnabled = _wifiRidScanningEnabled.asStateFlow()
    private val _remoteVideoControlEnabled = MutableStateFlow(
        RemoteVideoControlPrefs.isEnabled(R2CApplication.getAppCtxt())
    )
    val remoteVideoControlEnabled = _remoteVideoControlEnabled.asStateFlow()
    private val _thumbnailRefreshSeconds = MutableStateFlow(
        VideoThumbnailRefreshPolicy.format(
            VideoThumbnailRefreshPrefs.getSeconds(R2CApplication.getAppCtxt())
        )
    )
    val thumbnailRefreshSeconds = _thumbnailRefreshSeconds.asStateFlow()
    private val _predictiveHeadEnabled = MutableStateFlow(CaltopoClient.GetPredictiveHeadEnabled())
    val predictiveHeadEnabled = _predictiveHeadEnabled.asStateFlow()
    private val _proximityAlertSpacingFeet = MutableStateFlow(CaltopoClient.GetProximityAlertSpacingFeet().toString())
    val proximityAlertSpacingFeet = _proximityAlertSpacingFeet.asStateFlow()

    private val _maxIdleTimeInMinutes = MutableStateFlow(CaltopoClient.GetMaxIdleTimeInMinutes().toString())
    val maxIdleTimeInMinutes = _maxIdleTimeInMinutes.asStateFlow()

    private val _incident = MutableStateFlow( CaltopoClient.GetIncident())
    val incident = _incident.asStateFlow()

    private val _opPeriod = MutableStateFlow( CaltopoClient.GetOpPeriod())
    val opPeriod = _opPeriod.asStateFlow()

    private val _caltopoDomainAndPort = MutableStateFlow( CaltopoClient.GetCaltopoDomainAndPort())
    val caltopoUrl = _caltopoDomainAndPort.asStateFlow()
    private val initialCaltopoCredentials = CaltopoClient.GetCaltopoCredentials()
    private val _caltopoTeamId = MutableStateFlow(initialCaltopoCredentials.teamId ?: "")
    val caltopoTeamId = _caltopoTeamId.asStateFlow()
    private val _caltopoCredentialId = MutableStateFlow(initialCaltopoCredentials.credentialId ?: "")
    val caltopoCredentialId = _caltopoCredentialId.asStateFlow()
    private val _caltopoCredentialSecret = MutableStateFlow(initialCaltopoCredentials.credentialSecret ?: "")
    val caltopoCredentialSecret = _caltopoCredentialSecret.asStateFlow()
    private val _caltopoConnectKey = MutableStateFlow(CaltopoClient.GetConnectKey())
    val caltopoConnectKey = _caltopoConnectKey.asStateFlow()
    private val _caltopoCredentialError = MutableStateFlow<String?>(null)
    val caltopoCredentialError = _caltopoCredentialError.asStateFlow()

    private val _trackerUrl = MutableStateFlow(CaltopoClient.GetHomeTrackerUrlPfx())
    val trackerUrl = _trackerUrl.asStateFlow()
    private val _trackerApiKey = MutableStateFlow(CaltopoClient.GetHomeTrackerApiKey())
    val trackerApiKey = _trackerApiKey.asStateFlow()
    private var trackerManuallyEdited = false

    private val _mutualAidTeamId = MutableStateFlow(CaltopoClient.GetMutualAidTemplateTeamId())
    val mutualAidTeamId = _mutualAidTeamId.asStateFlow()
    private val _mutualAidCredentialId = MutableStateFlow(CaltopoClient.GetMutualAidTemplateCredentialId())
    val mutualAidCredentialId = _mutualAidCredentialId.asStateFlow()
    private val _mutualAidCredentialSecret = MutableStateFlow(CaltopoClient.GetMutualAidTemplateCredentialSecret())
    val mutualAidCredentialSecret = _mutualAidCredentialSecret.asStateFlow()
    private val _mutualAidDomain = MutableStateFlow(CaltopoClient.GetMutualAidTemplateDomainAndPort())
    val mutualAidDomain = _mutualAidDomain.asStateFlow()
    private val _mutualAidSourceLabel = MutableStateFlow(CaltopoClient.GetMutualAidTemplateSourceLabel())
    val mutualAidSourceLabel = _mutualAidSourceLabel.asStateFlow()
    private val _mutualAidTargetFolder = MutableStateFlow(CaltopoClient.GetMutualAidTemplateTargetFolderHint())
    val mutualAidTargetFolder = _mutualAidTargetFolder.asStateFlow()
    private val _mutualAidConnectKey = MutableStateFlow(CaltopoClient.GetMutualAidTemplateConnectKey())
    val mutualAidConnectKey = _mutualAidConnectKey.asStateFlow()

    private val _notamEnabled = MutableStateFlow(CaltopoClient.GetNotamEnabled())
    val notamEnabled = _notamEnabled.asStateFlow()

    private val _notamRadiusNm = MutableStateFlow(CaltopoClient.GetNotamRadiusNm().toString())
    val notamRadiusNm = _notamRadiusNm.asStateFlow()

    private val _notamRefreshIntervalSeconds = MutableStateFlow(CaltopoClient.GetNotamRefreshIntervalSeconds().toString())
    val notamRefreshIntervalSeconds = _notamRefreshIntervalSeconds.asStateFlow()

    private val _notamAutoRefresh = MutableStateFlow(CaltopoClient.GetNotamAutoRefresh())
    val notamAutoRefresh = _notamAutoRefresh.asStateFlow()

    private val _notamStatus = MutableStateFlow(buildNotamStatus())
    val notamStatus = _notamStatus.asStateFlow()

    private val _landRestrictionsEnabled = MutableStateFlow(CaltopoClient.GetLandRestrictionsEnabled())
    val landRestrictionsEnabled = _landRestrictionsEnabled.asStateFlow()
    private val _landRestrictionsShowOnMap = MutableStateFlow(CaltopoClient.GetLandRestrictionsShowOnMap())
    val landRestrictionsShowOnMap = _landRestrictionsShowOnMap.asStateFlow()
    private val _landRestrictionsAutoRefresh = MutableStateFlow(CaltopoClient.GetLandRestrictionsAutoRefresh())
    val landRestrictionsAutoRefresh = _landRestrictionsAutoRefresh.asStateFlow()
    private val _landRestrictionsRadiusNm = MutableStateFlow(CaltopoClient.GetLandRestrictionsRadiusNm().toString())
    val landRestrictionsRadiusNm = _landRestrictionsRadiusNm.asStateFlow()

    private val initialExternalConfig = R2CApplication.getAppCtxt()?.let { ExternalDisplayPrefs.load(it) }
        ?: ExternalDisplayConfig()
    private val _externalDisplayMode = MutableStateFlow(initialExternalConfig.mode)
    val externalDisplayMode = _externalDisplayMode.asStateFlow()
    private val _externalDisplayAutoOpen = MutableStateFlow(initialExternalConfig.autoOpenOnConnect)
    val externalDisplayAutoOpen = _externalDisplayAutoOpen.asStateFlow()
    private val _externalDisplayReturnToPhoneOnly = MutableStateFlow(initialExternalConfig.returnToPhoneOnlyLayoutOnDisconnect)
    val externalDisplayReturnToPhoneOnly = _externalDisplayReturnToPhoneOnly.asStateFlow()
    private val _externalDisplayAllowInteraction = MutableStateFlow(initialExternalConfig.allowInteraction)
    val externalDisplayAllowInteraction = _externalDisplayAllowInteraction.asStateFlow()
    private val _externalDisplayContentMode = MutableStateFlow(initialExternalConfig.contentMode)
    val externalDisplayContentMode = _externalDisplayContentMode.asStateFlow()
    private val _externalDisplayAlertRouting = MutableStateFlow(initialExternalConfig.alertRouting)
    val externalDisplayAlertRouting = _externalDisplayAlertRouting.asStateFlow()



    init {
        CaltopoClient.SetSettingsListener(this)
        settingsChanged() // load initial values.
    }

    override fun settingsChanged() {
        if (isSaving) return
        _organizationName.value = CaltopoClient.GetHomeOrgName()
        _trackFolder.value = CaltopoClient.GetTrackFolderName()
        _incident.value = CaltopoClient.GetIncident()
        _opPeriod.value = CaltopoClient.GetOpPeriod()
        _usePeers.value = CaltopoClient.GetUsePeersFlag()
        _standaloneR2cCoordinationEnabled.value = CaltopoClient.GetStandaloneR2cCoordinationEnabled()
        _captureIncomingVideo.value = CaltopoClient.GetCaptureVideoStreamsFlag()
        _mediaServerRestricted.value = MediaServerAccessPrefs.isRestricted(R2CApplication.getAppCtxt())
        _wifiRidScanningEnabled.value = WifiRidScanPrefs.isEnabled(R2CApplication.getAppCtxt())
        _remoteVideoControlEnabled.value = RemoteVideoControlPrefs.isEnabled(R2CApplication.getAppCtxt())
        _thumbnailRefreshSeconds.value = VideoThumbnailRefreshPolicy.format(
            VideoThumbnailRefreshPrefs.getSeconds(R2CApplication.getAppCtxt())
        )
        _predictiveHeadEnabled.value = CaltopoClient.GetPredictiveHeadEnabled()
        _proximityAlertSpacingFeet.value = CaltopoClient.GetProximityAlertSpacingFeet().toString()
        _newTrackDelay.value = CaltopoClient.GetNewTrackDelayInSeconds().toString()
        _maxFlatlineToneDuration.value = CaltopoClient.GetMaxFlatlineToneDurationInSeconds().toString()
        _bridgeCheckDistanceFeet.value = CaltopoClient.GetBridgeCheckDistanceFeet().toString()
        _alarmVolumePercent.value = CaltopoClient.GetAlarmVolumePercent()
        _minDistance.value = CaltopoClient.GetMinDistanceInFeet().toString()
        _maxIdleTimeInMinutes.value = CaltopoClient.GetMaxIdleTimeInMinutes().toString()
        _caltopoDomainAndPort.value = CaltopoClient.GetCaltopoDomainAndPort()
        CaltopoClient.GetCaltopoCredentials().let {
            _caltopoTeamId.value = it.teamId ?: ""
            _caltopoCredentialId.value = it.credentialId ?: ""
            _caltopoCredentialSecret.value = it.credentialSecret ?: ""
        }
        _caltopoConnectKey.value = CaltopoClient.GetConnectKey()
        _trackerUrl.value = CaltopoClient.GetHomeTrackerUrlPfx()
        _trackerApiKey.value = CaltopoClient.GetHomeTrackerApiKey()
        _mutualAidTeamId.value = CaltopoClient.GetMutualAidTemplateTeamId()
        _mutualAidCredentialId.value = CaltopoClient.GetMutualAidTemplateCredentialId()
        _mutualAidCredentialSecret.value = CaltopoClient.GetMutualAidTemplateCredentialSecret()
        _mutualAidDomain.value = CaltopoClient.GetMutualAidTemplateDomainAndPort()
        _mutualAidSourceLabel.value = CaltopoClient.GetMutualAidTemplateSourceLabel()
        _mutualAidTargetFolder.value = CaltopoClient.GetMutualAidTemplateTargetFolderHint()
        _mutualAidConnectKey.value = CaltopoClient.GetMutualAidTemplateConnectKey()
        _notamEnabled.value = CaltopoClient.GetNotamEnabled()
        _notamRadiusNm.value = CaltopoClient.GetNotamRadiusNm().toString()
        _notamRefreshIntervalSeconds.value = CaltopoClient.GetNotamRefreshIntervalSeconds().toString()
        _notamAutoRefresh.value = CaltopoClient.GetNotamAutoRefresh()
        _notamStatus.value = buildNotamStatus()
        _landRestrictionsEnabled.value = CaltopoClient.GetLandRestrictionsEnabled()
        _landRestrictionsShowOnMap.value = CaltopoClient.GetLandRestrictionsShowOnMap()
        _landRestrictionsAutoRefresh.value = CaltopoClient.GetLandRestrictionsAutoRefresh()
        _landRestrictionsRadiusNm.value = CaltopoClient.GetLandRestrictionsRadiusNm().toString()
        val externalConfig = R2CApplication.getAppCtxt()?.let { ExternalDisplayPrefs.load(it) }
            ?: ExternalDisplayConfig()
        _externalDisplayMode.value = externalConfig.mode
        _externalDisplayAutoOpen.value = externalConfig.autoOpenOnConnect
        _externalDisplayReturnToPhoneOnly.value = externalConfig.returnToPhoneOnlyLayoutOnDisconnect
        _externalDisplayAllowInteraction.value = externalConfig.allowInteraction
        _externalDisplayContentMode.value = externalConfig.contentMode
        _externalDisplayAlertRouting.value = externalConfig.alertRouting
    }

    // --- UI Event Handlers --- //

    fun onMinDistanceChanged(newMinDistance: String) {
        _minDistance.value = newMinDistance
    }

    fun onMinimumLocationAccuracyCodeChanged(code: Int) {
        _minimumLocationAccuracyCode.value = code.coerceIn(9, 12)
    }

    fun onOrganizationNameChanged(value: String) { _organizationName.value = value }
    fun onTrackFolderChanged(value: String) { _trackFolder.value = value }
    fun onIncidentChanged(value: String) { _incident.value = value }
    fun onOpPeriodChanged(value: String) { _opPeriod.value = value }
    fun onCaltopoTeamIdChanged(value: String) {
        _caltopoTeamId.value = value
        _caltopoCredentialError.value = null
    }
    fun onCaltopoCredentialIdChanged(value: String) {
        _caltopoCredentialId.value = value
        _caltopoCredentialError.value = null
    }
    fun onCaltopoCredentialSecretChanged(value: String) {
        _caltopoCredentialSecret.value = value
        _caltopoCredentialError.value = null
    }
    fun onCaltopoConnectKeyChanged(value: String) { _caltopoConnectKey.value = value }
    fun onTrackerUrlChanged(value: String) {
        _trackerUrl.value = value
        trackerManuallyEdited = true
    }
    fun onTrackerApiKeyChanged(value: String) {
        _trackerApiKey.value = value
        trackerManuallyEdited = true
    }
    fun onMutualAidTeamIdChanged(value: String) { _mutualAidTeamId.value = value }
    fun onMutualAidCredentialIdChanged(value: String) { _mutualAidCredentialId.value = value }
    fun onMutualAidCredentialSecretChanged(value: String) { _mutualAidCredentialSecret.value = value }
    fun onMutualAidDomainChanged(value: String) { _mutualAidDomain.value = value }
    fun onMutualAidSourceLabelChanged(value: String) { _mutualAidSourceLabel.value = value }
    fun onMutualAidTargetFolderChanged(value: String) { _mutualAidTargetFolder.value = value }
    fun onMutualAidConnectKeyChanged(value: String) { _mutualAidConnectKey.value = value }

    fun onNewTrackDelayChanged(newDelay: String) {
        _newTrackDelay.value = newDelay
    }

    fun onMaxFlatlineToneDurationChanged(newDuration: String) {
        _maxFlatlineToneDuration.value = newDuration
    }

    fun onBridgeCheckDistanceFeetChanged(newDistance: String) {
        _bridgeCheckDistanceFeet.value = newDistance
    }

    fun onAlarmVolumePercentChanged(percent: Int) {
        _alarmVolumePercent.value = CaltopoClient.SetAlarmVolumePercent(percent)
    }

    fun onMaxIdleTimeInMinutesChanged(newVal: String) {
        _maxIdleTimeInMinutes.value = newVal
    }

    fun onUsePeersChanged(usePeers: Boolean) {
        _usePeers.value = usePeers
    }
    fun onStandaloneR2cCoordinationEnabledChanged(enabled: Boolean) {
        _standaloneR2cCoordinationEnabled.value = enabled
    }
    fun onMediaServerRestrictedChanged(restricted: Boolean) {
        _mediaServerRestricted.value = restricted
    }
    fun onCaptureIncomingVideoChanged(enabled: Boolean) {
        _captureIncomingVideo.value = enabled
    }
    fun onWifiRidScanningEnabledChanged(enabled: Boolean) {
        _wifiRidScanningEnabled.value = enabled
    }
    fun onPredictiveHeadEnabledChanged(enabled: Boolean) {
        _predictiveHeadEnabled.value = enabled
    }
    fun onProximityAlertSpacingFeetChanged(feet: String) {
        _proximityAlertSpacingFeet.value = feet
    }
    fun onCaltopoDomainAndPortChanged(url: String) {
        _caltopoDomainAndPort.value = url
    }

    fun onNotamEnabledChanged(enabled: Boolean) {
        _notamEnabled.value = enabled
    }

    fun onNotamRadiusNmChanged(radiusNm: String) {
        _notamRadiusNm.value = radiusNm
    }

    fun onNotamRefreshIntervalSecondsChanged(seconds: String) {
        _notamRefreshIntervalSeconds.value = seconds
    }

    fun onNotamAutoRefreshChanged(enabled: Boolean) {
        _notamAutoRefresh.value = enabled
    }

    fun onLandRestrictionsEnabledChanged(enabled: Boolean) {
        _landRestrictionsEnabled.value = enabled
    }

    fun onLandRestrictionsShowOnMapChanged(enabled: Boolean) {
        _landRestrictionsShowOnMap.value = enabled
    }

    fun onLandRestrictionsAutoRefreshChanged(enabled: Boolean) {
        _landRestrictionsAutoRefresh.value = enabled
    }

    fun onLandRestrictionsRadiusNmChanged(radiusNm: String) {
        _landRestrictionsRadiusNm.value = radiusNm
    }

    fun onExternalDisplayModeChanged(mode: ExternalDisplayMode) {
        _externalDisplayMode.value = mode
    }

    fun onExternalDisplayAutoOpenChanged(enabled: Boolean) {
        _externalDisplayAutoOpen.value = enabled
    }

    fun onRemoteVideoControlEnabledChanged(enabled: Boolean) {
        _remoteVideoControlEnabled.value = enabled
    }

    fun onThumbnailRefreshSecondsChanged(seconds: String) {
        _thumbnailRefreshSeconds.value = seconds
    }

    fun onExternalDisplayReturnToPhoneOnlyChanged(enabled: Boolean) {
        _externalDisplayReturnToPhoneOnly.value = enabled
    }

    fun onExternalDisplayAllowInteractionChanged(enabled: Boolean) {
        _externalDisplayAllowInteraction.value = enabled
    }

    fun onExternalDisplayContentModeChanged(mode: ExternalDisplayContentMode) {
        _externalDisplayContentMode.value = mode
    }

    fun onExternalDisplayAlertRoutingChanged(routing: ExternalDisplayAlertRouting) {
        _externalDisplayAlertRouting.value = routing
    }

    fun saveSettings(): Boolean {
        val trimmedCaltopoTeamId = _caltopoTeamId.value.trim()
        val trimmedCaltopoCredentialId = _caltopoCredentialId.value.trim()
        val trimmedCaltopoCredentialSecret = _caltopoCredentialSecret.value.trim()
        val credentialFieldState = caltopoCredentialFieldState(
            trimmedCaltopoTeamId,
            trimmedCaltopoCredentialId,
            trimmedCaltopoCredentialSecret,
        )
        if (credentialFieldState == CaltopoCredentialFieldState.PARTIAL) {
            _caltopoCredentialError.value =
                "Enter Team ID, Credential ID, and Credential secret together, or leave all three blank."
            return false
        }
        _caltopoCredentialError.value = null
        isSaving = true
        try {
        val restartMediaMtx = CaltopoClient.GetCaptureVideoStreamsFlag() != _captureIncomingVideo.value ||
            MediaServerAccessPrefs.isRestricted(R2CApplication.getAppCtxt()) != _mediaServerRestricted.value
        CaltopoClient.SetHomeOrgName(_organizationName.value.trim())
        CaltopoClient.SetConnectKey(_caltopoConnectKey.value.trim())
        CaltopoClient.SetTrackFolderName(_trackFolder.value.trim())
        CaltopoClient.SetIncident(_incident.value.trim())
        CaltopoClient.SetOpPeriod(_opPeriod.value.trim())
        if (credentialFieldState == CaltopoCredentialFieldState.COMPLETE) {
            CaltopoClient.SetCaltopoCredentials(
                CaltopoCredentials(
                    trimmedCaltopoTeamId,
                    trimmedCaltopoCredentialId,
                    trimmedCaltopoCredentialSecret,
                )
            )
        }
        if (trackerManuallyEdited) {
            CaltopoClient.SetHomeTrackerCredentials(
                _trackerUrl.value.trim(),
                _trackerApiKey.value.trim()
            )
            CaltopoClient.SetTrackerFaaProxyUrl("")
            trackerManuallyEdited = false
        }
        CaltopoClient.SetMutualAidTemplateFields(
            _mutualAidTeamId.value.trim(),
            _mutualAidCredentialId.value.trim(),
            _mutualAidCredentialSecret.value.trim(),
            _mutualAidDomain.value.trim().ifBlank { "caltopo.com" },
            _mutualAidSourceLabel.value.trim(),
            _mutualAidTargetFolder.value.trim().ifBlank { "MAI" },
            _mutualAidConnectKey.value.trim()
        )
        _minDistance.value.toLongOrNull()?.let { CaltopoClient.setMinDistanceInFeet(it) }
        _newTrackDelay.value.toLongOrNull()?.let { CaltopoClient.SetNewTrackDelayInSeconds(it) }
        RidLocationAccuracyPrefs.setMinimumCode(R2CApplication.getAppCtxt(), _minimumLocationAccuracyCode.value)
        (_maxFlatlineToneDuration.value.toLongOrNull()
            ?: CaltopoClient.DEFAULT_MAX_FLATLINE_TONE_DURATION_SECONDS).let {
            CaltopoClient.SetMaxFlatlineToneDurationInSeconds(it)
        }
        _bridgeCheckDistanceFeet.value.toLongOrNull()?.let { CaltopoClient.SetBridgeCheckDistanceFeet(it) }
        if (CaltopoClient.GetAlarmVolumeConfigured()) {
            CaltopoClient.SetAlarmVolumePercent(_alarmVolumePercent.value)
        }
        _maxIdleTimeInMinutes.value.toLongOrNull()?.let { CaltopoClient.SetMaxIdleTimeInMinutes(it) }
        CaltopoClient.SetUsePeers(_usePeers.value)
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)
        CaltopoClient.SetCaptureVideoStreamsFlag(_captureIncomingVideo.value)
        MediaServerAccessPrefs.setRestricted(R2CApplication.getAppCtxt(), _mediaServerRestricted.value)
        R2CApplication.getAppCtxt()?.let { context ->
            WifiRidScanPrefs.setEnabled(context, _wifiRidScanningEnabled.value)
            ScanningService.requestWifiRidScanningRefresh(context)
        }
        RemoteVideoControlPrefs.setEnabled(
            R2CApplication.getAppCtxt(),
            _remoteVideoControlEnabled.value,
        )
        val thumbnailRefreshSeconds = VideoThumbnailRefreshPrefs.setSeconds(
            R2CApplication.getAppCtxt(),
            _thumbnailRefreshSeconds.value.toDoubleOrNull()
                ?: VideoThumbnailRefreshPolicy.DEFAULT_SECONDS,
        )
        _thumbnailRefreshSeconds.value = VideoThumbnailRefreshPolicy.format(thumbnailRefreshSeconds)
        CaltopoClient.SetPredictiveHeadEnabled(_predictiveHeadEnabled.value)
        _proximityAlertSpacingFeet.value.toLongOrNull()?.let { CaltopoClient.SetProximityAlertSpacingFeet(it) }
        CaltopoClient.SetCaltopoDomainAndPort(_caltopoDomainAndPort.value)
        CaltopoClient.SetNotamEnabled(_notamEnabled.value)
        _notamRadiusNm.value.toIntOrNull()?.let { CaltopoClient.SetNotamRadiusNm(it) }
        _notamRefreshIntervalSeconds.value.toIntOrNull()?.let { CaltopoClient.SetNotamRefreshIntervalSeconds(it) }
        CaltopoClient.SetNotamAutoRefresh(_notamAutoRefresh.value)
        CaltopoClient.SetLandRestrictionsEnabled(_landRestrictionsEnabled.value)
        CaltopoClient.SetLandRestrictionsShowOnMap(_landRestrictionsShowOnMap.value)
        CaltopoClient.SetLandRestrictionsAutoRefresh(_landRestrictionsAutoRefresh.value)
        _landRestrictionsRadiusNm.value.toIntOrNull()?.let(CaltopoClient::SetLandRestrictionsRadiusNm)
        R2CApplication.getAppCtxt()?.let { context ->
            ExternalDisplayPrefs.save(
                context,
                ExternalDisplayConfig(
                    mode = _externalDisplayMode.value,
                    autoOpenOnConnect = _externalDisplayAutoOpen.value,
                    returnToPhoneOnlyLayoutOnDisconnect = _externalDisplayReturnToPhoneOnly.value,
                    allowInteraction = _externalDisplayAllowInteraction.value,
                    contentMode = _externalDisplayContentMode.value,
                    alertRouting = _externalDisplayAlertRouting.value
                )
            )
        }
        _notamStatus.value = buildNotamStatus()
        NotamCenter.requestImmediateRefresh()
        AirspaceCenter.requestImmediateRefresh()
        LandRestrictionCenter.settingsChanged()
        if (restartMediaMtx) {
            R2CApplication.getAppCtxt()?.let { MediaMTXService.requestRestart(it) }
        }
        } finally {
            isSaving = false
            settingsChanged()
        }
        return true
    }

    private fun buildNotamStatus(): String {
        val credentialSource = NotamAuthManager.credentialSource()
        val enabled = CaltopoClient.GetNotamEnabled()
        val radius = CaltopoClient.GetNotamRadiusNm()
        val radiusUnit = if (radius == 1) "statute mile" else "statute miles"
        return when {
            !enabled -> "Disabled"
            credentialSource == NotamAuthManager.CredentialSource.MANAGED_DEVICE_ENROLLMENT ->
                "Enabled, managed device enrollment present, $radius $radiusUnit radius"
            credentialSource == NotamAuthManager.CredentialSource.ORGANIZATION_CONFIG_CREDENTIAL ->
                "Enabled with an organization-config tracker credential; FAA proxy authorization " +
                    "must succeed or the QR is stale or needs device enrollment, $radius $radiusUnit radius"
            else ->
                "Enabled, FAA proxy not enrolled; scan a Drone-team enrollment QR, $radius $radiusUnit radius"
        }
    }
}

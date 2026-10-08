/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.ncssar.rid2caltopo.ui

import org.ncssar.rid2caltopo.ui.AlertDialog

import org.opendroneid.android.bluetooth.DroneScoutBridgeMonitor
import org.ncssar.rid2caltopo.data.ProximityAlertConsent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlin.math.roundToInt
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.ui.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.ExternalDisplayAlertRouting
import org.ncssar.rid2caltopo.data.ExternalDisplayContentMode
import org.ncssar.rid2caltopo.data.ExternalDisplayMode
import org.ncssar.rid2caltopo.data.RidLocationAccuracyPrefs

@Composable
fun CaltopoSettingsScreen(
    onDismiss: () -> Unit,
    onShowDeveloperTools: () -> Unit,
    onSelectIncident: () -> Unit,
    settingsViewModel: CaltopoSettingsViewModel = viewModel(),
    startAtProximity: Boolean = false
) {
    val savedBridgeWarningsMuted by DroneScoutBridgeMonitor.audioMuted.collectAsState()
    var bridgeWarningsMuted by remember { mutableStateOf(savedBridgeWarningsMuted) }
    val initialBridgeMuted = remember { savedBridgeWarningsMuted }
    var showRidMappingAdmin by remember { mutableStateOf(false) }
    val ridMappingCount = CaltopoClient.GetPersistedDroneSpecs().size
    val organizationName by settingsViewModel.organizationName.collectAsState()
    val trackFolder by settingsViewModel.trackFolder.collectAsState()
    val incident by settingsViewModel.incident.collectAsState()
    val opPeriod by settingsViewModel.opPeriod.collectAsState()
    val caltopoTeamId by settingsViewModel.caltopoTeamId.collectAsState()
    val caltopoCredentialId by settingsViewModel.caltopoCredentialId.collectAsState()
    val caltopoCredentialSecret by settingsViewModel.caltopoCredentialSecret.collectAsState()
    val caltopoConnectKey by settingsViewModel.caltopoConnectKey.collectAsState()
    val caltopoCredentialError by settingsViewModel.caltopoCredentialError.collectAsState()
    val trackerUrl by settingsViewModel.trackerUrl.collectAsState()
    val trackerApiKey by settingsViewModel.trackerApiKey.collectAsState()
    val usePeers by settingsViewModel.usePeers.collectAsState()
    val minDistance by settingsViewModel.minDistance.collectAsState()
    val newTrackDelay by settingsViewModel.newTrackDelay.collectAsState()
    val minimumLocationAccuracyCode by settingsViewModel.minimumLocationAccuracyCode.collectAsState()
    var showMinimumLocationAccuracyDialog by remember { mutableStateOf(false) }
    val bridgeCheckDistanceFeet by settingsViewModel.bridgeCheckDistanceFeet.collectAsState()
    val alarmVolumePercent by settingsViewModel.alarmVolumePercent.collectAsState()
    val maxIdleTimeInMinutes by settingsViewModel.maxIdleTimeInMinutes.collectAsState()
    val captureIncomingVideo by settingsViewModel.captureIncomingVideo.collectAsState()
    val mediaServerRestricted by settingsViewModel.mediaServerRestricted.collectAsState()
    val wifiRidScanningEnabled by settingsViewModel.wifiRidScanningEnabled.collectAsState()
    val remoteVideoControlEnabled by settingsViewModel.remoteVideoControlEnabled.collectAsState()
    val thumbnailRefreshSeconds by settingsViewModel.thumbnailRefreshSeconds.collectAsState()
    val proximityAlertSpacingFeet by settingsViewModel.proximityAlertSpacingFeet.collectAsState()
    val savedProximityConsent by ProximityAlertConsent.state.collectAsState()
    var proximityConsent by remember { mutableStateOf(savedProximityConsent) }
    val initialProximityEnabled = remember { savedProximityConsent.enabled }
    val savedAlertAllAircraft by ProximityAlertConsent.alertAllAircraft.collectAsState()
    var alertAllAircraft by remember { mutableStateOf(savedAlertAllAircraft) }
    val initialAlertAll = remember { savedAlertAllAircraft }
    DisposableEffect(Unit) {
        settingsViewModel.beginEditing()
        onDispose { settingsViewModel.discardEdits() }
    }
    val suspendedProximity by ProximityAlertCenter.isSuspended.collectAsState()
    val caltopoUrl by settingsViewModel.caltopoUrl.collectAsState()
    val notamEnabled by settingsViewModel.notamEnabled.collectAsState()
    val notamRadiusNm by settingsViewModel.notamRadiusNm.collectAsState()
    val notamRefreshIntervalSeconds by settingsViewModel.notamRefreshIntervalSeconds.collectAsState()
    val notamAutoRefresh by settingsViewModel.notamAutoRefresh.collectAsState()
    val notamStatus by settingsViewModel.notamStatus.collectAsState()
    val landRestrictionsEnabled by settingsViewModel.landRestrictionsEnabled.collectAsState()
    val landRestrictionsShowOnMap by settingsViewModel.landRestrictionsShowOnMap.collectAsState()
    val landRestrictionsAutoRefresh by settingsViewModel.landRestrictionsAutoRefresh.collectAsState()
    val landRestrictionsRadiusNm by settingsViewModel.landRestrictionsRadiusNm.collectAsState()
    val externalDisplayMode by settingsViewModel.externalDisplayMode.collectAsState()
    val externalDisplayContentMode by settingsViewModel.externalDisplayContentMode.collectAsState()
    val externalDisplayAutoOpen by settingsViewModel.externalDisplayAutoOpen.collectAsState()
    val externalDisplayReturnToPhoneOnly by settingsViewModel.externalDisplayReturnToPhoneOnly.collectAsState()
    val externalDisplayAllowInteraction by settingsViewModel.externalDisplayAllowInteraction.collectAsState()
    val externalDisplayAlertRouting by settingsViewModel.externalDisplayAlertRouting.collectAsState()
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun dirty() = settingsViewModel.hasUnsavedChanges() || bridgeWarningsMuted != initialBridgeMuted ||
        proximityConsent.enabled != initialProximityEnabled || alertAllAircraft != initialAlertAll
    fun saveAndContinue(action: () -> Unit) {
        pendingExit = null
        if (!settingsViewModel.saveSettings()) return
        if (bridgeWarningsMuted != initialBridgeMuted) DroneScoutBridgeMonitor.setAudioMuted(bridgeWarningsMuted)
        if (alertAllAircraft != initialAlertAll) ProximityAlertCenter.setAlertAllAircraft(alertAllAircraft)
        if (proximityConsent.enabled != initialProximityEnabled) {
            if (!proximityConsent.enabled) ProximityAlertCenter.disableAlerts()
            else {
                // Local draft confirmation already required every paragraph; persist only on Save.
                ProximityAlertConsent.requestEnable()
                ProximityAlertConsent.noticeParagraphs.indices.forEach { ProximityAlertConsent.toggleAcknowledgment(it) }
                ProximityAlertConsent.confirmEnable()
            }
        }
        pendingExit = null
        action()
    }
    val dismissAndSave = { saveAndContinue(onDismiss) }
    val discardAndClose = { settingsViewModel.discardEdits(); onDismiss() }
    fun requestExit(action: () -> Unit) {
        if (dirty()) pendingExit = action else action()
    }
    val showDeveloperTools = { requestExit(onShowDeveloperTools) }

    val settingsScroll = rememberScrollState()
    var proximityOffset by remember { mutableStateOf<Int?>(null) }
    var initialScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(startAtProximity, proximityOffset, settingsScroll.maxValue) {
        val offset = proximityOffset
        if (startAtProximity && !initialScrollDone && offset != null && settingsScroll.maxValue > 0) {
            settingsScroll.scrollTo(offset.coerceIn(0, settingsScroll.maxValue))
            initialScrollDone = true
        }
    }
    Dialog(onDismissRequest = { requestExit(onDismiss) }) {
        Card (modifier = Modifier.verticalScroll(settingsScroll)) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                caltopoCredentialError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Changes apply only when you Save. Cancel discards edits. Map connection, sign-in, file deletion, and test actions are separate workflows.", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Administration",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "RID map entries are normally loaded from the organization QR code. " +
                        "Use this editor to review, add, or correct Remote ID mappings stored on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showRidMappingAdmin = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View or Edit RID Map Entries ($ridMappingCount)")
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Organization and operational defaults",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsHelpLabel("Organization designator")
                OutlinedTextField(
                    value = organizationName,
                    onValueChange = settingsViewModel::onOrganizationNameChanged,

                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Organization designator" }
                )
                SettingsHelpLabel("CalTopo track folder")
                OutlinedTextField(
                    value = trackFolder,
                    onValueChange = settingsViewModel::onTrackFolderChanged,

                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "CalTopo track folder" }
                )
                SettingsHelpLabel("Incident")
                StorageActionButton(onClick = onSelectIncident) { Text(incident) }
                SettingsHelpLabel("Operational period")
                OutlinedTextField(
                    value = opPeriod,
                    onValueChange = settingsViewModel::onOpPeriodChanged,

                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Operational period" }
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "CalTopo Teams Account",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsHelpLabel("Team ID")
                OutlinedTextField(
                    value = caltopoTeamId,
                    onValueChange = settingsViewModel::onCaltopoTeamIdChanged,

                    isError = caltopoCredentialError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Team ID" }
                )
                SettingsHelpLabel("Credential ID")
                OutlinedTextField(
                    value = caltopoCredentialId,
                    onValueChange = settingsViewModel::onCaltopoCredentialIdChanged,

                    isError = caltopoCredentialError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Credential ID" }
                )
                SettingsHelpLabel("Credential secret")
                OutlinedTextField(
                    value = caltopoCredentialSecret,
                    onValueChange = settingsViewModel::onCaltopoCredentialSecretChanged,

                    isError = caltopoCredentialError != null,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Credential secret" }
                )
                SettingsHelpLabel("Connect Key")
                OutlinedTextField(
                    value = caltopoConnectKey,
                    onValueChange = settingsViewModel::onCaltopoConnectKeyChanged,

                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Connect Key" }
                )
                caltopoCredentialError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SettingsHelpLabel("Domain and port")
                OutlinedTextField(
                    value = caltopoUrl,
                    onValueChange = settingsViewModel::onCaltopoDomainAndPortChanged,

                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Domain and port" }
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Tracker Coordination",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsHelpLabel("Tracker URL")
                OutlinedTextField(
                    value = trackerUrl,
                    onValueChange = settingsViewModel::onTrackerUrlChanged,

                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Tracker URL" }
                )
                SettingsHelpLabel("Tracker API key")
                OutlinedTextField(
                    value = trackerApiKey,
                    onValueChange = settingsViewModel::onTrackerApiKeyChanged,

                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Tracker API key" }
                )
                LabeledSwitch(
                    label = "Use tracker peers",
                    checked = usePeers,
                    onCheckedChange = settingsViewModel::onUsePeersChanged
                )
                Text(
                    "Manual tracker changes configure coordination only. FAA proxy access remains organization-QR-only and is cleared when these fields are manually changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Bridge warnings", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    SettingsHelpLabel("Bridge audio warnings", modifier = Modifier.weight(1f))
                    Switch(checked = !bridgeWarningsMuted,
                        onCheckedChange = { bridgeWarningsMuted = !it })
                }
                Text("Enabled when the app starts. Turning this off silences bridge warnings for this app session only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Traffic safety",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                LabeledSwitch(
                    label = "Wi-Fi RID scanning",
                    checked = wifiRidScanningEnabled,
                    onCheckedChange = settingsViewModel::onWifiRidScanningEnabledChanged
                )
                Text(
                    "Off by default to match Apple Bluetooth-only RID discovery. Controls Android Wi-Fi Beacon and Wi-Fi NAN RID discovery only. " +
                        "Bluetooth RID, DS100 bridge reception, and normal Wi-Fi remain active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                SettingsHelpLabel("Min Dist (ft)")
                OutlinedTextField(
                    value = minDistance,
                    onValueChange = { settingsViewModel.onMinDistanceChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),

                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Min Dist (ft)" }
                )
                SettingsHelpLabel("New Track Delay (s)")
                OutlinedTextField(
                    value = newTrackDelay,
                    onValueChange = { settingsViewModel.onNewTrackDelayChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),

                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "New Track Delay (s)" }
                )
                SettingsHelpLabel("Minimum Location Accuracy")
                Button(
                    onClick = { showMinimumLocationAccuracyDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Minimum Location Accuracy: ${RidLocationAccuracyPrefs.labelForCode(minimumLocationAccuracyCode)}")
                }
                Text(
                    "RID positions less accurate than this threshold are retained as signal but are not added to the track.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                SettingsHelpLabel("Bridge Check Distance (ft)")
                OutlinedTextField(
                    value = bridgeCheckDistanceFeet,
                    onValueChange = {
                        settingsViewModel.onBridgeCheckDistanceFeetChanged(it.filter { ch -> ch.isDigit() })
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),

                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Bridge Check Distance (ft)" }
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsHelpLabel("Audio Alarm Volume: $alarmVolumePercent%")
                    Slider(
                        value = alarmVolumePercent.toFloat(),
                        onValueChange = {
                            settingsViewModel.onAlarmVolumePercentChanged(it.toInt())
                        },
                        valueRange = 0f..100f,
                        steps = 19
                    )
                    Button(
                        onClick = { SpokenWarningCenter.requestAudioAlarmTest() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Audio Alarm Test (saved volume)")
                    }
                }
                SettingsHelpLabel("Max Idle Time (minutes)")
                OutlinedTextField(
                    value = maxIdleTimeInMinutes,
                    onValueChange = { settingsViewModel.onMaxIdleTimeInMinutesChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),

                    supportingText = { Text("RID messages and user interaction reset this timer. Set to 0 to disable automatic closing.") },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Max Idle Time (minutes)" }
                )
                Text("Standalone flights stay independent. Live aircraft coordination requires an incident map. Organization access and archive uploads remain available.")
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Proximity Alerts",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                        proximityOffset = it.positionInParent().y.roundToInt()
                    }
                )
                LabeledSwitch(
                    label = "Proximity alerts: ${if (!proximityConsent.enabled) "Off" else if (suspendedProximity) "Suspended" else "On"}",
                    checked = proximityConsent.enabled,
                    onCheckedChange = { enabled ->
                        proximityConsent = if (enabled) proximityConsent.requestEnable() else proximityConsent.disable()
                    }
                )
                Text("Optional alerts based on received telemetry. No alert does not mean the airspace is clear.")
                LabeledSwitch(
                    label = "Alert scope: ${if (alertAllAircraft) "All aircraft" else "Published only"}",
                    checked = alertAllAircraft,
                    onCheckedChange = { alertAllAircraft = it }
                )
                Text("Published only: pairs involving an aircraft claimed by this tablet. All aircraft: any received pair, including ignored or unconfirmed flights. This does not change recording or publishing.")
                SettingsHelpLabel("Proximity Alert Spacing (ft, minimum 50; default 100)")
                OutlinedTextField(
                    value = proximityAlertSpacingFeet,
                    onValueChange = {
                        settingsViewModel.onProximityAlertSpacingFeetChanged(it.filter { ch -> ch.isDigit() })
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),

                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Proximity Alert Spacing (ft, minimum 50; default 100)" }
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Video Streams",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                LabeledSwitch(
                    label = "Restrict media server access",
                    checked = mediaServerRestricted,
                    onCheckedChange = settingsViewModel::onMediaServerRestrictedChanged
                )
                Text("Accept controller RTMP streams while blocking direct media-server viewing from other devices. Playback and recording on this tablet and authorized R2C sharing remain available. Turn off only for trusted local viewers. Changing this setting restarts video.")
                LabeledSwitch(
                    label = "Capture Streams",
                    checked = captureIncomingVideo,
                    onCheckedChange = settingsViewModel::onCaptureIncomingVideoChanged
                )
                LabeledSwitch(
                    label = "Remote Video Control",
                    checked = remoteVideoControlEnabled,
                    onCheckedChange = settingsViewModel::onRemoteVideoControlEnabledChanged,
                )
                Text(
                    "When enabled, an authenticated requester chooses video quality after the link test without a per-request approval prompt. Only one viewer can use this tablet at a time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsHelpLabel("Thumbnail & LiveTrack update interval")
                OutlinedTextField(
                    value = thumbnailRefreshSeconds,
                    onValueChange = { value ->
                        settingsViewModel.onThumbnailRefreshSecondsChanged(
                            value.filterIndexed { index, character ->
                                character.isDigit() || (character == '.' &&
                                    value.indexOf('.') == index)
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),

                    supportingText = {
                        Text("Minimum time between thumbnail refreshes and LiveTrack updates. 0.5–60.0 seconds; default 5.0. Lower values update more often and use more battery and data.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Thumbnail & LiveTrack update interval" },
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "NOTAM / TFR",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = notamStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                LabeledSwitch(
                    label = "FAA monitoring",
                    checked = notamEnabled,
                    onCheckedChange = settingsViewModel::onNotamEnabledChanged
                )

                SettingsHelpLabel("NOTAM radius (statute miles)")
                OutlinedTextField(
                    value = notamRadiusNm,
                    onValueChange = { settingsViewModel.onNotamRadiusNmChanged(it.filter { ch -> ch.isDigit() }) },
                    enabled = notamEnabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),

                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "NOTAM radius (statute miles)" }
                )

                SettingsHelpLabel("Refresh interval (seconds, minimum 1800)")
                OutlinedTextField(
                    value = notamRefreshIntervalSeconds,
                    onValueChange = { settingsViewModel.onNotamRefreshIntervalSecondsChanged(it.filter { ch -> ch.isDigit() }) },
                    enabled = notamEnabled && notamAutoRefresh,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),

                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Refresh interval (seconds, minimum 1800)" }
                )

                LabeledSwitch(
                    label = "Refresh automatically",
                    checked = notamAutoRefresh,
                    enabled = notamEnabled,
                    onCheckedChange = settingsViewModel::onNotamAutoRefreshChanged
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Land / Agency Restrictions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Checks NPS units, National Wildlife Refuges, USFS wilderness, and Colorado parks and wildlife properties. Results distinguish land-use rules from FAA airspace restrictions and include agency follow-up links.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                LabeledSwitch(
                    label = "Protected-land checks",
                    checked = landRestrictionsEnabled,
                    onCheckedChange = settingsViewModel::onLandRestrictionsEnabledChanged
                )
                LabeledSwitch(
                    label = "Show protected lands on map",
                    checked = landRestrictionsShowOnMap,
                    enabled = landRestrictionsEnabled,
                    onCheckedChange = settingsViewModel::onLandRestrictionsShowOnMapChanged
                )
                LabeledSwitch(
                    label = "Refresh protected lands automatically",
                    checked = landRestrictionsAutoRefresh,
                    enabled = landRestrictionsEnabled,
                    onCheckedChange = settingsViewModel::onLandRestrictionsAutoRefreshChanged
                )
                SettingsHelpLabel("Boundary query radius (statute miles)")
                OutlinedTextField(
                    value = landRestrictionsRadiusNm,
                    onValueChange = { settingsViewModel.onLandRestrictionsRadiusNmChanged(it.filter(Char::isDigit)) },
                    enabled = landRestrictionsEnabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),

                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Boundary query radius (statute miles)" }
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "External Display",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                SettingsHelpLabel("External display mode")
                SingleChoiceGroup(
                    options = ExternalDisplayMode.entries,
                    selected = externalDisplayMode,
                    label = { it.displayLabel },
                    onSelected = settingsViewModel::onExternalDisplayModeChanged
                )
                when (externalDisplayMode) {
                    ExternalDisplayMode.Off -> {
                        Text(
                            "RID2Caltopo will not manage the external display.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ExternalDisplayMode.OsMirroring -> {
                        Text(
                            "RID2Caltopo will not open its own external window. Enable mirroring from Samsung/Android display controls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ExternalDisplayMode.AppManaged -> {
                        SettingsHelpLabel("Content")
                        SingleChoiceGroup(
                            options = ExternalDisplayContentMode.entries,
                            selected = externalDisplayContentMode,
                            label = { it.displayLabel },
                            onSelected = settingsViewModel::onExternalDisplayContentModeChanged
                        )
                        LabeledSwitch(
                            label = "Auto-open on connect",
                            checked = externalDisplayAutoOpen,
                            onCheckedChange = settingsViewModel::onExternalDisplayAutoOpenChanged
                        )
                        LabeledSwitch(
                            label = "Return to phone-only layout on disconnect",
                            checked = externalDisplayReturnToPhoneOnly,
                            onCheckedChange = settingsViewModel::onExternalDisplayReturnToPhoneOnlyChanged
                        )
                        LabeledSwitch(
                            label = "Allow external display interaction",
                            checked = externalDisplayAllowInteraction,
                            onCheckedChange = settingsViewModel::onExternalDisplayAllowInteractionChanged
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "App-managed mode presents the selected streams/map layout independently on the attached display.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        SettingsHelpLabel("Alert routing")
                        SingleChoiceGroup(
                            options = ExternalDisplayAlertRouting.entries,
                            selected = externalDisplayAlertRouting,
                            label = { it.displayLabel },
                            onSelected = settingsViewModel::onExternalDisplayAlertRoutingChanged
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row {
                    Button(onClick = dismissAndSave) {
                        Text("Save")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = discardAndClose) {
                        Text("Cancel")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = showDeveloperTools,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Developer Tools")
                }
            }
        }
    }
    pendingExit?.let { action ->
        AlertDialog(onDismissRequest = { pendingExit = null }, title = { Text("Unsaved Settings") },
            text = { Text("Save your changes, discard them, or keep editing?") },
            confirmButton = { TextButton(onClick = { saveAndContinue(action) }) { Text("Save Changes") } },
            dismissButton = {
                TextButton(onClick = { settingsViewModel.discardEdits(); pendingExit = null; action() }) { Text("Discard Changes") }
                TextButton(onClick = { pendingExit = null }) { Text("Keep Editing") }
            })
    }
    if (showRidMappingAdmin) {
        RidMappingAdminDialog(onDismiss = { showRidMappingAdmin = false })
    }
    if (proximityConsent.noticePending) {
        AlertDialog(
            onDismissRequest = { proximityConsent = proximityConsent.cancel() },
            title = { Text(ProximityAlertConsent.TITLE) },
            text = {
                val consentScroll = rememberScrollState()
                val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                Column(
                    modifier = Modifier
                        .verticalScrollbar(consentScroll, scrollbarColor)
                        .verticalScroll(consentScroll)
                        .padding(end = 10.dp)
                ) {
                    ProximityAlertConsent.noticeParagraphs.forEachIndexed { index, paragraph ->
                        val checked = index in proximityConsent.acknowledged
                        // Whole row is one checkbox target; its accessibility label is the paragraph.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { proximityConsent = proximityConsent.toggleAcknowledgment(index) }
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(paragraph, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { proximityConsent = proximityConsent.confirmEnable() },
                    enabled = proximityConsent.canConfirm
                ) { Text(proximityConsent.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { proximityConsent = proximityConsent.cancel() }) { Text("Keep disabled") }
            }
        )
    }
    if (showMinimumLocationAccuracyDialog) {
        AlertDialog(
            onDismissRequest = { showMinimumLocationAccuracyDialog = false },
            title = { Text("Minimum Location Accuracy") },
            text = {
                Column {
                    listOf(9, 10, 11, 12).forEach { code ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = minimumLocationAccuracyCode == code,
                                onClick = {
                                    settingsViewModel.onMinimumLocationAccuracyCodeChanged(code)
                                    showMinimumLocationAccuracyDialog = false
                                }
                            )
                            Text(RidLocationAccuracyPrefs.labelForCode(code))
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SettingsHelpLabel(label, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun <T> SingleChoiceGroup(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Column {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = option == selected,
                    onClick = { onSelected(option) }
                )
                Text(label(option))
            }
        }
    }
}

@Composable
private fun SettingsHelpLabel(title: String, modifier: Modifier = Modifier) {
    var showing by remember { mutableStateOf(false) }
    TextButton(onClick = { showing = true }, modifier = modifier) {
        Text(title + "  ⓘ", textAlign = androidx.compose.ui.text.style.TextAlign.Start)
    }
    if (showing) {
        AlertDialog(
            onDismissRequest = { showing = false },
            title = { Text(title.substringBefore(":")) },
            text = { Text(SettingsFieldHelp.description(title), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { showing = false }) { Text("Done") } }
        )
    }
}

/** Always-visible thumb so long or large-text dialog content is obviously scrollable. */
private fun Modifier.verticalScrollbar(state: ScrollState, color: Color): Modifier = drawWithContent {
    drawContent()
    if (state.maxValue <= 0 || state.maxValue == Int.MAX_VALUE) return@drawWithContent
    val width = 4.dp.toPx()
    val viewport = size.height
    val thumbHeight = (viewport * viewport / (viewport + state.maxValue)).coerceAtLeast(24.dp.toPx())
    val thumbTop = (viewport - thumbHeight) * state.value / state.maxValue
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width, thumbTop),
        size = Size(width, thumbHeight),
        cornerRadius = CornerRadius(width / 2)
    )
}

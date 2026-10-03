/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import org.ncssar.rid2caltopo.ui.AlertDialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.data.CaltopoHybridBrowser
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.ncssar.rid2caltopo.ui.Dialog
import androidx.compose.ui.window.DialogProperties
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

import java.util.Locale

@Composable
fun R2CView(
    hostName: String,
    viewModel: R2CViewModel?,
    drones : List<CtDroneSpec>,
    appUptime : String,
    onConfirmDrone: (CtDroneSpec) -> Unit
) {
    val tag = "R2CView"
    Column {
        AppHeader(appUptime, hostName, viewModel)
        if (!drones.isEmpty()) {
            RidmapHeader()
            drones.forEach { drone ->
                key(drone.remoteId) {
                    val triggerCount = drone.totalCount
                    DroneItem(
                        drone = drone,
                        totalCount = drone.totalCount
                    ) {
                        onConfirmDrone(drone)
                    }
                }
            }
        }
    }
}

@Composable
fun AppHeader(appUptime: String, hostName: String, viewModel: R2CViewModel?) {
    val textMod = Modifier.fillMaxWidth().padding(6.dp)
    val colModifier = Modifier
        .fillMaxHeight()
        .background(MaterialTheme.colorScheme.surface)
    var showRidmapEntries by remember { mutableStateOf(false) }
    if (showRidmapEntries) {
        RidmapEntriesDialog(
            entries = CaltopoClient.GetRidmapEntriesSnapshot(),
            onDismiss = { showRidmapEntries = false }
        )
    }
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(start = 2.dp, end = 2.dp, top = 8.dp, bottom = 4.dp)
    ) {
        Column(modifier = colModifier) {
            if (null != viewModel) MapStateView(viewModel)
        }
        Column(
            modifier = colModifier.width(135.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            OpPeriodField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
        }
        Column(
            modifier = colModifier.width((180 * androidx.compose.ui.platform.LocalDensity.current.fontScale).dp),
            verticalArrangement = Arrangement.Center,
        ) {
            PilotCallsignField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )
        }
        Column(modifier = colModifier) {
            val coordinatorStatus = coordinatorStatusDisplayText(
                R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.coordinationStatusText
            )
            Text(
                text = "Tracker:\n$coordinatorStatus",
                modifier = textMod,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
        Column (modifier = colModifier) {
            Text(
                text = "Team Drones:\n${CaltopoClient.GetRidmapCount()}",
                modifier = textMod.clickable { showRidmapEntries = true },
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
        Column(modifier = colModifier) {
            Text(
                text = "$hostName\n${R2CActivity.getMyAppVersion()}",
                modifier = textMod,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
        Column(modifier = colModifier) {
            Text(
                text = "Up Time:\n$appUptime",
                modifier = textMod,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
        Column(modifier = colModifier) {
            val rtt = if (viewModel?.connectionState is CaltopoConnectionState.MapSelected) {
                String.format(
                    Locale.US, "%.3f sec",
                    R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.getCaltopoRttMs().toDouble() / 1000.0
                )
            } else {
                "--"
            }
            Text(
                text = "Caltopo msg rtt:\n$rtt",
                modifier = textMod,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
        Column(modifier = colModifier) {
            val msgs = String.format(
                Locale.US, "%d", CtDroneSpec.GetInvalidWaypointCount()
            )
            Text(
                text = "Invalid RID msgs:\n$msgs",
                modifier = textMod,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

internal fun coordinatorStatusDisplayText(statusText: String): String = when (statusText) {
    "Tracker verified" -> "Tracker verified"
    "Tracker link healthy" -> "Tracker verified"
    "Tracker link standby" -> "Tracker standby"
    "Tracker link degraded" -> "Tracker degraded"
    "Tracker link disabled" -> "Disabled"
    "Tracker authorization rejected; re-enrollment required" -> "Re-enroll required"
    "Tracker link not configured",
    "R2C link not configured" -> "Not configured"
    "MQTT link healthy" -> "MQTT OK"
    "MQTT link degraded" -> "MQTT degraded"
    "Coordinator unavailable" -> "Unavailable"
    else -> statusText
}

@Composable
private fun RidmapEntriesDialog(
    entries: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Text("rid_map entries (${entries.size})")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (entries.isEmpty()) {
                    Text("No cached rid_map entries.")
                } else {
                    Text(
                        text = entries.joinToString("\n\n"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    )
}

@Composable
fun RidmapHeader() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(2.dp),
    ) {
        Column(
            modifier = Modifier.width(R2CViewColumnLayout.publishStatusColumnWidthDp.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(MaterialTheme.colorScheme.surface)
            )
        }
        Column(
            modifier = Modifier.width(R2CViewColumnLayout.trackLabelColumnWidthDp.dp)
        ) {
            Text(
                text = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
            )
            Text(
                text = "Track Label:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.width(R2CViewColumnLayout.remoteIdColumnWidthDp.dp)
        ) {
            Text(
                text = "Drone→Bridge RSSI",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 6.dp),
                textAlign = TextAlign.Center,
                fontSize = 10.sp,
                maxLines = 1,
            )
            Text(
                text = "Remote ID:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.width(R2CViewColumnLayout.waypointsReceivedHeaderWidthDp.dp)
        ) {
            Text(
                text = "Waypoints Received",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "BT4:",
                    modifier = Modifier
                        .width(R2CViewColumnLayout.transportCountColumnWidthDp.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Box(modifier = Modifier.width(R2CViewColumnLayout.transportSignalColumnWidthDp.dp).height(25.dp).background(MaterialTheme.colorScheme.surface))
                Text(
                    text = "BT5:",
                    modifier = Modifier
                        .width(R2CViewColumnLayout.transportCountColumnWidthDp.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Box(modifier = Modifier.width(R2CViewColumnLayout.transportSignalColumnWidthDp.dp).height(25.dp).background(MaterialTheme.colorScheme.surface))
                Text(
                    text = "WiFi:",
                    modifier = Modifier
                        .width(R2CViewColumnLayout.transportCountColumnWidthDp.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Box(modifier = Modifier.width(R2CViewColumnLayout.transportSignalColumnWidthDp.dp).height(25.dp).background(MaterialTheme.colorScheme.surface))
                Text(
                    text = "NaN:",
                    modifier = Modifier
                        .width(R2CViewColumnLayout.transportCountColumnWidthDp.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Box(modifier = Modifier.width(R2CViewColumnLayout.transportSignalColumnWidthDp.dp).height(25.dp).background(MaterialTheme.colorScheme.surface))
                Text(
                    text = "R2C:",
                    modifier = Modifier
                        .width(R2CViewColumnLayout.r2cWaypointColumnWidthDp.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Text(
                    text = "SEI:",
                    modifier = Modifier
                        .width(R2CViewColumnLayout.seiColumnWidthDp.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Text(
                    text = "Total:",
                    modifier = Modifier
                        .width(R2CViewColumnLayout.totalColumnWidthDp.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
            }
        }
        Column(
            modifier = Modifier.width(R2CViewColumnLayout.flightDurationColumnWidthDp.dp)
        ) {
            Text(
                text = "Flight",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Right,
                fontSize = 18.sp
            )
            Text(
                text = "Duration:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Right,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.width(R2CViewColumnLayout.r2cRttColumnWidthDp.dp)
        ) {
            Text(
                text = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface)
            )
            Text(
                text = "R2C RTT:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Right,
                fontSize = 18.sp
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun R2CViewPreview() {
    RID2CaltopoTheme {
        R2CView(
            "",
            null,
            emptyList(),
            "",
            {}
        )
    }
}

@Composable
fun DroneSpecConfirmationDialog(
    state: DroneSpecConfirmationUiState,
    onFieldChange: (organization: String?, pilotCallsign: String?, droneDescription: String?) -> Unit,
    onSave: () -> Unit,
    onUnknown: () -> Unit,
) {
    var readiness by remember(state.remoteId) { mutableStateOf(org.ncssar.rid2caltopo.data.FlightReadiness(
        aircraft = CaltopoClient.GetPersistedDroneSpecs().firstOrNull { it.remoteId == state.remoteId }?.readiness ?: org.ncssar.rid2caltopo.data.AircraftReadiness(),
        selectedAccessories = emptySet()).restoringEquipment(org.ncssar.rid2caltopo.data.AircraftOrganizationAccess.rememberedEquipment(state.remoteId))) }
    val managedAircraft = org.ncssar.rid2caltopo.data.AircraftOrganizationAccess.belongsToOrganization()
    val pilotCallsign = state.pilotCallsign.trim()
    val pilotMatched = org.json.JSONObject(readiness.pilotJson).optString("memberId").isNotBlank() &&
        org.json.JSONObject(readiness.pilotJson).optString("callsign").equals(pilotCallsign, true)


    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Text(
                if (state.mappedIdIsRemoteId) "Add to RID Map" else "Update Saved Drone"
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.bootstrapDesignator?.let { designator ->
                    Text("Remote ID: ${state.remoteId}\nStream designator: $designator")
                    Text("Save a local RID-map entry and confirm this flight for publishing to the selected map. Check the suggested model and enter the pilot callsign. Without a selected map, publication waits for map selection.")
                    Text("Pair video only leaves track publishing off.")
                }
                if (!state.warning.isNullOrBlank()) {
                    Text(
                        text = state.warning,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedTextField(
                    value = state.pilotCallsign,
                    onValueChange = { onFieldChange(null, it, null) },
                    label = { Text(if (managedAircraft) "RPIC callsign" else "Pilot Callsign") },
                    supportingText = state.pilotCallsignWarning?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.droneDescription,
                    onValueChange = { onFieldChange(null, null, it) },
                    label = { Text("Drone Description") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                FlightReadinessFields(state.remoteId, pilotCallsign, readiness, { readiness = it.copy(operatingProfileJson = it.operatingProfileJson ?: readiness.operatingProfileJson) }) { onFieldChange(null, it, null) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = state.bootstrapDesignator == null || pilotCallsign.isNotBlank(),
                onClick = {
                    readiness.operatingProfileJson?.let { raw ->
                        val selected = org.json.JSONObject(raw)
                        org.ncssar.rid2caltopo.data.AircraftOrganizationAccess.rememberProfile(selected.getJSONObject("profile"))
                        if (selected.optBoolean("useForAssignment")) {
                            org.ncssar.rid2caltopo.data.OperatingProfiles.remember(selected.getJSONObject("profile"),
                                org.ncssar.rid2caltopo.data.IncidentBriefing.fromJSON(selected.optJSONObject("incidentBriefing")))
                            selected.put("assignmentId", org.ncssar.rid2caltopo.data.OperatingProfiles.assignmentId)
                            readiness = readiness.copy(operatingProfileJson = selected.toString())
                        } else {
                            org.ncssar.rid2caltopo.data.OperatingProfiles.endAssignment()
                            selected.put("assignmentId", "")
                            readiness = readiness.copy(operatingProfileJson = selected.toString())
                        }
                    }
                    val recorded = readiness.resolvingPilot(pilotCallsign, org.ncssar.rid2caltopo.data.AircraftOrganizationAccess.cachedState().optJSONArray("pilots")).confirmed()
                    CaltopoClient.GetDroneSpec(state.remoteId)?.setFlightReadinessJson(recorded.toJSON().toString())
                    org.ncssar.rid2caltopo.data.AircraftOrganizationAccess.rememberEquipment(state.remoteId, readiness)
                    onSave()
                }
            ) {
                Text(if (state.bootstrapDesignator != null) "Save and publish track" else "Publish track")
            }
        },
        dismissButton = {
            TextButton(onClick = onUnknown) {
                Text(if (state.bootstrapDesignator != null) "Pair video only" else "Don’t publish")
            }
        },
    )
}

@Composable
fun MapStateView(viewModel: R2CViewModel) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
        CaltopoActionInterface(
            state = viewModel.connectionState,
            modifier = Modifier.width(280.dp),
            onActionClicked = { viewModel.openConnectionOverlayFromCurrentScreen() }
        )
    }
}

// Shared host keeps the active page mounted throughout the connection workflow.
@Composable
fun MapConnectionOverlayHost(viewModel: R2CViewModel) {
    val connection = viewModel.connectionState
    val overlay = viewModel.overlay
    val pendingProfileSwitch = viewModel.pendingProfileSwitch
    val context = androidx.compose.ui.platform.LocalContext.current
    val configPicker = rememberLauncherForActivityResult(FreshOpenDocument()) { uri ->
        if (uri == null) {
            viewModel.onUIEvent(UIEvent.DismissRequested)
        } else {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                viewModel.onUIEvent(if (CaltopoClient.LoadConfigFile(uri)) {
                    UIEvent.ConfigFileLoaded
                } else {
                    UIEvent.NotAbleToReadConfigFile
                })
            } catch (e: Exception) {
                CaltopoClient.CTError("MapConnectionOverlayHost", "Config read failed: ", e)
                viewModel.onUIEvent(UIEvent.NotAbleToReadConfigFile)
            }
        }
    }
    LaunchedEffect(overlay) {
        if (overlay == OverlayState.RequestConfigFile) {
            configPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }
    Box {
        // 2. The "Marching Orders": Render EXACTLY one overlay based on state
        when (val currentOverlay = overlay) {
            is OverlayState.ConnectionSetup -> {
                StandAloneOptionsDialog(
                    onPersonalReady = { viewModel.onUIEvent(UIEvent.BrowseProfileSelected("personal")) },
                    hasCreds = viewModel.hasCredentials,
                    hasNetwork = viewModel.hasNetwork,
                    onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                    loading = false,
                    onAction = { viewModel.onUIEvent(UIEvent.ConnectionRequested) }
                )
            }
            is OverlayState.RequestConfigFile -> {
                StandAloneOptionsDialog(
                    onPersonalReady = { viewModel.onUIEvent(UIEvent.BrowseProfileSelected("personal")) },
                    hasCreds = viewModel.hasCredentials,
                    hasNetwork = viewModel.hasNetwork,
                    onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                    loading = true,
                    onAction = { viewModel.onUIEvent(UIEvent.ConnectionRequested) }
                )
            }
            is OverlayState.Connecting -> {
                StandAloneOptionsDialog(
                    onPersonalReady = { viewModel.onUIEvent(UIEvent.BrowseProfileSelected("personal")) },
                    hasCreds = viewModel.hasCredentials,
                    hasNetwork = viewModel.hasNetwork,
                    onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                    loading = true,
                    connectingToMap = CaltopoMap.GetMapNode() != null,
                    onAction = { viewModel.onUIEvent(UIEvent.ConnectionRequested) }
                )
            }

            is OverlayState.MapBrowser -> {
                val nodes = if (org.ncssar.rid2caltopo.data.CaltopoPersonalSession.browsingPersonal) org.ncssar.rid2caltopo.data.CaltopoPersonalSession.maps else viewModel.mapHierarchy?: emptyList()
                // The browser receives the data it needs and bubbles events back up
                Dialog(
                    onDismissRequest = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    // Now the browser has its own window and won't be "squished" by the header
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        key(nodes) {
                            CaltopoHybridBrowser(
                                rootNodes = nodes,
                                profileOptions = viewModel.mapBrowserProfiles,
                                selectedProfileId = viewModel.selectedMapBrowserProfileId,
                                onUIEvent = { viewModel.onUIEvent(it) },
                                loading = !org.ncssar.rid2caltopo.data.CaltopoPersonalSession.browsingPersonal && viewModel.mapHierarchy == null
                            )
                        }
                    }
                }
            }

            is OverlayState.Management -> {
                // Type safety: Management only makes sense if a map is selected
                (connection as? CaltopoConnectionState.MapSelected)?.let { state ->
                    ConnectedOptionsDialog(
                        mapName = state.map.title,
                        onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                        onSwitchMap = { viewModel.onUIEvent(UIEvent.SwitchMapRequested) },
                        onDisconnect = { viewModel.onUIEvent(UIEvent.DisconnectRequested) }
                    )
                }
            }

            is OverlayState.Error -> {
                ErrorDialog(
                    message = overlay.message,
                    onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) }
                )
            }

            OverlayState.None -> { /* Render nothing over the map */ }
        }
    }

    pendingProfileSwitch?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingProfileSwitch() },
            title = { Text("Switch Browse Profile?") },
            text = {
                Text(
                    "Disconnect from the current map and stop arbitration for " +
                            "${pending.activeFlightCount} active flight(s) before browsing as " +
                            "${pending.label}?"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingProfileSwitch() }) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingProfileSwitch() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(text = "Connection Error")
        },
        text = {
            Text(text = message)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandAloneOptionsDialog(
    onDismiss: () -> Unit,
    loading: Boolean,
    hasNetwork: Boolean,
    hasCreds: Boolean,
    onAction: () -> Unit,
    connectingToMap: Boolean = false,
    onPersonalReady: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val (personalBusy, openPersonal) = rememberPersonalCredentialsAction(onPersonalReady)
    val titleText = if (!hasNetwork) {
        "No Network Connection"
    } else if (connectingToMap || (loading && hasCreds)) {
        "Connect to Map"
    } else {
        "Credentials Required"
    }
    val msgText = if (!hasNetwork) {
        "Turn on your device's WiFi and connect to hotspot before continuing"
    } else if (hasCreds) {
        "Existing credentials found. Would you like to select a map?"
    } else {
        "Sign in with your personal CalTopo account to choose a map. Teams credentials are optional."
    }
    AlertDialog(
        onDismissRequest = if (loading) ({}) else onDismiss, // disable dismiss while loading
        title = { Text(titleText) },
        text = {
            if (loading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (connectingToMap) {
                            "Connecting to incident map…"
                        } else if (hasCreds) {
                            "Loading available maps…"
                        } else {
                            "Waiting for CalTopo credentials. Complete reauthentication, " +
                                "or stay offline and load credentials manually."
                        }
                    )
                }
            } else {
                Column {
                    if (hasCreds) PersonalCaltopoLoginButton(onReady = onPersonalReady)
                    Text(msgText)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = if (hasCreds) onAction else openPersonal,
                enabled = !loading && !personalBusy && hasNetwork
            ) {
                Text(if (hasCreds) "Connect" else if (personalBusy) "Loading personal maps…" else "Personal: Sign in")

            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay Offline") }
        }
    )
}

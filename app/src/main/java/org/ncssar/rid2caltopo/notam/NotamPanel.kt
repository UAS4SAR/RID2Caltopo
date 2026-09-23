package org.ncssar.rid2caltopo.notam

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.airspace.AirspaceUiState
import org.ncssar.rid2caltopo.airspace.OperatingArea
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.video.CoordinateDisplayFormat
import org.ncssar.rid2caltopo.video.CoordinateFormatter

@Composable
fun NotamPanel(
    state: NotamUiState,
    airspaceState: AirspaceUiState? = null,
    onDismiss: () -> Unit
) {
    val displayedAirspace = airspaceState?.takeIf {
        shouldUseAirspaceStatus(state.visible, it)
    }
    if (displayedAirspace != null) {
        AirspaceRestrictionsPanel(
            state = displayedAirspace,
            notamState = state,
            onDismiss = onDismiss
        )
        return
    }

    val expandedIds = remember { mutableStateListOf<String>() }
    val coordinateDisplayFormat = CoordinateDisplayFormat.fromStorage(CaltopoClient.GetCoordinateDisplayFormat())
    val currentLocationText = CaltopoMap.GetMyLocation()?.let { location ->
        CoordinateFormatter.format(location.latitude, location.longitude, coordinateDisplayFormat)
    } ?: "Unavailable"
    val queryLocationText = if (state.queryLatitude != null && state.queryLongitude != null) {
        CoordinateFormatter.format(state.queryLatitude, state.queryLongitude, coordinateDisplayFormat)
    } else {
        null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Column {
                Text("Nearby NOTAMs")
                Text("Not flight authorization. Verify independently.", style = MaterialTheme.typography.labelMedium)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                AirspaceSafetyNotice()
                Text("Location: $currentLocationText")
                queryLocationText?.let {
                    Text("NOTAM query used: $it")
                }
                Text(
                    "Radius: ${state.radiusStatuteMiles} " +
                        if (state.radiusStatuteMiles == 1) "statute mile" else "statute miles"
                )
                state.lastUpdatedText?.let {
                    Text(it, color = if (state.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.statusLine.isNotBlank()) {
                    Text(state.statusLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                airspaceState?.takeIf { it.summary.isNotBlank() || it.detail.isNotBlank() }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("Airspace", fontWeight = FontWeight.SemiBold)
                    AirspaceQueryMetadata(it)
                    if (it.summary.isNotBlank()) {
                        Text(it.summary, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (it.detail.isNotBlank()) {
                        Text(it.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(10.dp))
                if (state.suppressedNoticeCount > 0) {
                    Text(
                        "Showing only notices within ${state.radiusStatuteMiles} statute miles. ${state.suppressedNoticeCount} farther notices hidden.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (state.notices.isEmpty()) {
                    val nearestHidden = state.nearestHiddenNotice
                    when {
                        !state.enabled -> {
                            Text("Nearby NOTAM monitoring is disabled.")
                        }
                        nearestHidden != null -> {
                            Text(
                                "Nearest NOTAM is ${nearestHidden.proximityText.ifBlank { nearestHidden.distanceNm?.let { "${"%.1f".format(it / OperatingArea.radiusNm)} mi" } ?: "unavailable" }} away.",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    if (expandedIds.contains(nearestHidden.id)) {
                                        expandedIds.remove(nearestHidden.id)
                                    } else {
                                        expandedIds.add(nearestHidden.id)
                                    }
                                }
                            ) {
                                Text(if (expandedIds.contains(nearestHidden.id)) "Hide nearest NOTAM" else "Show nearest NOTAM")
                            }
                        if (expandedIds.contains(nearestHidden.id)) {
                            if (nearestHidden.proximityText.isNotBlank()) {
                                Text(
                                    nearestHidden.proximityText,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(nearestHidden.title, fontWeight = FontWeight.SemiBold)
                                nearestHidden.rawReference.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val metaText = buildString {
                                    if (nearestHidden.intersectsPilotBubble) append("intersects ${OperatingArea.displayLabel}")
                                    if (nearestHidden.effectiveText.isNotBlank()) {
                                        if (isNotBlank()) append(" • ")
                                        append(nearestHidden.effectiveText)
                                    }
                                }
                                if (metaText.isNotBlank()) {
                                    Text(metaText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (nearestHidden.summary.isNotBlank()) {
                                    Text(nearestHidden.summary, modifier = Modifier.padding(top = 2.dp))
                                }
                                if (nearestHidden.details.isNotBlank()) {
                                    Text(nearestHidden.details, modifier = Modifier.padding(top = 2.dp))
                                }
                                if (nearestHidden.rawTitle.isNotBlank()) {
                                    Text(
                                        "FAA notice:",
                                        modifier = Modifier.padding(top = 2.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (nearestHidden.rawTitle.isNotBlank()) {
                                    Text(
                                        nearestHidden.rawTitle,
                                        modifier = Modifier.padding(top = 2.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (nearestHidden.rawText.isNotBlank()) {
                                    Text(
                                        nearestHidden.rawText,
                                        modifier = Modifier.padding(top = 2.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        state.configured -> {
                            Text("No notices are displayed. Check query status above; this is not a flight clearance.")
                        }
                        else -> {
                            Text("NOTAM monitoring is enabled, but credentials have not been loaded yet.")
                        }
                    }
                } else {
                    state.notices.forEach { notice ->
                        val expanded = expandedIds.contains(notice.id)
                        if (notice.proximityText.isNotBlank()) {
                            Text(
                                notice.proximityText,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(notice.title, fontWeight = FontWeight.SemiBold)
                        notice.rawReference.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val metaText = buildString {
                            if (notice.intersectsPilotBubble) append("intersects ${OperatingArea.displayLabel}")
                            if (notice.effectiveText.isNotBlank()) {
                                if (isNotBlank()) append(" • ")
                                append(notice.effectiveText)
                            }
                        }
                        if (metaText.isNotBlank()) {
                            Text(metaText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (notice.summary.isNotBlank()) {
                            Text(notice.summary, modifier = Modifier.padding(top = 2.dp))
                        }
                        TextButton(
                            onClick = {
                                if (expanded) {
                                    expandedIds.remove(notice.id)
                                } else {
                                    expandedIds.add(notice.id)
                                }
                            },
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(if (expanded) "Hide details" else "Show details")
                        }
                        if (expanded) {
                            if (notice.details.isNotBlank()) {
                                Text(notice.details, modifier = Modifier.padding(top = 2.dp))
                            }
                            if (notice.rawTitle.isNotBlank()) {
                                Text(
                                    "FAA notice:",
                                    modifier = Modifier.padding(top = 2.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (notice.rawTitle.isNotBlank()) {
                                Text(
                                    notice.rawTitle,
                                    modifier = Modifier.padding(top = 2.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (notice.rawText.isNotBlank()) {
                                Text(
                                    notice.rawText,
                                    modifier = Modifier.padding(top = 2.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    )
}

@Composable
private fun AirspaceRestrictionsPanel(
    state: AirspaceUiState,
    notamState: NotamUiState,
    onDismiss: () -> Unit
) {
    val coordinateDisplayFormat =
        CoordinateDisplayFormat.fromStorage(CaltopoClient.GetCoordinateDisplayFormat())
    val currentLocationText = CaltopoMap.GetMyLocation()?.let { location ->
        CoordinateFormatter.format(location.latitude, location.longitude, coordinateDisplayFormat)
    } ?: "Unavailable"

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Column {
                Text("Nearby Airspace Restrictions")
                Text("Not flight authorization. Verify independently.", style = MaterialTheme.typography.labelMedium)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                AirspaceSafetyNotice()
                Text("Location: $currentLocationText")
                Text("Operating area: 1 statute mile")
                AirspaceQueryMetadata(state)
                Spacer(Modifier.height(10.dp))
                if (state.summary.isNotBlank()) {
                    Text(state.summary, fontWeight = FontWeight.SemiBold)
                }
                if (state.detail.isNotBlank()) {
                    Text(
                        state.detail,
                        modifier = Modifier.padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Source: FAA UAS Facility Map (not the NOTAM database).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Text("Nearby NOTAMs", fontWeight = FontWeight.SemiBold)
                Text("Query radius: ${notamState.radiusStatuteMiles} statute mile(s)")
                notamState.lastUpdatedText?.let { Text(it) }
                if (notamState.queryLatitude != null && notamState.queryLongitude != null) {
                    Text("Query location: " + CoordinateFormatter.format(notamState.queryLatitude, notamState.queryLongitude, coordinateDisplayFormat))
                }
                Text(
                    notamState.chipLabel,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (notamState.statusLine.isNotBlank()) {
                    Text(
                        notamState.statusLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                notamState.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (notamState.notices.isEmpty()) {
                    Text(
                        when {
                            !notamState.enabled -> "Nearby NOTAM monitoring is disabled."
                            !notamState.configured ->
                                "Import the r2c-tracker organization QR code to enable NOTAM queries."
                            else -> "No notices are displayed. Check query status above; this is not a flight clearance."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    notamState.notices.forEach { notice ->
                        Spacer(Modifier.height(8.dp))
                        Text(notice.title, fontWeight = FontWeight.SemiBold)
                        if (notice.summary.isNotBlank()) {
                            Text(
                                notice.summary,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "NOTAM source: FAA NOTAM data via the configured r2c-tracker organization proxy.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun AirspaceSafetyNotice() {
    val uriHandler = LocalUriHandler.current
    Text("Not flight authorization. Verify restrictions and operational conditions independently.", fontWeight = FontWeight.SemiBold)
    Text("These queries do not establish that flight is safe. Firefighting aircraft may be present even without a TFR. Emergency-response flights require applicable authorization and incident air-operations coordination.")
    TextButton(onClick = { uriHandler.openUri("https://www.faa.gov/pilots/safety/notams_tfr") }) { Text("FAA NOTAM / TFR resources") }
    TextButton(onClick = { uriHandler.openUri("https://www.faa.gov/uas/getting_started/b4ufly") }) { Text("FAA-approved planning providers") }
}

@Composable
private fun AirspaceQueryMetadata(state: AirspaceUiState) {
    Text("Last successful facility-map check: ${state.lastSuccessfulCheck}")
    state.queryCoordinate?.let { Text("Query location: %.5f, %.5f • radius: 1 statute mile".format(java.util.Locale.US, it.latitude, it.longitude)) }
}

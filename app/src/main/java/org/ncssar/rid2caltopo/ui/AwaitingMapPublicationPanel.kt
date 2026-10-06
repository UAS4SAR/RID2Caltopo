package org.ncssar.rid2caltopo.ui

import android.location.Location
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterCenterFocus
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.ArchiveFileDeletion
import org.ncssar.rid2caltopo.data.AwaitingMapArchiveFiles
import org.ncssar.rid2caltopo.data.AwaitingMapFlightDiscarder
import org.ncssar.rid2caltopo.data.AwaitingMapFlightProximity
import org.ncssar.rid2caltopo.data.AwaitingMapFlightText
import org.ncssar.rid2caltopo.data.AwaitingMapFlights
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.video.AndroidClueStore
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AwaitingMap"
private val InsideGreen = Color(0xFF30D158)
private val BearingBlue = Color(0xFF0A84FF)

/** Locale date/time like "10/6/2026, 6:12 AM" (four-digit year, matching iOS). */
private fun flightStart(timeMs: Long): String {
    val locale = Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "yMdjmm")
    return SimpleDateFormat(pattern, locale).format(Date(timeMs))
}

private fun usableFix(location: Location?): Location? =
    location?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() && !(it.latitude == 0.0 && it.longitude == 0.0) }

@Composable
fun AwaitingMapPublicationPanel(onChooseMap: () -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(emptyList<JSONObject>()) }
    var cluePhotoCounts by remember { mutableStateOf(emptyMap<String, Int>()) }
    var clueIdsByFlight by remember { mutableStateOf(emptyMap<String, List<String>>()) }
    var location by remember { mutableStateOf<Location?>(null) }
    var mapId by remember { mutableStateOf("") }
    var mapName by remember { mutableStateOf("") }
    var reviewing by remember { mutableStateOf(false) }
    val reminder = remember { AwaitingMapReminder() }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var discardCandidate by remember { mutableStateOf<JSONObject?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(refreshTick) {
        while (true) {
            pending = AwaitingMapFlights.pending()
            mapId = CaltopoMap.GetMapId()
            mapName = CaltopoMap.GetMapName()
            // Re-read the device fix on every pass so distances follow the operator while open.
            location = usableFix(CaltopoMap.GetMyLocation())
            if (reviewing) {
                val flights = pending
                val owned = withContext(Dispatchers.IO) {
                    val store = AndroidClueStore.shared(context)
                    val all = AwaitingMapFlights.allEntries()
                    flights.associate { it.getString("id") to store.awaitingFlightClues(it, all).map { clue -> clue.id } }
                }
                clueIdsByFlight = owned
                cluePhotoCounts = owned.mapValues { it.value.size }
            }
            val eligible = pending.filter { !it.optBoolean("finished") && it.optString("map").isBlank() }
                .map { it.getString("id") }.toSet()
            if (reminder.shouldPresent(eligible, mapId.isNotBlank())) { reviewing = true; refreshTick++ }
            if (pending.isEmpty()) { reviewing = false; selected = null; discardCandidate = null }
            delay(2_000)
        }
    }
    if (pending.isNotEmpty()) {
        TextButton(onClick = { reviewing = true; refreshTick++ }) {
            Text("${pending.size} flight(s) awaiting a map — Review")
        }
    }
    if (reviewing) {
        val ic = CaltopoMap.GetArtifactFeatureSnapshot()
        val flights = pending.sortedByDescending { AwaitingMapFlights.suggested(it, ic) }
        AwaitingMapReviewDialog(
            flights = flights,
            nearIc = flights.associate { it.getString("id") to AwaitingMapFlights.suggested(it, ic) },
            cluePhotoCounts = cluePhotoCounts,
            location = location,
            mapId = mapId,
            mapName = mapName,
            onDismiss = { reviewing = false },
            onChooseMap = { reviewing = false; onChooseMap() },
            onPublish = { selected = it },
            onDiscard = { discardCandidate = it },
        )
    }
    selected?.let { flight ->
        // Capture the displayed destination, so a map change cannot redirect the accepted offer.
        val destination = remember(flight) { flight.optString("map").ifBlank { mapId } }
        val destinationName = remember(flight) { if (flight.optString("map").isNotBlank() && flight.optString("map") != mapId) "Original incident map" else mapName }
        val team = remember(flight) { if (flight.optString("map").isNotBlank()) flight.optString("team") else AwaitingMapFlights.selectedPublicationScope() }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Publish earlier flight?") },
            text = { Text("${flight.optString("label")}\nTo $destinationName ($destination)\nIncludes the recorded track and associated clues marked for publication. Clues kept local stay local.") },
            confirmButton = { TextButton(onClick = {
                runCatching { AwaitingMapFlights.decide(flight.getString("id"), destination, team) }
                    .onFailure { CaltopoClient.ShowToast("Publication choice could not be saved; please retry.") }
                selected = null; pending = AwaitingMapFlights.pending()
            }, enabled = destination.isNotBlank() && team.isNotBlank()) { Text("Publish") } },
            dismissButton = { TextButton(onClick = {
                runCatching { AwaitingMapFlights.decide(flight.getString("id"), null, team) }
                    .onFailure { CaltopoClient.ShowToast("Publication choice could not be saved; please retry.") }
                selected = null; pending = AwaitingMapFlights.pending()
            }) { Text("Keep local") } })
    }
    discardCandidate?.let { flight ->
        val id = flight.getString("id")
        val clueIds = clueIdsByFlight[id].orEmpty()
        AlertDialog(
            onDismissRequest = { discardCandidate = null },
            title = { Text(AwaitingMapFlightText.DISCARD_TITLE) },
            text = {
                Text("${flight.optString("label")} · ${flightStart(AwaitingMapFlights.firstTime(flight))}\n" +
                    AwaitingMapFlightText.discardMessage(clueIds.size))
            },
            dismissButton = { TextButton(onClick = { discardCandidate = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardCandidate = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { discardFlight(context, flight, clueIds) }
                            if (result.succeeded) {
                                CaltopoClient.CTInfo(TAG, result.logSummary(flight))
                            } else {
                                CaltopoClient.CTWarn(TAG, result.logSummary(flight))
                                CaltopoClient.ShowToast("Flight log was not fully discarded; please retry.")
                            }
                            pending = AwaitingMapFlights.pending()
                            refreshTick++
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Discard") }
            },
        )
    }
}

/** Real discard: clue store, operator archive tree, and the awaiting-map journal. */
private fun discardFlight(context: android.content.Context, flight: JSONObject, clueIds: List<String>): AwaitingMapFlightDiscarder.Result {
    val current = AwaitingMapFlights.pending().firstOrNull { it.optString("id") == flight.optString("id") }
    if (current == null || !current.optBoolean("finished")) {
        return AwaitingMapFlightDiscarder.Result(false, emptyList(), emptyList(), listOf("flight not found or still in progress"))
    }
    val store = AndroidClueStore.shared(context)
    val archiveRoot = CaltopoClient.GetArchiveDir()
    val archive = archiveRoot?.let { root ->
        AwaitingMapArchiveFiles { day, name ->
            val file = root.findFile(day)?.takeIf { it.isDirectory }?.findFile(name)?.takeIf { it.isFile }
            when {
                file == null -> ArchiveFileDeletion.NOT_FOUND
                file.delete() -> ArchiveFileDeletion.DELETED
                else -> ArchiveFileDeletion.FAILED
            }
        }
    }
    if (archive == null) CaltopoClient.CTInfo(TAG, "Discard: no archive folder available; no archive files to delete")
    return AwaitingMapFlightDiscarder.discard(
        flight = current,
        clueIds = clueIds,
        deleteClue = { store.delete(it) },
        archive = archive,
        removeEntry = { AwaitingMapFlights.discard(it) },
    )
}

@Composable
private fun AwaitingMapReviewDialog(
    flights: List<JSONObject>,
    nearIc: Map<String, Boolean>,
    cluePhotoCounts: Map<String, Int>,
    location: Location?,
    mapId: String,
    mapName: String,
    onDismiss: () -> Unit,
    onChooseMap: () -> Unit,
    onPublish: (JSONObject) -> Unit,
    onDiscard: (JSONObject) -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 1_100.dp)
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.9f),
        ) {
            Column {
                Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp)) {
                    Text(
                        AwaitingMapFlightText.TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text("Dismiss", fontSize = 16.sp)
                    }
                }
                HorizontalDivider()
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                ) {
                    MapBanner(mapId, mapName, onChooseMap)
                    val summary = location?.let {
                        AwaitingMapFlightText.locationSummary(
                            if (it.hasAccuracy()) it.accuracy.toDouble() else null,
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.time)),
                        )
                    } ?: AwaitingMapFlightText.LOCATION_UNAVAILABLE
                    FitsHorizontally(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 7.dp),
                        wide = {
                            Row(Modifier.fillMaxWidth()) {
                                SectionText(AwaitingMapFlightText.flightCountHeader(flights.size))
                                Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
                                SectionText(summary)
                            }
                        },
                        narrow = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                SectionText(AwaitingMapFlightText.flightCountHeader(flights.size))
                                SectionText(summary)
                            }
                        },
                    )
                    // One layout decision for every row, so rows never mix wide and stacked forms.
                    FitsHorizontally(
                        wide = { FlightGroup(flights, nearIc, cluePhotoCounts, location, mapId, true, onPublish, onDiscard) },
                        narrow = { FlightGroup(flights, nearIc, cluePhotoCounts, location, mapId, false, onPublish, onDiscard) },
                    )
                    Text(
                        buildAnnotatedString {
                            append(AwaitingMapFlightText.FOOTER_PUBLISH)
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Discard") }
                            append(AwaitingMapFlightText.FOOTER_DISCARD)
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Dismiss") }
                            append(AwaitingMapFlightText.FOOTER_DISMISS)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionText(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, softWrap = false)
}

@Composable
private fun MapBanner(mapId: String, mapName: String, onChooseMap: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (mapId.isBlank()) {
            val text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("No map connected.") }
                append(" " + AwaitingMapFlightText.NO_MAP_BANNER)
            }
            val icon = @Composable { Icon(Icons.Outlined.Info, contentDescription = null, tint = Color(0xFFFF9F0A)) }
            val button = @Composable {
                FilledTonalButton(onClick = onChooseMap) {
                    Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Choose Map…", maxLines = 1, softWrap = false)
                }
            }
            FitsHorizontally(
                wide = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        icon()
                        Spacer(Modifier.width(12.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
                        Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
                        button()
                    }
                },
                narrow = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row { icon(); Spacer(Modifier.width(12.dp)); Text(text, style = MaterialTheme.typography.bodyMedium) }
                        button()
                    }
                },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Destination: $mapName ($mapId)", style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FlightGroup(
    flights: List<JSONObject>,
    nearIc: Map<String, Boolean>,
    cluePhotoCounts: Map<String, Int>,
    location: Location?,
    mapId: String,
    wide: Boolean,
    onPublish: (JSONObject) -> Unit,
    onDiscard: (JSONObject) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
    ) {
        flights.forEachIndexed { index, flight ->
            val id = flight.getString("id")
            FlightRow(
                flight = flight,
                proximity = location?.let { AwaitingMapFlightProximity.forFlight(flight, it.latitude, it.longitude) },
                cluePhotoCount = cluePhotoCounts[id] ?: 0,
                nearIc = nearIc[id] == true,
                canPublish = mapId.isNotBlank(),
                wide = wide,
                onPublish = { onPublish(flight) },
                onDiscard = { onDiscard(flight) },
            )
            if (index < flights.lastIndex) HorizontalDivider(Modifier.padding(start = 74.dp))
        }
    }
}

@Composable
private fun FlightRow(
    flight: JSONObject,
    proximity: AwaitingMapFlightProximity?,
    cluePhotoCount: Int,
    nearIc: Boolean,
    canPublish: Boolean,
    wide: Boolean,
    onPublish: () -> Unit,
    onDiscard: () -> Unit,
) {
    val rowModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
    if (wide) {
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            CompassChip(proximity)
            Spacer(Modifier.width(14.dp))
            FlightDetails(flight, proximity, cluePhotoCount, nearIc, wide = true)
            Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
            FlightActions(flight, canPublish, onPublish, onDiscard)
        }
    } else {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CompassChip(proximity)
                Spacer(Modifier.width(14.dp))
                FlightDetails(flight, proximity, cluePhotoCount, nearIc, wide = false)
            }
            Row(Modifier.padding(start = 58.dp)) { FlightActions(flight, canPublish, onPublish, onDiscard) }
        }
    }
}

@Composable
private fun FlightDetails(flight: JSONObject, proximity: AwaitingMapFlightProximity?, cluePhotoCount: Int, nearIc: Boolean, wide: Boolean) {
    val duration = AwaitingMapFlights.lastTime(flight) - AwaitingMapFlights.firstTime(flight)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(flight.optString("label"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false)
            Spacer(Modifier.width(12.dp))
            Text(flightStart(AwaitingMapFlights.firstTime(flight)), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(bottom = 2.dp))
        }
        val items: List<@Composable () -> Unit> = listOf(
            { MetaItem(Icons.Outlined.Timer, AwaitingMapFlightText.duration(duration)) },
            { MetaItem(Icons.Outlined.PhotoCamera, AwaitingMapFlightText.cluePhotos(cluePhotoCount), dim = cluePhotoCount == 0) },
            { LocationItem(proximity) },
        )
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { index, item ->
                    if (index > 0) Text("  ·  ", color = MaterialTheme.colorScheme.outline, softWrap = false)
                    item()
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { items.forEach { it() } }
        }
        if (nearIc) Text("Recent flight near IC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetaItem(icon: ImageVector, text: String, dim: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, softWrap = false,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LocationItem(proximity: AwaitingMapFlightProximity?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (proximity) {
            AwaitingMapFlightProximity.Inside -> {
                Icon(Icons.Outlined.FilterCenterFocus, contentDescription = null, tint = InsideGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(AwaitingMapFlightText.INSIDE_AREA, style = MaterialTheme.typography.bodyLarge, color = InsideGreen,
                    fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
            is AwaitingMapFlightProximity.Away -> {
                Icon(Icons.Outlined.Place, contentDescription = null, tint = BearingBlue, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(AwaitingMapFlightText.location(proximity), style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, softWrap = false)
            }
            null -> {
                Icon(Icons.Outlined.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(AwaitingMapFlightText.DISTANCE_UNAVAILABLE, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun FlightActions(flight: JSONObject, canPublish: Boolean, onPublish: () -> Unit, onDiscard: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(onClick = onPublish, enabled = canPublish) {
            Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Publish…", maxLines = 1, softWrap = false)
        }
        val error = MaterialTheme.colorScheme.error
        FilledTonalButton(
            onClick = onDiscard,
            // A flight still in the air keeps recording; it can be discarded once it ends.
            enabled = flight.optBoolean("finished"),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = error.copy(alpha = 0.16f), contentColor = error),
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Discard", maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun CompassChip(proximity: AwaitingMapFlightProximity?) {
    val inside = proximity == AwaitingMapFlightProximity.Inside
    val ring = if (inside) InsideGreen else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape)
            .border(BorderStroke(1.2.dp, ring), CircleShape)
            .semantics { contentDescription = AwaitingMapFlightText.location(proximity) },
        contentAlignment = Alignment.Center,
    ) {
        Text("N", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = 2.dp))
        when (proximity) {
            AwaitingMapFlightProximity.Inside -> Canvas(Modifier.size(22.dp)) {
                drawCircle(InsideGreen, radius = size.minDimension / 2 - 1.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.4.dp.toPx()))))
                drawCircle(InsideGreen, radius = 4.5.dp.toPx(), center = Offset(size.width / 2, size.height / 2))
            }
            is AwaitingMapFlightProximity.Away -> Icon(Icons.Filled.Navigation, contentDescription = null, tint = BearingBlue,
                modifier = Modifier.size(22.dp).rotate(proximity.bearingDegrees.toFloat()))
            null -> Icon(Icons.Outlined.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Compose counterpart of SwiftUI ViewThatFits for one axis: shows [wide] when its single-line
 * intrinsic width fits the available width, otherwise [narrow].
 */
@Composable
private fun FitsHorizontally(modifier: Modifier = Modifier, wide: @Composable () -> Unit, narrow: @Composable () -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val wideMeasurables = subcompose("wide", wide)
        val needed = wideMeasurables.maxOfOrNull { it.maxIntrinsicWidth(constraints.maxHeight) } ?: 0
        val chosen = if (needed <= constraints.maxWidth) wideMeasurables else subcompose("narrow", narrow)
        val placeables = chosen.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = placeables.sumOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = 0
            placeables.forEach { it.placeRelative(0, y); y += it.height }
        }
    }
}

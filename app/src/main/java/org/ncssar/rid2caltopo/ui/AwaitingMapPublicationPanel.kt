package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.AwaitingMapFlights
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoClient
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun AwaitingMapPublicationPanel() {
    var pending by remember { mutableStateOf(emptyList<JSONObject>()) }
    var mapId by remember { mutableStateOf("") }
    var mapName by remember { mutableStateOf("") }
    var reviewing by remember { mutableStateOf(false) }
    val reminder = remember { AwaitingMapReminder() }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            pending = AwaitingMapFlights.pending()
            mapId = CaltopoMap.GetMapId()
            mapName = CaltopoMap.GetMapName()
            val eligible = pending.filter { !it.optBoolean("finished") && it.optString("map").isBlank() }
                .map { it.getString("id") }.toSet()
            if (reminder.shouldPresent(eligible, mapId.isNotBlank())) reviewing = true
            if (pending.isEmpty()) { reviewing = false; selected = null }
            delay(2_000)
        }
    }
    if (pending.isNotEmpty()) {
        TextButton(onClick = { reviewing = true }) {
            Text("${pending.size} flight(s) awaiting a map — Review")
        }
    }
    if (reviewing) AlertDialog(onDismissRequest = { reviewing = false }, title = { Text("Flights awaiting a map") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (mapId.isBlank()) "Select an incident map to publish. Flights and clues remain saved locally." else "Destination: $mapName ($mapId)")
                pending.sortedByDescending { AwaitingMapFlights.suggested(it, CaltopoMap.GetArtifactFeatureSnapshot()) }.forEach { flight ->
                    TextButton(onClick = { selected = flight }, enabled = mapId.isNotBlank()) {
                        Column {
                            Text(flight.optString("label"))
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(AwaitingMapFlights.firstTime(flight))))
                            if (AwaitingMapFlights.suggested(flight, CaltopoMap.GetArtifactFeatureSnapshot())) Text("Recent flight near IC")
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { reviewing = false }) { Text("Later") } })
    selected?.let { flight ->
        // Capture the displayed destination, so a map change cannot redirect the accepted offer.
        val destination = remember(flight) { flight.optString("map").ifBlank { mapId } }
        val destinationName = remember(flight) { if (flight.optString("map").isNotBlank() && flight.optString("map") != mapId) "Original incident map" else mapName }
        val team = remember(flight) { if (flight.optString("map").isNotBlank()) flight.optString("team") else CaltopoClient.GetCaltopoCredentials().teamId.orEmpty() }
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
}

package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FlightReadinessFields(remoteId: String, callsign: String, value: FlightReadiness, onChange: (FlightReadiness) -> Unit,
                         onPilotSelected: (String) -> Unit) {
    var rosterState by remember { mutableStateOf(AircraftOrganizationAccess.cachedState()) }
    var equipmentOpen by remember { mutableStateOf(false) }
    LaunchedEffect(remoteId) {
        withContext(Dispatchers.IO) { AircraftOrganizationAccess.refresh() }
        rosterState = AircraftOrganizationAccess.cachedState()
    }
    val update by AircraftOrganizationAccess.changes.collectAsState()
    LaunchedEffect(update) { rosterState = AircraftOrganizationAccess.cachedState() }
    val fleet = rosterState.optJSONArray("aircraft")
    val entry = (0 until (fleet?.length() ?: 0)).map { fleet!!.getJSONObject(it) }
        .firstOrNull { it.optString("remoteId").equals(remoteId, true) }
    val service = entry?.optJSONArray("history")?.optJSONObject(0) ?: JSONObject()
    val pilots = rosterState.optJSONArray("pilots")
    LaunchedEffect(service.toString(), rosterState.optString("fetchedAt"), callsign) {
        val local = CaltopoClient.GetPersistedDroneSpecs().firstOrNull { it.remoteId.equals(remoteId, true) }
        onChange(value.withAircraft(local?.readiness, entry?.optJSONObject("readiness")?.let { AircraftReadiness.fromJSON(it) })
            .resolvingPilot(callsign, pilots).copy(serviceJson = service.toString(),
            rosterFetchedAt = rosterState.optString("fetchedAt"), configurationVersion = rosterState.optLong("configurationVersion")))
    }
    TextButton(onClick = { equipmentOpen = true }) {
        Text("Equipment & payload: " + value.equipmentSummary())
    }
    OperatingProfileFields(remoteId, value, rosterState, onChange)
    if (AircraftOrganizationAccess.belongsToOrganization() && !AircraftOrganizationAccess.eligiblePilot(value.pilotJson))
        Text(value.pilotQualificationWarning(), style = MaterialTheme.typography.bodySmall)
    if (service.optString("status") == "out_of_service") Text("Aircraft out of service: " + service.optString("note"), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = { equipmentOpen = !equipmentOpen }) { Text("Equipment, payload & weight ${if (equipmentOpen) "▾" else "▸"}") }
    if (equipmentOpen) {
        Text("Equipment and payload are remembered for this aircraft after publishing. Review them before each flight.")
        value.aircraft.accessories.forEach { accessory ->
            Row {
                Checkbox(checked = accessory.id in value.selectedAccessories, onCheckedChange = { selected ->
                    val next = value.selectedAccessories.toMutableSet()
                    if (selected) {
                        if (accessory.group.isNotBlank()) next.removeAll(value.aircraft.accessories.filter { it.group == accessory.group }.map { it.id }.toSet())
                        next.add(accessory.id)
                    } else next.remove(accessory.id)
                    onChange(value.copy(selectedAccessories = next))
                })
                Text(accessory.name + if (accessory.required) " (required)" else "")
            }
        }
        OutlinedTextField(value = value.payloadDescription, onValueChange = { onChange(value.copy(payloadDescription = it)) }, label = { Text("Takeoff payload") })
        WeightField("Payload weight (grams)", value.payloadWeightGrams) { onChange(value.copy(payloadWeightGrams = it)) }
        Text("Takeoff weight: " + (value.aircraft.totalWeight(value.selectedAccessories, value.payloadDescription, value.payloadWeightGrams)?.let { "$it grams" } ?: "incomplete — weight unknown"))
    }
}

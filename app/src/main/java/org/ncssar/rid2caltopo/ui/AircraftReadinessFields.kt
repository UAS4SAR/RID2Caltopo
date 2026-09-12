package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.ncssar.rid2caltopo.data.AircraftReadiness
import org.ncssar.rid2caltopo.data.AircraftAccessory
import java.util.UUID

@Composable
fun AircraftReadinessFields(value: AircraftReadiness, enabled: Boolean, onChange: (AircraftReadiness) -> Unit) {
    @Composable fun field(label: String, text: String, change: (String) -> Unit) {
        OutlinedTextField(value = text, onValueChange = change, enabled = enabled,
            label = { Text(label) }, modifier = Modifier.fillMaxWidth())
    }
    field("Aircraft serial number", value.serialNumber) { onChange(value.copy(serialNumber = it)) }
    field("FAA registration number", value.registrationNumber) { onChange(value.copy(registrationNumber = it)) }
    WeightField("Base weight (grams)", value.baseWeightGrams, enabled) { onChange(value.copy(baseWeightGrams = it)) }
    field("Included in base weight", value.baseWeightIncludes) { onChange(value.copy(baseWeightIncludes = it)) }
    Text("Exclude the selectable battery, accessories and payload from base weight. Save measured weights once.")
    field("Required equipment", value.requiredEquipment) { onChange(value.copy(requiredEquipment = it)) }
    field("Air traffic monitoring equipment", value.monitoringEquipment) { onChange(value.copy(monitoringEquipment = it)) }
    Text("Examples: aircraft ADS-B warnings on controller; standalone skyAlert.")
    value.accessories.forEachIndexed { index, accessory ->
        key(accessory.id) {
            fun update(item: AircraftAccessory) = onChange(value.copy(accessories = value.accessories.toMutableList().also { it[index] = item }))
            field("Accessory / battery name", accessory.name) { update(accessory.copy(name = it)) }
            WeightField("Accessory weight (grams)", accessory.weightGrams, enabled) { update(accessory.copy(weightGrams = it)) }
            field("Choose-one group (for example battery)", accessory.group) { update(accessory.copy(group = it)) }
            Row { Checkbox(checked = accessory.required, enabled = enabled, onCheckedChange = { update(accessory.copy(required = it)) }); Text("Required equipment") }
            TextButton(enabled = enabled, onClick = { onChange(value.copy(accessories = value.accessories.filter { it.id != accessory.id })) }) { Text("Remove accessory") }
        }
    }
    TextButton(enabled = enabled && value.accessories.size < 32, onClick = {
        onChange(value.copy(accessories = value.accessories + AircraftAccessory(UUID.randomUUID().toString(), "")))
    }) { Text("Add accessory or battery") }
}

@Composable
fun WeightField(label: String, value: Double?, enabled: Boolean = true, onChange: (Double?) -> Unit) {
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    val parsed = text.toDoubleOrNull()
    val valid = text.isBlank() || (parsed != null && parsed.isFinite() && parsed in 0.0..100000.0)
    OutlinedTextField(value = text, enabled = enabled, onValueChange = {
        text = it
        onChange(it.toDoubleOrNull()?.takeIf { number -> number.isFinite() && number in 0.0..100000.0 })
    }, label = { Text(label) }, isError = !valid, supportingText = { Text(if (valid) "Blank means unknown" else "Enter a nonnegative weight in grams") }, modifier = Modifier.fillMaxWidth())
}

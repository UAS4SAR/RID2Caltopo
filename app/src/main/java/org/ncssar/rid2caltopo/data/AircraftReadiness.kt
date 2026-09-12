package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable

data class AircraftAccessory(
    val id: String,
    val name: String,
    val weightGrams: Double? = null,
    val required: Boolean = false,
    val group: String = ""
) : Serializable

data class AircraftReadiness(
    val serialNumber: String = "",
    val registrationNumber: String = "",
    val baseWeightGrams: Double? = null,
    val baseWeightIncludes: String = "",
    val requiredEquipment: String = "",
    val monitoringEquipment: String = "",
    val accessories: List<AircraftAccessory> = emptyList(),
    val recordId: String = ""
) : Serializable {
    fun validationErrors(): List<String> = buildList {
        if (baseWeightGrams != null && (!baseWeightGrams.isFinite() || baseWeightGrams !in 0.0..100000.0)) add("Invalid base weight.")
        if (accessories.size > 32 || accessories.map { it.id }.toSet().size != accessories.size) add("Accessory identifiers must be unique (maximum 32).")
        accessories.forEach {
            if (it.name.isBlank()) add("Give each accessory or battery a name.")
            if (it.weightGrams != null && (!it.weightGrams.isFinite() || it.weightGrams !in 0.0..100000.0)) add("Invalid accessory weight.")
        }
    }

    fun totalWeight(selected: Set<String>, payloadDescription: String, payloadGrams: Double?): Double? {
        if (selected.any { id -> accessories.none { it.id == id } }) return null
        val installed = accessories.filter { it.id in selected }
        if (installed.filter { it.group.isNotBlank() }.groupBy { it.group }.any { it.value.size > 1 }) return null
        if (baseWeightGrams == null || installed.any { it.weightGrams == null } ||
            (payloadDescription.isNotBlank() && payloadGrams == null)) return null
        val weights = listOf(baseWeightGrams, payloadGrams ?: 0.0) + installed.map { it.weightGrams!! }
        if (weights.any { !it.isFinite() || it < 0 }) return null
        return weights.sum()
    }

    fun toJSON(): JSONObject = JSONObject().apply {
        put("recordId", recordId)
        put("serialNumber", serialNumber); put("registrationNumber", registrationNumber)
        put("baseWeightGrams", baseWeightGrams ?: JSONObject.NULL)
        put("baseWeightIncludes", baseWeightIncludes); put("requiredEquipment", requiredEquipment)
        put("monitoringEquipment", monitoringEquipment)
        put("accessories", JSONArray().apply { accessories.forEach { accessory ->
            put(JSONObject().apply {
                put("id", accessory.id); put("name", accessory.name)
                put("weightGrams", accessory.weightGrams ?: JSONObject.NULL)
                put("required", accessory.required); put("group", accessory.group)
            })
        } })
    }

    companion object {
        @JvmStatic fun fromJSON(value: JSONObject?): AircraftReadiness {
            if (value == null) return AircraftReadiness()
            val items = value.optJSONArray("accessories") ?: JSONArray()
            return AircraftReadiness(
                value.optString("serialNumber"), value.optString("registrationNumber"),
                value.optionalWeight("baseWeightGrams"), value.optString("baseWeightIncludes"),
                value.optString("requiredEquipment"), value.optString("monitoringEquipment"),
                (0 until items.length()).map { index -> items.getJSONObject(index).let {
                    AircraftAccessory(it.getString("id"), it.getString("name"),
                        it.optionalWeight("weightGrams"), it.optBoolean("required"), it.optString("group"))
                } }, value.optString("recordId")
            )
        }
        private fun JSONObject.optionalWeight(key: String): Double? =
            if (!has(key) || isNull(key)) null else getDouble(key).also {
                require(it.isFinite() && it >= 0 && it <= 100000) { "Invalid weight in grams" }
            }
    }
}

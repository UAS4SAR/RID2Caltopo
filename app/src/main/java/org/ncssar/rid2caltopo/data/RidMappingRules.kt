package org.ncssar.rid2caltopo.data

import java.util.Locale

data class EditableRidMapping(
    val remoteId: String,
    val ownerName: String,
    val ownerCallsign: String,
    val model: String,
    val readiness: AircraftReadiness = AircraftReadiness()
) {
    fun mappedId(): String = CtDroneSpec.BuildMappedId(ownerCallsign, model, remoteId)
}

data class RidMappingOwnerFields(
    val ownerName: String,
    val ownerCallsign: String
)

object RidMappingRules {
    private val remoteIdPattern = Regex("^[A-Z0-9]+$")
    private val callsignPattern = Regex("^[0-9]+[A-Za-z]+[0-9]+(?:-[0-9]+)?$")
    private val ownerNamePlaceholders = setOf("owner name")
    private val ownerCallsignPlaceholders = setOf("owner callsign")

    fun normalizeRemoteId(value: String): String =
        value.trim().uppercase(Locale.US)

    fun normalizeOrganization(value: String): String = value.trim()

    fun resolveOwnerFields(
        ownerName: String?,
        ownerCallsign: String?,
        legacyOwner: String?,
        mappedId: String,
        model: String,
        remoteId: String
    ): RidMappingOwnerFields {
        val explicitName = ownerName.cleanUnlessPlaceholder(ownerNamePlaceholders)
        val explicitCallsign = ownerCallsign.cleanUnlessPlaceholder(ownerCallsignPlaceholders)
        val legacy = legacyOwner?.trim().orEmpty()
        val derivedCallsign = CtDroneSpec.GuessPilotCallsign(mappedId, model, remoteId)

        val resolvedCallsign = sequenceOf(explicitCallsign, legacy, derivedCallsign)
            .firstOrNull(::looksLikeCallsign)
            .orEmpty()
        val resolvedName = sequenceOf(explicitName, legacy, explicitCallsign)
            .firstOrNull { it.isNotEmpty() && !looksLikeCallsign(it) }
            .orEmpty()

        return RidMappingOwnerFields(
            ownerName = resolvedName,
            ownerCallsign = resolvedCallsign
        )
    }

    private fun looksLikeCallsign(value: String): Boolean =
        callsignPattern.matches(value.trim())

    private fun String?.cleanUnlessPlaceholder(placeholders: Set<String>): String {
        val value = this?.trim().orEmpty()
        return value.takeUnless { it.lowercase(Locale.US) in placeholders }.orEmpty()
    }

    fun validateEntry(organization: String, entry: EditableRidMapping, others: List<EditableRidMapping>): List<String> {
        val errors = validate(organization, listOf(entry)).toMutableList()
        if (others.any { normalizeRemoteId(it.remoteId) == normalizeRemoteId(entry.remoteId) })
            errors += "Remote ID is already listed."
        if (others.any { it.ownerCallsign.trim().equals(entry.ownerCallsign.trim(), true) && it.model.trim().equals(entry.model.trim(), true) })
            errors += "Model must be unique for this owner callsign."
        return errors.map { it.removePrefix("Aircraft 1: ") }
    }

    fun validate(
        organization: String,
        mappings: List<EditableRidMapping>
    ): List<String> {
        val errors = mutableListOf<String>()
        if (normalizeOrganization(organization).isEmpty()) {
            errors += "Organization is required."
        }
        val remoteIds = mutableSetOf<String>()
        val ownerModels = mutableSetOf<String>()
        mappings.forEachIndexed { index, raw ->
            val row = index + 1
            errors += raw.readiness.validationErrors().map { "Aircraft $row: $it" }
            val remoteId = normalizeRemoteId(raw.remoteId)
            val callsign = raw.ownerCallsign.trim()
            val model = raw.model.trim()
            if (!remoteIdPattern.matches(remoteId)) {
                errors += "Aircraft $row: Remote ID must contain only A-Z and 0-9."
            } else if (!remoteIds.add(remoteId)) {
                errors += "Aircraft $row: Remote ID is already listed."
            }
            if (!callsignPattern.matches(callsign)) {
                errors += "Aircraft $row: Owner callsign must look like 1SAR7."
            }
            if (model.isEmpty()) {
                errors += "Aircraft $row: Model is required."
            } else {
                val ownerModelKey = "${callsign.lowercase(Locale.US)}\u0000${model.lowercase(Locale.US)}"
                if (!ownerModels.add(ownerModelKey)) {
                    errors += "Aircraft $row: Model must be unique for this owner callsign."
                }
            }
        }
        return errors
    }
}

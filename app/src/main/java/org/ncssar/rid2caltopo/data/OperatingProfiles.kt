package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Profile decisions are advisory and do not participate in flight admission. */
object OperatingProfiles {
    fun standard() = JSONObject().put("id", "standard-part-107").put("version", 1)
        .put("name", "Standard Part 107 — no operational waiver").put("authorityType", "part107").put("conditions", JSONArray())
    fun bvlos() = JSONObject().put("id", "bvlos-pending").put("version", 1).put("name", "BVLOS — authority details pending").put("authorityType", "unresolved").put("conditions", JSONArray())
    fun other() = JSONObject().put("id", "other-pending").put("version", 1)
        .put("name", "Other / details pending").put("authorityType", "unresolved").put("conditions", JSONArray())
    fun choices(state: JSONObject): List<JSONObject> = listOf(standard()) +
        state.optJSONObject("operatingProfiles")?.optJSONArray("profiles").objects() + listOf(bvlos(), other())
    fun default(state: JSONObject): JSONObject {
        val id = state.optJSONObject("operatingProfiles")?.optString("defaultProfileId", "standard-part-107") ?: "standard-part-107"
        return choices(state).firstOrNull { it.optString("id") == id }
            ?: other().put("missingProfileId", id)
    }
    fun preferenceKey(organization: String) = "operating-profile-choice:" + organization.trim('/')
    fun preferred(state: JSONObject, savedId: String?): JSONObject {
        if (savedId.isNullOrBlank()) return default(state)
        return choices(state).firstOrNull { it.optString("id") == savedId }
            ?: other().put("missingProfileId", savedId)
    }
    fun warnings(profile: JSONObject, state: JSONObject, pilotId: String, aircraftId: String, mapId: String,
                 managed: Boolean, now: Instant = Instant.now()): List<String> = buildList {
        val today = now.toString().take(10)
        if (profile.optString("authorityType") !in listOf("part107", "part107_waiver")) add("Operating authority and qualification requirements need review.")
        if (profile.has("missingProfileId")) add("Selected profile is missing; confirm authority details.")
        for (key in listOf("effectiveFrom", "effectiveUntil")) {
            val date = profile.optString(key)
            if (date.isNotBlank()) {
                if (runCatching { LocalDate.parse(date) }.isFailure) add("Profile effective date needs review.")
                else if ((key == "effectiveFrom" && date > today) || (key == "effectiveUntil" && date < today)) add("Profile is outside its effective dates.")
            }
        }
        if (profile.optString("authorityType") == "part107_waiver" &&
            listOf("waiverNumber", "holder", "document", "effectiveFrom", "effectiveUntil").any { profile.optString(it).isBlank() }) add("Waiver details are incomplete; review the source document.")
        for ((key, selected) in listOf("pilotIds" to pilotId, "aircraftIds" to aircraftId, "locationIds" to mapId)) {
            val ids = profile.optJSONArray(key)
            if (ids != null && ids.length() > 0 && (0 until ids.length()).none { ids.optString(it) == selected }) add("Profile applicability needs review: $key.")
        }
        if (managed) {
            val fetched = runCatching { Instant.parse(state.optString("fetchedAt")) }.getOrNull()
            if (fetched == null || fetched.isBefore(now.minusSeconds(86400)) || fetched.isAfter(now.plusSeconds(300))) add("Saved profiles are missing or stale; synchronize when available.")
            val current = choices(state).firstOrNull { it.optString("id") == profile.optString("id") }
            if (current == null || current.optInt("version") != profile.optInt("version")) add("Profile has changed or is unavailable; review the saved version.")
        }
    }
    fun snapshot(profile: JSONObject, state: JSONObject, pilotId: String, aircraftId: String, mapId: String,
                 managed: Boolean, checked: Set<Int> = emptySet()): JSONObject = JSONObject()
        .put("profile", JSONObject(profile.toString())).put("selectedAt", Instant.now().toString())
        .put("organizationId", state.optString("organizationId")).put("incidentId", mapId)
        .put("assignmentId", assignmentId).put("catalogRevision", state.optJSONObject("operatingProfiles")?.optInt("revision") ?: 0)
        .put("fetchedAt", state.optString("fetchedAt")).put("checkedConditions", JSONArray(checked.sorted()))
        .put("reviewIssues", JSONArray(warnings(profile, state, pilotId, aircraftId, mapId, managed) +
            if ((0 until (profile.optJSONArray("conditions")?.length() ?: 0)).any { it !in checked }) listOf("One or more briefing conditions have not been acknowledged.") else emptyList<String>()))

    // Assignment identity and its snapshot are session-only; the separate saved choice is not an assignment.
    private var scope = ""
    var assignmentId = ""; private set
    private var remembered: String? = null
    @JvmStatic @Synchronized fun setScope(org: String, incident: String) {
        val next = JSONArray(listOf(org, incident)).toString()
        if (next != scope) { scope = next; endAssignment() }
    }
    @JvmStatic @Synchronized fun endAssignment() { assignmentId = ""; remembered = null }
    @Synchronized fun remember(profile: JSONObject) {
        if (assignmentId.isEmpty()) assignmentId = UUID.randomUUID().toString()
        remembered = profile.toString()
    }
    @Synchronized fun initial(state: JSONObject, savedId: String? = null): JSONObject = remembered?.let { JSONObject(it) } ?: preferred(state, savedId)
    fun active(readiness: JSONObject): JSONObject? = readiness.optJSONArray("operatingProfileChanges")?.objects()?.lastOrNull()?.optJSONObject("after")
        ?: readiness.optJSONObject("operatingProfile")
    @JvmStatic fun mergeHistory(previous: String, next: String): String = runCatching {
        val before = JSONObject(previous); val after = JSONObject(next)
        val old = active(before); val selected = after.optJSONObject("operatingProfile")
        if (old != null && selected != null) {
            val changes = JSONArray(before.optJSONArray("operatingProfileChanges")?.toString() ?: "[]")
            if ((old.optJSONObject("profile")?.toString() ?: "{}") != (selected.optJSONObject("profile")?.toString() ?: "{}") ||
                (old.optJSONArray("checkedConditions")?.toString() ?: "[]") != (selected.optJSONArray("checkedConditions")?.toString() ?: "[]")) {
                changes.put(JSONObject().put("at", Instant.now().toString()).put("before", old).put("after", selected))
            }
            after.put("operatingProfile", before.getJSONObject("operatingProfile"))
            after.put("operatingProfileChanges", changes)
        }
        if (old != null && selected == null) {
            after.put("operatingProfile", before.optJSONObject("operatingProfile"))
            after.put("operatingProfileChanges", before.optJSONArray("operatingProfileChanges") ?: JSONArray())
        }
        after.toString()
    }.getOrDefault(next)
    fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
}

package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class OperatingProfilesTest {
    private fun state(profile: JSONObject) = JSONObject().put("organizationId", "org")
        .put("fetchedAt", "2026-09-11T12:00:00Z").put("operatingProfiles", JSONObject()
            .put("defaultProfileId", profile.getString("id")).put("profiles", JSONArray().put(profile)))
    @Test fun savedChoiceUsesCurrentCatalogAndKeepsMissingChoiceVisible() {
        assertEquals("bvlos-pending", OperatingProfiles.preferred(JSONObject(), "bvlos-pending").getString("id"))
        val waiver = JSONObject().put("id", "waiver").put("version", 2)
        assertEquals(2, OperatingProfiles.preferred(state(waiver), "waiver").getInt("version"))
        assertEquals("waiver", OperatingProfiles.preferred(JSONObject(), "waiver").getString("missingProfileId"))
        assertEquals("standard-part-107", OperatingProfiles.preferred(JSONObject(), null).getString("id"))
        assertNotEquals(OperatingProfiles.preferenceKey("org-a"), OperatingProfiles.preferenceKey("org-b"))
        assertEquals(OperatingProfiles.preferenceKey("org-a/"), OperatingProfiles.preferenceKey("org-a"))
        OperatingProfiles.endAssignment()
        assertEquals("bvlos-pending", OperatingProfiles.initial(JSONObject(), "bvlos-pending").getString("id"))
        assertEquals("", OperatingProfiles.assignmentId)
    }
    @Test fun defaultsRequireExplicitOrganizationConfiguration() {
        assertEquals("standard-part-107", OperatingProfiles.default(JSONObject()).getString("id"))
        val waiver = OperatingProfiles.other().put("id", "waiver")
        assertEquals("waiver", OperatingProfiles.default(state(waiver)).getString("id"))
        val missing = state(waiver).getJSONObject("operatingProfiles").put("defaultProfileId", "missing")
        assertTrue(OperatingProfiles.default(JSONObject().put("operatingProfiles", missing)).has("missingProfileId"))
    }
    @Test fun assignmentEndsOnOrganizationOrIncidentChangeAndDoesNotResurrect() {
        OperatingProfiles.setScope("org", "incident-1")
        OperatingProfiles.remember(OperatingProfiles.other())
        val id = OperatingProfiles.assignmentId
        OperatingProfiles.setScope("org", "incident-1")
        assertEquals(id, OperatingProfiles.assignmentId)
        assertEquals("other-pending", OperatingProfiles.initial(JSONObject()).getString("id"))
        OperatingProfiles.setScope("org", "incident-2")
        OperatingProfiles.setScope("org", "incident-1")
        assertEquals("", OperatingProfiles.assignmentId)
        OperatingProfiles.remember(OperatingProfiles.other())
        OperatingProfiles.setScope("other-org", "incident-1")
        assertEquals("standard-part-107", OperatingProfiles.initial(JSONObject()).getString("id"))
    }
    @Test fun expiredStaleAndInapplicableAreAdvisories() {
        val waiver = OperatingProfiles.other().put("id", "waiver").put("authorityType", "part107_waiver")
            .put("effectiveUntil", "2025-01-01").put("pilotIds", JSONArray().put("different"))
        val issues = OperatingProfiles.warnings(waiver, state(waiver), "pilot", "aircraft", "incident", true, Instant.parse("2026-09-13T12:00:00Z"))
        assertTrue(issues.any { "effective dates" in it })
        assertTrue(issues.any { "stale" in it })
        assertTrue(issues.any { "applicability" in it })
        // A snapshot is still produced and can be archived.
        assertEquals("waiver", OperatingProfiles.snapshot(waiver, state(waiver), "", "", "", true).getJSONObject("profile").getString("id"))
    }
    @Test fun changesPreserveInitialSnapshotAndMultipleTransitions() {
        val original = JSONObject().put("operatingProfile", JSONObject().put("profile", OperatingProfiles.standard())).toString()
        val next = JSONObject().put("operatingProfile", JSONObject().put("profile", OperatingProfiles.other())).toString()
        val second = OperatingProfiles.mergeHistory(original, next)
        val third = JSONObject(OperatingProfiles.mergeHistory(second, original))
        assertEquals("standard-part-107", third.getJSONObject("operatingProfile").getJSONObject("profile").getString("id"))
        assertEquals(2, third.getJSONArray("operatingProfileChanges").length())
        assertEquals("other-pending", third.getJSONArray("operatingProfileChanges").getJSONObject(1).getJSONObject("before").getJSONObject("profile").getString("id"))
        assertEquals(2, JSONObject(OperatingProfiles.mergeHistory(third.toString(), original)).getJSONArray("operatingProfileChanges").length())
    }
    @Test fun laterBriefingAcknowledgmentAndLegacyUpdateRetainHistory() {
        val selected = JSONObject().put("profile", OperatingProfiles.standard()).put("checkedConditions", JSONArray())
        val original = JSONObject().put("operatingProfile", selected).toString()
        val acknowledged = JSONObject().put("operatingProfile", JSONObject(selected.toString()).put("checkedConditions", JSONArray().put(0))).toString()
        val updated = OperatingProfiles.mergeHistory(original, acknowledged)
        assertEquals(1, JSONObject(updated).getJSONArray("operatingProfileChanges").length())
        val legacyUpdate = JSONObject(OperatingProfiles.mergeHistory(updated, "{}"))
        assertEquals(1, legacyUpdate.getJSONArray("operatingProfileChanges").length())
        assertEquals(0, legacyUpdate.getJSONObject("operatingProfile").getJSONArray("checkedConditions").length())
    }
}

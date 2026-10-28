package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CaltopoPersonalCatalogTest {
    private val fixture get() = javaClass.getResourceAsStream("/caltopo-personal-account-api.json")!!.bufferedReader().use { it.readText() }
    private fun normalized(body: String = fixture) = CaltopoPersonalCatalog.normalize(body, "USER01")
    private fun membership(permission: Int, groupsOnly: Boolean = false, foreign: Boolean = false): String {
        val root = JSONObject(fixture); val result = root.getJSONObject("result")
        for (key in if (groupsOnly) listOf("groups") else listOf("groups", "rels")) {
            val props = result.getJSONArray(key).getJSONObject(0).getJSONObject("properties")
            props.put("type", permission)
            if (foreign) props.put("accountId", "OTHER1")
        }
        return root.toString()
    }
    @Test fun preservesFoldersBookmarksAndWorkspaceMembership() {
        val json = normalized(); assertEquals("Test Pilot", json.getString("username"))
        val roots = parseMapHierarchy(json)
        val personal = roots.filterIsInstance<CaltopoNode.Directory>().single { it.id == "USER01" }
        val folder = personal.children.filterIsInstance<CaltopoNode.Directory>().single { it.title == "Searches" }
        assertEquals(setOf("ABC123", "DEF456"), folder.children.map { it.id }.toSet())
        assertEquals(456L, folder.children.filterIsInstance<CaltopoNode.MapNode>().single { it.id == "DEF456" }.updated)
        val workspace = roots.filterIsInstance<CaltopoNode.Directory>().single { it.title == "Incident Workspace" }
        assertEquals("GHI789", (workspace.children.single() as CaltopoNode.Directory).children.single().id)
        assertEquals(2, roots.size)
    }
    @Test fun photoOwnershipUsesWritableWorkspaceOtherwisePersonal() {
        var owners = normalized().getJSONObject("mediaOwners")
        assertEquals("USER01", owners.getString("ABC123")); assertEquals("USER01", owners.getString("DEF456"))
        assertEquals("WORK01", owners.getString("GHI789"))
        owners = normalized(membership(10)).getJSONObject("mediaOwners")
        assertEquals("USER01", owners.getString("GHI789"))
    }
    @Test fun conflictingMembershipCannotEscalatePhotoOwnership() {
        assertEquals("USER01", normalized(membership(10, groupsOnly = true)).getJSONObject("mediaOwners").getString("GHI789"))
    }
    @Test fun foreignMembershipDoesNotGrantWorkspaceAccess() {
        assertEquals(1, normalized(membership(16, foreign = true)).getJSONArray("accounts").length())
    }
    @Test fun rejectsChangedOrMalformedAccountIdentity() {
        for (id in listOf("ABSENT", "../USER01", "", "USER010")) {
            assertTrue(runCatching { CaltopoPersonalCatalog.normalize(fixture, id) }.isFailure)
        }
    }
    @Test fun rejectsErrorsLegacyCatalogsAndMalformedResponses() {
        for (body in listOf("{\"account\":{}}", "{\"status\":\"error\",\"result\":{\"accounts\":[],\"features\":[]}}",
            "{\"status\":\"ok\",\"error\":\"denied\",\"result\":{}}", "{\"status\":\"ok\",\"result\":{\"account\":{\"id\":\"USER01\"}}}", "<html>Sign in</html>")) {
            assertTrue(runCatching { normalized(body) }.isFailure)
        }
    }
    @Test fun emptyCatalogRetainsIdentityAndDisplayFallback() {
        val body = """{"status":"ok","result":{"accounts":[{"id":"USER01","properties":{"class":"UserAccount"}}],"features":[]}}"""
        val json = normalized(body)
        assertEquals("USER01", json.getString("username")); assertTrue(parseMapHierarchy(json).isEmpty())
    }
}

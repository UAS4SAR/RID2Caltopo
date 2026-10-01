package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class CaltopoPersonalCatalogTest {
    private val fixture = """{"status":"ok","result":{"account":{"id":"USER01","folders":[{"id":"FOLDER1","properties":{"class":"UserFolder","label":"Searches"}}],"tenants":[{"id":"ABC123","properties":{"class":"CollaborativeMap","title":"Private test","folderId":"FOLDER1","updated":123}}],"bookmarks":[{"id":"REL001","properties":{"class":"UserAccountMapRel","mapId":"DEF456","title":"Shared map","mapUpdated":456}}],"groupAccounts":[{"id":"WORK01","alias":"Incident Workspace","isWorkspace":true,"tenants":[{"id":"GHI789","properties":{"class":"CollaborativeMap","title":"Incident"}}]},{"id":"OTHER1","alias":"Other","isWorkspace":false,"tenants":[{"id":"JKL123","properties":{"class":"CollaborativeMap","title":"Excluded"}}]}]}}}"""
    @Test fun photoOwnershipUsesWritableWorkspaceOtherwisePersonalAccount() {
        val parsed = org.json.JSONObject(fixture)
        val workspace = parsed.getJSONObject("result").getJSONObject("account").getJSONArray("groupAccounts").getJSONObject(0)
        workspace.put("type", 16)
        var owners = CaltopoPersonalCatalog.normalize(parsed.toString(), "USER01", "pilot").getJSONObject("mediaOwners")
        assertEquals("USER01", owners.getString("ABC123"))
        assertEquals("WORK01", owners.getString("GHI789"))
        workspace.put("type", 10)
        owners = CaltopoPersonalCatalog.normalize(parsed.toString(), "USER01", "pilot").getJSONObject("mediaOwners")
        assertEquals("USER01", owners.getString("GHI789"))
    }
    @Test fun preservesFoldersSharedMapsAndWorkspaceMembership() {
        val roots = parseMapHierarchy(CaltopoPersonalCatalog.normalize(fixture, "USER01", "pilot"))
        val personal = roots.filterIsInstance<CaltopoNode.Directory>().single { it.title == "pilot" }
        val folder = personal.children.filterIsInstance<CaltopoNode.Directory>().single { it.title == "Searches" }
        assertEquals("ABC123", folder.children.single().id)
        val shared = personal.children.filterIsInstance<CaltopoNode.MapNode>().single()
        assertEquals("DEF456", shared.id)
        assertEquals(456L, shared.updated)
        val workspace = roots.filterIsInstance<CaltopoNode.Directory>().single { it.title == "Incident Workspace" }
        assertEquals("GHI789", workspace.children.single().id)
        assertEquals(2, roots.size)
    }
    @Test fun rejectsChangedOrMalformedAccountIdentity() {
        for ((id, name) in listOf("OTHER1" to "pilot", "../USER01" to "pilot", "USER01" to "")) {
            assertTrue(runCatching { CaltopoPersonalCatalog.normalize(fixture, id, name) }.isFailure)
        }
    }
    @Test fun rejectsServerErrorsAndUnwrappedOrMalformedResponses() {
        for (body in listOf("{\"account\":{}}", "{\"status\":\"error\",\"result\":{\"account\":{}}}",
            "{\"status\":\"ok\",\"error\":\"denied\",\"result\":{\"account\":{}}}", "<html>Sign in</html>")) {
            assertTrue(runCatching { CaltopoPersonalCatalog.account(body) }.isFailure)
        }
        assertEquals("USER01", CaltopoPersonalCatalog.account(fixture).getString("id"))
    }

}

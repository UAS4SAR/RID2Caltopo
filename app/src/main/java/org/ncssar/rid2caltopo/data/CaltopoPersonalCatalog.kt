package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject

/** Adapts the documented account API to the personal picker without changing browser storage. */
object CaltopoPersonalCatalog {
    const val IDENTITY_SCRIPT = """(function(){if(location.origin!=='https://caltopo.com')return null;var s=window.sarsoft;var id=s&&(s.account_id||(s.account&&s.account.id));return id?JSON.stringify({id:String(id)}):null;})()"""
    fun normalize(body: String, expectedID: String): JSONObject {
        CaltopoPersonalProbe.accountEndpoint(expectedID)
        val envelope = JSONObject(body)
        require(envelope.optString("status") == "ok" && !envelope.has("error")) { "CalTopo rejected the account request." }
        val result = envelope.getJSONObject("result")
        val sourceAccounts = result.getJSONArray("accounts").objects()
        val sourceFeatures = result.getJSONArray("features").objects()
        val personal = sourceAccounts.firstOrNull { it.optString("id") == expectedID }
            ?: error("CalTopo account changed. Sign in again.")
        val personalProperties = personal.getJSONObject("properties")
        require(personalProperties.optString("class") == "UserAccount")
        val fullName = listOfNotNull(personalProperties.text("firstName"), personalProperties.text("lastName")).joinToString(" ")
        val username = fullName.takeIf { it.isNotBlank() } ?: personalProperties.text("title")
            ?: personalProperties.text("alias") ?: personalProperties.text("email") ?: expectedID
        val relations = result.optJSONArray("rels")?.objects().orEmpty()
        val groups = result.optJSONArray("groups")?.objects().orEmpty()
        val membership = mutableMapOf<String, Int>()
        for (relation in groups + relations) {
            val props = relation.optJSONObject("properties") ?: continue
            if (props.optString("class") != "UserAccountGroupRel" || props.optString("accountId") != expectedID) continue
            val id = props.optString("referenceAccountId")
            if (!id.matches(Regex("[A-Za-z0-9]{6}")) || props.opt("type") !is Number) continue
            val permission = props.getInt("type")
            membership[id] = minOf(membership[id] ?: permission, permission)
        }
        val visible = listOf(personal) + sourceAccounts.filter {
            it.optString("id") != expectedID && (membership[it.optString("id")] ?: 0) > 0 &&
                it.optJSONObject("properties")?.optString("class") == "UserAccount"
        }
        val mapsByID = sourceFeatures.filter {
            it.optJSONObject("properties")?.optString("class") == "CollaborativeMap" &&
                CaltopoPersonalProbe.mapID(it.optString("id")) != null
        }.associateBy { it.getString("id") }
        val accounts = JSONArray(); val features = JSONArray(); val mediaOwners = JSONObject()
        for (account in visible) {
            val id = account.getString("id")
            val props = account.getJSONObject("properties")
            accounts.put(JSONObject().put("id", id).put("properties", JSONObject().put("title",
                if (id == expectedID) username else props.text("alias") ?: props.text("title") ?: id)))
            for (feature in sourceFeatures) {
                val fp = feature.optJSONObject("properties") ?: continue
                if (fp.optString("accountId") != id) continue
                val featureID = feature.optString("id")
                if (fp.optString("class") == "UserFolder") features.put(feature)
                else if (fp.optString("class") == "CollaborativeMap" && CaltopoPersonalProbe.mapID(featureID) != null) {
                    features.put(feature)
                    mediaOwners.put(featureID, if ((membership[id] ?: 0) >= 16) id else expectedID)
                }
            }
            for (relation in relations) {
                val rel = relation.optJSONObject("properties") ?: continue
                val mapID = rel.optString("mapId")
                if (rel.optString("class") != "UserAccountMapRel" || rel.optString("accountId") != id ||
                    CaltopoPersonalProbe.mapID(mapID) == null) continue
                val original = mapsByID[mapID]?.optJSONObject("properties") ?: JSONObject()
                val owner = original.text("accountId") ?: expectedID
                mediaOwners.put(mapID, if ((membership[owner] ?: 0) >= 16) owner else expectedID)
                val map = JSONObject(original.toString()).put("class", "CollaborativeMap").put("accountId", id)
                    .put("folderId", rel.opt("folderId"))
                    .put("title", rel.text("title") ?: original.text("title") ?: "Untitled Map")
                    .put("updated", rel.opt("mapUpdated") ?: original.opt("updated") ?: 0)
                    .put("type", rel.opt("type"))
                features.put(JSONObject().put("id", mapID).put("type", "Feature").put("properties", map))
            }
        }
        return JSONObject().put("username", username).put("accountID", expectedID)
            .put("accounts", accounts).put("features", features).put("mediaOwners", mediaOwners)
    }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
}

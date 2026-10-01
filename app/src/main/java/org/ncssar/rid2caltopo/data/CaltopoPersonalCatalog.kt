package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject

/** Converts the website's read-only account catalog into the existing map hierarchy format. */
object CaltopoPersonalCatalog {
    const val IDENTITY_SCRIPT = """(function(){if(location.origin!=='https://caltopo.com')return null;var s=window.sarsoft;var id=s&&(s.account_id||(s.account&&s.account.id));return id?JSON.stringify({id:String(id)}):null;})()"""
    fun account(body: String): JSONObject {
        val envelope = JSONObject(body)
        require(envelope.optString("status") == "ok" && !envelope.has("error")) { "CalTopo rejected the account request." }
        return envelope.getJSONObject("result").getJSONObject("account")
    }
    fun normalize(body: String, expectedID: String, username: String): JSONObject {
        require(expectedID.matches(Regex("[A-Za-z0-9]+")) && username.isNotBlank())
        val account = account(body)
        require(account.getString("id") == expectedID) { "CalTopo account changed. Sign in again." }
        val accounts = JSONArray(); val features = JSONArray(); val mediaOwners = JSONObject()
        val all = mutableListOf(account)
        val groups = account.optJSONArray("groupAccounts") ?: JSONArray()
        for (i in 0 until groups.length()) groups.optJSONObject(i)?.let {
            if (it.optBoolean("isWorkspace")) all.add(it)
        }
        for (a in all) {
            val accountID = a.getString("id")
            accounts.put(JSONObject().put("id", accountID).put("properties", JSONObject().put("title",
                if (accountID == expectedID) username else a.optString("alias", accountID))))
            for (key in listOf("folders", "tenants", "bookmarks")) {
                val entries = a.optJSONArray(key) ?: JSONArray()
                for (i in 0 until entries.length()) {
                    val item = JSONObject(entries.getJSONObject(i).toString())
                    val props = item.getJSONObject("properties")
                    if (key == "bookmarks") {
                        val mapID = props.optString("mapId")
                        if (CaltopoPersonalProbe.mapID(mapID) == null) continue
                        item.put("id", mapID)
                        props.put("class", "CollaborativeMap")
                        props.put("updated", props.optLong("mapUpdated"))
                    }
                    if (key != "folders" && CaltopoPersonalProbe.mapID(item.optString("id")) == null) continue
                    if (key != "folders") {
                        val ownerID = props.optString("accountId", accountID)
                        var mediaOwner = expectedID
                        for (g in 0 until groups.length()) {
                            val group = groups.optJSONObject(g) ?: continue
                            if (group.optString("id") == ownerID && group.optInt("type") >= 16) mediaOwner = ownerID
                        }
                        mediaOwners.put(item.getString("id"), mediaOwner)
                    }
                    props.put("accountId", accountID)
                    features.put(item)
                }
            }
        }
        return JSONObject().put("username", username).put("accountID", expectedID)
            .put("accounts", accounts).put("features", features).put("mediaOwners", mediaOwners)
    }
}

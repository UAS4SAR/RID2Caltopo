package org.ncssar.rid2caltopo.data
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.json.JSONObject;
import org.json.JSONArray;

private val tag = "CaltopoMapHierarchy"

sealed class CaltopoNode {
    abstract val id: String
    abstract val title: String

    data class Directory(
        override val id: String,
        override val title: String,
        val children: MutableList<CaltopoNode> = mutableListOf()
    ) : CaltopoNode()

    data class MapNode @JvmOverloads constructor(
        override val id: String,
        override val title: String,
        val updated: Long,
        val personalSessionID: String? = null
    ) : CaltopoNode()
}

fun parseMapHierarchy(jsonResponse: JSONObject): List<CaltopoNode> {
    val features = jsonResponse.optJSONArray("features") ?: JSONArray()
    val accounts = jsonResponse.optJSONArray("accounts") ?: JSONArray()
    val groups = jsonResponse.optJSONArray("groups") ?: JSONArray()
    CTDebug(tag, "parseMapHierarchy(): Found ${features.length()} features, ${accounts.length()} accounts, and ${groups.length()} groups.")

    val nodeMap = mutableMapOf<String, CaltopoNode>()
    val parentMap = mutableMapOf<String, String>() // ChildID -> ParentID
    val relFolderParentMap = mutableMapOf<String, String>() // MapID -> folder from bookmark relation
    val relAccountParentMap = mutableMapOf<String, String>() // MapID -> account from bookmark relation
    val allMapsFlat = mutableListOf<CaltopoNode.MapNode>()

    fun JSONObject.cleanId(key: String): String {
        val raw = optString(key, "")
        return if (raw == "null" || raw.isEmpty()) "" else raw
    }

    // 1. Create Root Directories from Accounts
    for (i in 0 until accounts.length()) {
        val acct = accounts.getJSONObject(i)
        val id = acct.getString("id")
        val props = acct.optJSONObject("properties") ?: JSONObject()

        // Try every possible name field for a Team/Account
        val title = props.optString("title",
            props.optString("alias",
                props.optString("lastName", "Private Account")))

        nodeMap[id] = CaltopoNode.Directory(id, title)
    }

    val rels = jsonResponse.optJSONArray("rels") ?: JSONArray()
    for (i in 0 until rels.length()) {
        val rel = rels.optJSONObject(i) ?: continue
        val props = rel.optJSONObject("properties") ?: continue
        if (props.optString("class") != "UserAccountMapRel") continue

        val mapId = props.cleanId("mapId")
        if (mapId.isEmpty()) continue

        val folderId = props.cleanId("folderId")
        val accountId = props.cleanId("accountId")
        if (folderId.isNotEmpty()) relFolderParentMap[mapId] = folderId
        if (accountId.isNotEmpty()) relAccountParentMap[mapId] = accountId
    }

    // 2. Second Pass: Extract Folders and Maps
    for (i in 0 until features.length()) {
        val feat = features.getJSONObject(i)
        val id = feat.getString("id")
        val props = feat.optJSONObject("properties") ?: continue
        val className = props.optString("class")

        val folderId = props.cleanId("folderId")
        val accountId = props.cleanId("accountId")
        val relFolderId = relFolderParentMap[id].orEmpty()
        val relAccountId = relAccountParentMap[id].orEmpty()

        // Priority: explicit map folder, relation folder for folderless bookmarks, then account fallback.
        val pid = when {
            folderId.isNotEmpty() -> folderId
            relFolderId.isNotEmpty() -> relFolderId
            accountId.isNotEmpty() -> accountId
            else -> relAccountId
        }
        if (pid.isNotEmpty()) parentMap[id] = pid

        when (className) {
            "UserFolder" -> {
                // Aggressively hunt for a name
                val name = props.optString("label",
                    props.optString("name",
                        props.optString("title",
                            "Unnamed Folder [ID: $id]"))) // ID helps us debug if it still fails
                nodeMap[id] = CaltopoNode.Directory(id, name)
            }
            "CollaborativeMap" -> {
                val updated = props.optLong("updated", feat.optLong("Updated", 0L))
                val title = props.optString("title", "Untitled Map")
                val mapNode = CaltopoNode.MapNode(
                    id = id,
                    title = title,
                    updated = updated
                )
                nodeMap[id] = mapNode
                allMapsFlat.add(mapNode)
            }
        }
    }

    // 3. Third Pass: Assemble the Tree
    val rootNodes = mutableListOf<CaltopoNode>()

    nodeMap.forEach { (id, node) ->
        val pid = parentMap[id]
        val parent = if (pid != null) nodeMap[pid] else null

        if (parent is CaltopoNode.Directory) {
            parent.children.add(node)
        } else {
            rootNodes.add(node)
        }
    }
// NEW: Sort every directory's children
    nodeMap.values.filterIsInstance<CaltopoNode.Directory>().forEach { dir ->
        dir.children.sortWith(compareByDescending<CaltopoNode> {
            // 1. Folders first (we treat them as "Long.MAX_VALUE" for descending sort if we want them at top)
            it is CaltopoNode.Directory
        }.thenByDescending {
            // 2. Most recent update next
            if (it is CaltopoNode.MapNode) it.updated else 0L
        }.thenBy {
            // 3. Finally alphabetical
            it.title.lowercase()
        })
    }
    /***
    nodeMap.values.filterIsInstance<CaltopoNode.Directory>().forEach { dir ->
        dir.children.sortWith(compareBy(
            { it !is CaltopoNode.Directory }, // Folders (false) come before Maps (true)
            { it.title.lowercase() }           // Then alphabetical
        ))
    }
     ***/
    // 4. Final Assembly
    val finalResult = mutableListOf<CaltopoNode>()

    // A. Add Recents (Same as before)
    val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
    val recentMaps = allMapsFlat.filter { it.updated > sevenDaysAgo }.sortedByDescending { it.updated }
    if (recentMaps.isNotEmpty()) {
        finalResult.add(CaltopoNode.Directory("virtual_recents", "⭐ Recent Activity", recentMaps.toMutableList()))
    }

    // B. Add Everything Else that belongs at the root
    // We sort them so Directories (Folders/Accounts) appear before loose Maps
    val sortedRoots = rootNodes.filter { root ->
        // Only show directories that aren't empty, OR loose maps
        (root is CaltopoNode.Directory && root.children.isNotEmpty()) || (root is CaltopoNode.MapNode)
    }.sortedWith(compareBy({ it is CaltopoNode.MapNode }, { it.title }))
    finalResult.addAll(sortedRoots)

    return finalResult
}

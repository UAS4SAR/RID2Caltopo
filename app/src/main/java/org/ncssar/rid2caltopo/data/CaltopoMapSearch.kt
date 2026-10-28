package org.ncssar.rid2caltopo.data

/** Compose keys compare catalog identity without walking every directory/map on Main. */
internal class CaltopoCatalogIdentity(val nodes: List<CaltopoNode>) {
    override fun equals(other: Any?) = other is CaltopoCatalogIdentity && nodes === other.nodes
    override fun hashCode() = System.identityHashCode(nodes)
}

internal class CaltopoSearchResult(val root: List<CaltopoNode>, val level: List<CaltopoNode>,
    val query: String, val rows: List<CaltopoNode>)

/** Caller runs on Default/IO. Iterative traversal also handles deeply nested folders. */
internal fun searchCaltopoMaps(root: List<CaltopoNode>, current: List<CaltopoNode>, query: String,
    checkActive: () -> Unit = {}): List<CaltopoNode> {
    if (query.isEmpty()) return current
    val pending = java.util.ArrayDeque<CaltopoNode>()
    root.asReversed().forEach(pending::addLast)
    val seen = HashSet<String>()
    val result = ArrayList<CaltopoNode>()
    var count = 0
    while (pending.isNotEmpty()) {
        if (count++ % 64 == 0) checkActive()
        when (val node = pending.removeLast()) {
            is CaltopoNode.Directory -> node.children.asReversed().forEach(pending::addLast)
            is CaltopoNode.MapNode -> if (seen.add(node.id) && node.title.contains(query, ignoreCase = true)) result.add(node)
        }
    }
    checkActive()
    return result
}

package org.ncssar.rid2caltopo.ui

/** Video identifies a configured aircraft only on one exact normalized designator match. */
internal fun uniqueStreamConfirmationRemoteId(designator: String, mappings: List<Pair<String,String>>): String? {
    val key=designator.trim()
    if(key.isEmpty()) return null
    return mappings.filter { it.second.trim().equals(key,ignoreCase=true) }.map { it.first }.distinct().singleOrNull()
}

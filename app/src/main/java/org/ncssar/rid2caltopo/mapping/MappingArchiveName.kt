package org.ncssar.rid2caltopo.mapping

internal object MappingArchiveName {
    fun geoTiffName(assignment: String): String = fileName(assignment).removeSuffix(".zip") + ".tif"

    fun fileName(assignment: String): String {
        val safe = assignment.map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }
            .joinToString("").trim().trim('.').take(60).ifBlank { "Map" }
        return "${safe}_2D_map.zip"
    }
}

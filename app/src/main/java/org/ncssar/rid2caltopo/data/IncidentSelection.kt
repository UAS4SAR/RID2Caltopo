package org.ncssar.rid2caltopo.data

object IncidentSelection {
    @JvmStatic fun name(mapSelected: Boolean, mapTitle: String?, standaloneName: String?): String =
        (if (mapSelected) mapTitle?.trim()?.takeIf { it.isNotEmpty() } else null)
            ?: standaloneName?.trim()?.takeIf { it.isNotEmpty() } ?: "Training"
}

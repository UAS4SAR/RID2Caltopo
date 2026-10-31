package org.ncssar.rid2caltopo.data

object IncidentSelection {
    @JvmStatic fun name(mapSelected: Boolean, mapTitle: String?, standaloneName: String?): String =
        (if (mapSelected) mapTitle?.trim()?.takeIf { it.isNotEmpty() } else null)
            ?: standaloneName?.trim()?.takeIf { it.isNotEmpty() } ?: "Training"

    /** Drops a stored "account: title" prefix so the field shows the catalog title. */
    @JvmStatic fun displayMapTitle(title: String?, personalName: String?): String {
        val trimmed = title?.trim().orEmpty()
        val name = personalName?.trim().orEmpty()
        val prefix = "$name: "
        if (name.isNotEmpty() && trimmed.startsWith(prefix)) {
            return trimmed.removePrefix(prefix).trim().ifEmpty { trimmed }
        }
        return trimmed
    }

    @JvmStatic fun mapOptionsText(personal: Boolean, accountName: String, mapTitle: String): String {
        val who = accountName.trim().ifEmpty { "account" }
        val credentials = if (personal) "Credentials: Personal: $who" else "Credentials: Team: $who"
        val map = mapTitle.trim().ifEmpty { "Map name unavailable" }
        return "$credentials\nMap: $map"
    }
}

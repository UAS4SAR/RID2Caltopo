package org.ncssar.rid2caltopo.video.mapcache

/**
 * How long a cached map tile may be kept.
 *
 * Public OSM tiles and Esri ArcGIS Online imagery (ArcGIS-WorldImagery and the
 * same World Imagery service) may be held only as a short performance cache.
 * The OSM tile usage policy forbids treating the public tile servers as a bulk
 * or long-term store, and Esri's ArcGIS Online basemap terms likewise prohibit
 * a durable offline copy of World Imagery. One window covers both.
 *
 * DEM tiles, including usgs-geotiff 1 m S1M terrain, and AOL / lidar products
 * have no retention limit. USGS Topo and USFS Topo are public-domain or
 * freely distributable and are also unrestricted. Anything that cannot be
 * positively classified is treated as unrestricted so a migration never
 * deletes data whose source is unknown.
 */
internal object TileSourceRetention {
    const val RESTRICTED_RETENTION_DAYS = 3L
    const val RESTRICTED_RETENTION_MS = RESTRICTED_RETENTION_DAYS * 24L * 60L * 60L * 1000L

    private val restricted = setOf(
        "osm",
        "osmstandard",
        "mapnik",
        "openstreetmap",
        "arcgisworldimagery",
        "imagery",
        "esri",
        "worldimagery"
    )
    private val standard = setOf("usgscontours")

    fun classifyLocation(location: String): TileRetentionClass {
        if (isProtectedTerrainOrLidar(location)) return TileRetentionClass.UNRESTRICTED
        val token = sourceToken(location) ?: return TileRetentionClass.UNRESTRICTED
        return classifySource(token)
    }

    fun classifySource(source: String): TileRetentionClass {
        val key = normalize(source)
        return when {
            key in restricted -> TileRetentionClass.RESTRICTED
            key in standard -> TileRetentionClass.STANDARD
            else -> TileRetentionClass.UNRESTRICTED
        }
    }

    fun decision(
        location: String?,
        createdAtMs: Long,
        expiresAtMs: Long,
        nowMs: Long,
        standardMaxAgeMs: Long
    ): TileRetentionDecision {
        val kind = location?.let(::classifyLocation) ?: TileRetentionClass.UNRESTRICTED
        return when (kind) {
            TileRetentionClass.UNRESTRICTED ->
                if (expiresAtMs == Long.MAX_VALUE) TileRetentionDecision.Keep
                else TileRetentionDecision.RewriteExpiry(Long.MAX_VALUE)
            TileRetentionClass.RESTRICTED -> restrictedDecision(createdAtMs, expiresAtMs, nowMs)
            TileRetentionClass.STANDARD ->
                if (createdAtMs > 0L && standardMaxAgeMs >= 0L && nowMs >= createdAtMs + standardMaxAgeMs) {
                    TileRetentionDecision.Delete
                } else {
                    TileRetentionDecision.Keep
                }
        }
    }

    fun shouldPurge(
        location: String?,
        createdAtMs: Long,
        nowMs: Long,
        standardMaxAgeMs: Long
    ): Boolean = decision(location, createdAtMs, Long.MAX_VALUE, nowMs, standardMaxAgeMs) is TileRetentionDecision.Delete

    /** Expiry to store for a tile written now. Unrestricted tiles do not expire. */
    fun expiresAtMs(source: String, nowMs: Long, standardMaxAgeMs: Long): Long = when (classifySource(source)) {
        TileRetentionClass.RESTRICTED -> saturatingAdd(nowMs, RESTRICTED_RETENTION_MS)
        TileRetentionClass.UNRESTRICTED -> Long.MAX_VALUE
        TileRetentionClass.STANDARD -> saturatingAdd(nowMs, standardMaxAgeMs.coerceAtLeast(0L))
    }

    private fun restrictedDecision(createdAtMs: Long, expiresAtMs: Long, nowMs: Long): TileRetentionDecision {
        if (createdAtMs <= 0L) {
            val cap = saturatingAdd(nowMs, RESTRICTED_RETENTION_MS)
            return if (expiresAtMs > cap) TileRetentionDecision.RewriteExpiry(cap) else TileRetentionDecision.Keep
        }
        val cap = saturatingAdd(createdAtMs, RESTRICTED_RETENTION_MS)
        if (nowMs >= cap) return TileRetentionDecision.Delete
        return if (expiresAtMs > cap) TileRetentionDecision.RewriteExpiry(cap) else TileRetentionDecision.Keep
    }

    private fun isProtectedTerrainOrLidar(location: String): Boolean {
        val parts = location.replace('\\', '/').split('/')
        return parts.any { part ->
            val lower = part.lowercase()
            lower == "dem" ||
                lower == "surface_v1" ||
                lower.endsWith(".tif") ||
                lower.endsWith(".tiff") ||
                lower.endsWith(".aol") ||
                lower.contains("s1m") ||
                lower.contains("geotiff") ||
                lower.contains("lidar")
        }
    }

    internal fun sourceToken(location: String): String? {
        val key = CACHE_KEY.matchEntire(location.trim())
        if (key != null) return key.groupValues[1]
        val normalized = location.replace('\\', '/')
        val marker = "/MapTiles/"
        val markerAt = normalized.indexOf(marker)
        val relative = if (markerAt >= 0) normalized.substring(markerAt + marker.length) else normalized
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.size >= 4 && parts[1].all(Char::isDigit) && parts[2].all(Char::isDigit)) {
            return parts[0]
        }
        return null
    }

    private fun normalize(source: String): String =
        source.lowercase().filter { it.isLetterOrDigit() }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right < 0L || left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private val CACHE_KEY = Regex("""v\d+\|([^|]+)\|\d+\|\d+\|\d+""")
}

internal enum class TileRetentionClass {
    RESTRICTED,
    UNRESTRICTED,
    STANDARD
}

internal sealed class TileRetentionDecision {
    data object Keep : TileRetentionDecision()
    data object Delete : TileRetentionDecision()
    data class RewriteExpiry(val expiresAtMs: Long) : TileRetentionDecision()
}

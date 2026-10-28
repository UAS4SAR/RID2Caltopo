package org.ncssar.rid2caltopo.storage

internal enum class StorageCategory(val label: String) {
    IMAGERY("Map imagery"), TERRAIN("Terrain files"), DECODED("Decoded terrain"), AOL("AOL obstacle tiles"),
    ICONS("Icons and elevation samples"), PROJECTS("Local Maps projects and originals"), LAYERS("Map layers and models"),
    CHECKPOINTS("Saved processing checkpoints"), LOGS("Diagnostic logs"), TRACKS("Track logs"), CLUES("Clue pictures"),
    RECORDINGS("Captured video recordings"), ANNOTATIONS("Video review annotations"), SUPPORT("Downloaded support data"),
    METADATA("Indexes and operational metadata"), RECOVERY("Recovery files"), OTHER("Other retained archive files"), TEMPORARY("Temporary files")
}

/** No file contents are parsed to determine a storage category. */
internal fun storageCategory(path: String): StorageCategory {
    val lower = path.lowercase()
    val name = lower.substringAfterLast('/')
    return when {
        lower.contains("storage-recovery/") || lower.contains("storage-deletions/") || name.contains(".r2c-stage-") || name.contains(".r2c-previous-") || name.contains(".r2c-interrupted-") -> StorageCategory.RECOVERY
        name.endsWith(".r2c-info") || name == ".r2c-current" || lower.contains("mapping-storage-stats/") || lower.contains("queue-state/") -> StorageCategory.METADATA
        lower.contains("mapping-missions/") -> StorageCategory.PROJECTS
        lower.contains("mapping-layers/") -> StorageCategory.LAYERS
        lower.contains("mapping-processing/") -> StorageCategory.CHECKPOINTS
        lower.contains("unfinished-recordings/") || lower.contains("recording-recovery/") || lower.contains("recording-originals/") -> StorageCategory.RECORDINGS
        lower.startsWith("cache/surface_v1/") -> StorageCategory.AOL
        lower.startsWith("cache/decoded-terrain/") -> StorageCategory.DECODED
        lower.startsWith("cache/tile_cache_") || lower.startsWith("cache/imported-osmdroid/") -> StorageCategory.IMAGERY
        lower.startsWith("cache/dem/") || (lower.startsWith("cache/") && name.substringAfterLast('.') in listOf("tif", "tiff", "cog")) -> StorageCategory.TERRAIN
        lower.startsWith("cache/icon_cache_") || lower.startsWith("cache/dem_point_") -> StorageCategory.ICONS
        lower.startsWith("cache/support-data/") -> StorageCategory.SUPPORT
        name.endsWith(".review.json") || lower.contains("review-annotations/") -> StorageCategory.ANNOTATIONS
        lower.contains("/clues/") && name.endsWith(".json") -> StorageCategory.METADATA
        name.substringAfterLast('.') in listOf("jpg", "jpeg", "png") && !lower.startsWith("cache/") -> StorageCategory.CLUES
        name.substringAfterLast('.') in listOf("mp4", "fmp4", "mov", "mkv", "ts") -> StorageCategory.RECORDINGS
        name.startsWith("log_") || lower.contains("diagnostic-traces/") || name.startsWith("anr_") -> StorageCategory.LOGS
        name.substringAfterLast('.') in listOf("kmz", "kml", "geojson", "gpx") || ((lower.startsWith("tracks-") || lower.startsWith("20")) && name.endsWith(".json")) -> StorageCategory.TRACKS
        lower.startsWith("cache/") || lower.startsWith("application-logs/operational/") -> StorageCategory.METADATA
        else -> StorageCategory.OTHER
    }
}

internal data class StorageCategoryReport(val bytes: Map<StorageCategory, Long>, val complete: Boolean) {
    val total: Long get() = bytes.values.sum()
}

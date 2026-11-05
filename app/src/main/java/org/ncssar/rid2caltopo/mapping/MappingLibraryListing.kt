package org.ncssar.rid2caltopo.mapping

import java.io.File
import java.nio.file.Files

/** Resolves mapping-missions / mapping-layers for library refresh without treating hydrate gaps as empty. */
internal object MappingLibraryListing {
    sealed class Result {
        data class Available(val dirs: List<File>) : Result()
        data class Unavailable(val reason: String) : Result()
    }

    const val REASON_HYDRATING = "hydrating"
    const val REASON_UNAVAILABLE = "missions-unavailable"
    const val STALE_STAGE = "Storage is being prepared · showing saved list"
    val HEAVY_AREAS = setOf("missions", "decoded-terrain", "recordings", "recording-originals")

    /** Injectable for tests. Null = use [OperationalStorage.areaBusy]. */
    @Volatile var areaBusyProbe: ((String) -> Boolean)? = null

    fun areaBusy(id: String): Boolean =
        areaBusyProbe?.invoke(id)
            ?: runCatching { org.ncssar.rid2caltopo.storage.OperationalStorage.areaBusy(id) }.getOrDefault(false)

    fun heavyBusy(): Boolean = HEAVY_AREAS.any { areaBusy(it) }

    fun listMissions(filesDir: File): Result = listArea(filesDir, "mapping-missions", "missions")
    fun listLayers(filesDir: File): Result = listArea(filesDir, "mapping-layers", "layers")

    private fun listArea(filesDir: File, relative: String, areaId: String): Result {
        if (areaBusy(areaId)) return Result.Unavailable(REASON_HYDRATING)
        val alias = File(filesDir, relative)
        if (Files.isSymbolicLink(alias.toPath())) {
            val target = runCatching { Files.readSymbolicLink(alias.toPath()) }.getOrNull()
            if (target == null || !Files.exists(target)) return Result.Unavailable(REASON_UNAVAILABLE)
        }
        val listed = alias.listFiles() ?: return Result.Unavailable(REASON_UNAVAILABLE)
        return Result.Available(listed.filter { it.isDirectory })
    }
}

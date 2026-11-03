package org.ncssar.rid2caltopo.mapping

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Guards mapping-missions alias swaps. Stamps-skipped missions are absent from
 * archive hydrate digests, so a staged copy can silently omit them — never
 * delete the working projection or swap the alias in that case.
 */
internal object MissionProjectionGuard {
    data class Verdict(val ok: Boolean, val missingMissionIds: List<String> = emptyList())

    fun hasOriginals(missionDir: File): Boolean {
        val originals = File(missionDir, "originals")
        if (!originals.isDirectory) return false
        return originals.list()?.any { it.endsWith(".jpg", ignoreCase = true) } == true
    }

    fun hasRetainedEvidence(missionDir: File): Boolean {
        if (File(missionDir, "processing-status.json").isFile) return true
        if (hasOriginals(missionDir)) return true
        val inventory = File(missionDir, "inventory")
        return inventory.isDirectory && inventory.list()?.any { it.endsWith(".json", ignoreCase = true) } == true
    }

    /** Every working mission that holds originals (or inventory/status) must exist in [staged] with the same originals. */
    fun assertStagedPreservesWorking(working: File, staged: File): Verdict {
        if (!working.isDirectory) return Verdict(ok = true)
        val missing = mutableListOf<String>()
        for (mission in working.listFiles().orEmpty().filter { it.isDirectory }) {
            if (!hasRetainedEvidence(mission)) continue
            val id = mission.name
            val stagedMission = File(staged, id)
            if (!stagedMission.isDirectory) {
                missing += id
                continue
            }
            val workingOriginals = File(mission, "originals")
            if (!workingOriginals.isDirectory) continue
            val stagedOriginals = File(stagedMission, "originals")
            for (name in workingOriginals.list().orEmpty().filter { it.endsWith(".jpg", ignoreCase = true) }) {
                if (!File(stagedOriginals, name).isFile) {
                    missing += id
                    break
                }
            }
        }
        return Verdict(ok = missing.isEmpty(), missingMissionIds = missing.distinct())
    }

    /**
     * Create [alias] -> [destination] without a missing window: write alias.tmp then
     * ATOMIC_MOVE over the previous alias (or plain rename if atomic is unavailable).
     */
    fun installAliasAtomically(alias: File, destination: File) {
        alias.parentFile!!.mkdirs()
        val tmp = File(alias.parentFile, "${alias.name}.tmp-${java.util.UUID.randomUUID()}")
        if (tmp.exists()) {
            if (Files.isSymbolicLink(tmp.toPath())) Files.delete(tmp.toPath()) else tmp.deleteRecursively()
        }
        Files.createSymbolicLink(tmp.toPath(), destination.toPath().toAbsolutePath())
        try {
            Files.move(
                tmp.toPath(), alias.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            if (Files.isSymbolicLink(alias.toPath())) Files.delete(alias.toPath())
            else if (alias.exists()) alias.deleteRecursively()
            Files.move(tmp.toPath(), alias.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** True while [alias] exists as a symlink or directory — used by tests to assert no gap. */
    fun aliasPresent(alias: File): Boolean =
        Files.isSymbolicLink(alias.toPath()) || alias.exists()
}

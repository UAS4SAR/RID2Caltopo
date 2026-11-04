package org.ncssar.rid2caltopo.storage

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exactly one compact Mapping storage migration outcome line per process start.
 * Uses CTDebug so the line reaches the session log at the default Debug level
 * (CTInfo is Ludicrous-only and was dropped on field devices).
 */
internal object MappingStorageMigrationLog {
    private val emitted = AtomicBoolean(false)

    /** Pure formatter for unit tests and both platforms' wording. */
    fun outcomeLine(
        outcome: AndroidMappingRepositoryMigration.Outcome,
        files: Int = 0,
        bytes: Long = 0,
        alreadyCommitted: Boolean = false,
        failureReason: String? = null,
    ): String {
        failureReason?.let { return "Mapping storage: $it" }
        return when (outcome) {
            AndroidMappingRepositoryMigration.Outcome.COMPLETED,
            AndroidMappingRepositoryMigration.Outcome.COMPLETED_WITH_LEFTOVERS,
            -> {
                val mb = (bytes + 512 * 1024) / (1024 * 1024)
                "Mapping storage: migrated $files items ($mb MB)"
            }
            AndroidMappingRepositoryMigration.Outcome.NOTHING_TO_DO ->
                if (alreadyCommitted) "Mapping storage: already migrated"
                else "Mapping storage: nothing to migrate"
            AndroidMappingRepositoryMigration.Outcome.DEFERRED_ACTIVE ->
                "Mapping storage: deferred (reconstruction active)"
            AndroidMappingRepositoryMigration.Outcome.DEFERRED_UNSTABLE ->
                "Mapping storage: deferred (sources unstable)"
        }
    }

    /**
     * Emit at most once per process. Prefer calling after [org.ncssar.rid2caltopo.data.CaltopoClient.InitArchiveDir]
     * so the session log file is attached; DeferredLog still buffers earlier lines.
     */
    fun emitOnce(line: String) {
        if (!emitted.compareAndSet(false, true)) return
        // Ensure the session log stream is open before writing the required outcome line.
        runCatching { org.ncssar.rid2caltopo.data.CaltopoClient.InitArchiveDir() }
        org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("OperationalStorage", line)
    }

    fun resetForTests() {
        emitted.set(false)
    }
}

package org.ncssar.rid2caltopo.storage

import java.util.concurrent.CopyOnWriteArrayList

/** Why the archive folder cannot be written. */
/** MISSING: the active archive folder is on a card that is not in the tablet. */
enum class ArchiveWriteProblem { READ_ONLY, IO_ERROR, NOT_WRITABLE, MISSING }

/** The only events allowed to clear a "not writable" verdict. */
enum class ArchiveRecheckTrigger { REMOUNT, FOLDER_PICKED, PERIODIC }

data class ArchiveWritabilityVerdict(
    val writable: Boolean,
    val problem: ArchiveWriteProblem? = null,
    val detail: String? = null,
    val sinceMs: Long = 0L,
    /** The archive is on a removable volume (an SD card), so the UI can say "SD card". */
    val removable: Boolean = false,
    /** Increments on every state change; the UI shows one alert per generation. */
    val generation: Int = 0,
)

/**
 * Sticky "the archive folder cannot be written" verdict.
 *
 * On 2026-11-18 the S11U SD card remounted read-only (EROFS). Each storage check failed,
 * the start-up estimate and `canWrite()` then reported the card as fine again, and the
 * Flight Storage pop-up flickered while recording switched on and off. Once a read-only or
 * I/O error is seen, this verdict stays "not writable" until a real recheck succeeds: a
 * remount, a new folder pick, or a periodic probe at most every [RECHECK_INTERVAL_MS].
 * Nothing else (estimates, `canWrite()`, scans) can clear it.
 */
class ArchiveWritabilityTracker(
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) {
    private val lock = Any()
    @Volatile private var current = ArchiveWritabilityVerdict(writable = true)
    private var lastRecheckMs: Long? = null
    private val listeners = CopyOnWriteArrayList<(ArchiveWritabilityVerdict, ArchiveWritabilityVerdict) -> Unit>()

    val verdict: ArchiveWritabilityVerdict get() = current
    val writable: Boolean get() = current.writable

    fun addListener(listener: (old: ArchiveWritabilityVerdict, new: ArchiveWritabilityVerdict) -> Unit) {
        listeners += listener
    }

    /**
     * Records a failed archive write or read. Returns true when [error] means the archive is
     * not writable (read-only or I/O error); the verdict then sticks. Other errors return
     * false and change nothing.
     */
    fun noteFailure(error: Throwable?, where: String, removable: Boolean = false): Boolean {
        val problem = classify(error) ?: return false
        notWritable(problem, describe(error), where, removable)
        return true
    }

    /** Marks the archive not writable for a reason found without an exception (e.g. a volume mounted read-only). */
    fun markNotWritable(problem: ArchiveWriteProblem, detail: String, where: String, removable: Boolean = false) {
        notWritable(problem, detail, where, removable)
    }

    private fun notWritable(problem: ArchiveWriteProblem, detail: String, where: String, removable: Boolean) {
        val change = synchronized(lock) {
            if (!current.writable) return
            val now = clock()
            // The periodic window starts at the failure, so the next probe is 5 minutes away.
            lastRecheckMs = now
            val old = current
            current = ArchiveWritabilityVerdict(false, problem, detail, now, removable, old.generation + 1)
            old to current
        }
        log("Archive not writable (${problem.name}) after $where: $detail. Recording and logs stay in tablet storage; next check on remount, folder pick or in ${RECHECK_INTERVAL_MS / 60_000} min.")
        notify(change)
    }

    /** True when [trigger] may run a probe now. Periodic probes only run while not writable. */
    fun recheckDue(trigger: ArchiveRecheckTrigger): Boolean = synchronized(lock) {
        when (trigger) {
            ArchiveRecheckTrigger.REMOUNT, ArchiveRecheckTrigger.FOLDER_PICKED -> true
            ArchiveRecheckTrigger.PERIODIC -> !current.writable &&
                (lastRecheckMs?.let { clock() - it >= RECHECK_INTERVAL_MS } ?: true)
        }
    }

    /**
     * Runs [probe] when [trigger] allows it. A probe that returns normally clears the verdict;
     * one that throws sets it. Returns the resulting writability.
     */
    fun recheck(trigger: ArchiveRecheckTrigger, removable: Boolean = false, probe: () -> Unit): Boolean {
        synchronized(lock) {
            if (!recheckDue(trigger)) return current.writable
            lastRecheckMs = clock()
        }
        val failure = try { probe(); null } catch (error: Throwable) { error }
        if (failure == null) {
            val change = synchronized(lock) {
                if (current.writable) null else {
                    val old = current
                    current = ArchiveWritabilityVerdict(true, removable = removable, sinceMs = clock(), generation = old.generation + 1)
                    old to current
                }
            }
            if (change != null) {
                log("Archive writable again after ${trigger.name} check.")
                notify(change)
            }
        } else {
            val problem = classify(failure) ?: ArchiveWriteProblem.NOT_WRITABLE
            if (writable) notWritable(problem, describe(failure), "${trigger.name} check", removable)
            else if (trigger != ArchiveRecheckTrigger.PERIODIC) log("Archive still not writable after ${trigger.name} check: ${describe(failure)}")
        }
        return writable
    }

    private fun notify(change: Pair<ArchiveWritabilityVerdict, ArchiveWritabilityVerdict>) {
        listeners.forEach { listener -> runCatching { listener(change.first, change.second) } }
    }

    companion object {
        const val RECHECK_INTERVAL_MS = 5 * 60_000L
        private val EIO = Regex("\\bEIO\\b")
        private const val MISSING_CARD = "is not in this tablet"

        fun missingCardMessage(volume: String): String = "Archive SD card $volume $MISSING_CARD"

        /** Read-only or I/O failure anywhere in the cause chain; null for anything else. */
        fun classify(error: Throwable?): ArchiveWriteProblem? {
            var current = error
            var depth = 0
            while (current != null && depth++ < 12) {
                val text = current.javaClass.name + " " + current.message.orEmpty()
                if (text.contains("EROFS") || text.contains("Read-only file system", ignoreCase = true)) {
                    return ArchiveWriteProblem.READ_ONLY
                }
                if (EIO.containsMatchIn(text) || text.contains("I/O error", ignoreCase = true)) {
                    return ArchiveWriteProblem.IO_ERROR
                }
                if (text.contains(MISSING_CARD)) return ArchiveWriteProblem.MISSING
                if (current.cause === current) break
                current = current.cause
            }
            return null
        }

        fun describe(error: Throwable?): String {
            var current = error
            var depth = 0
            while (current != null && depth++ < 12) {
                val message = current.message
                if (!message.isNullOrBlank() && classify(Throwable(message)) != null) return message
                current = current.cause
            }
            return error?.message ?: error?.javaClass?.simpleName ?: "unknown"
        }
    }
}

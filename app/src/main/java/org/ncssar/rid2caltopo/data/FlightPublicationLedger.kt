package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.ncssar.rid2caltopo.app.R2CApplication
import java.io.File

/**
 * Compact durable per-flight publication disposition.
 *
 * Awaiting-map rows are pruned after local/publish/confirm decisions, so recovery and
 * tracker upload cannot rely on that journal alone. This ledger survives those prunes and
 * gates every publish path: live CalTopo, archive, awaiting-map promotion, journal recovery,
 * and BgPollUnreportedTracks / r2c-tracker uploads.
 *
 * Publication gates consult in-memory state first. Disk writes always run on a worker so
 * UI Don't Publish / Discard never hit OperationalStorage on Main.
 */
object FlightPublicationLedger {
    enum class Disposition(val wire: String) {
        /** Operator chose Don't Publish / Ignore on the Drone Confirmation Panel. */
        DECLINED("declined"),
        /** Operator discarded an awaiting-map offer; never re-enter inbox or publish. */
        DISCARDED("discarded"),
        /** Flight ended with no DCP acknowledgement; never publish or await a map. */
        SUPPRESSED("suppressed"),
        /** Local archive written with local_archive_only (declined retention). */
        LOCAL_ARCHIVED("local_archived"),
        /** Confirmed Shape-archive / published; never re-enter awaiting-map. */
        PUBLISHED("published");

        companion object {
            fun fromWire(value: String): Disposition? =
                entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
        }
    }

    private var testFile: File? = null
    private var loaded = false
    private var entries = JSONObject()
    private val lock = Any()
    private val persist = java.util.concurrent.atomic.AtomicBoolean(false)

    @JvmStatic
    fun useFileForTesting(file: File?) {
        synchronized(lock) {
            testFile = file
            loaded = false
            entries = JSONObject()
            persist.set(false)
        }
    }

    /** App-private path only — never triggers Archive Dir prepare/retain on the caller. */
    private fun appPrivateFile(): File? = testFile
        ?: R2CApplication.getAppCtxt()?.let { context ->
            org.ncssar.rid2caltopo.storage.ArchiveDirAvailability.operationalStateFile(
                context.filesDir,
                "flight-publication-ledger.json",
            )
        }

    private fun loadLocked() {
        if (loaded) return
        val file = appPrivateFile() ?: return
        val disk = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        // Memory wins for keys already decided this session (Don't Publish before hydrate).
        val merged = JSONObject()
        for (key in disk.keys()) {
            merged.put(key, disk.get(key))
        }
        for (key in entries.keys()) {
            val memory = Disposition.fromWire(entries.optString(key)) ?: continue
            val prior = Disposition.fromWire(merged.optString(key))
            merged.put(key, merge(prior, memory).wire)
        }
        entries = merged
        loaded = true
    }

    private fun saveLocked() {
        val file = appPrivateFile() ?: return
        file.parentFile?.mkdirs()
        // App-private atomic write; Archive Dir copy is retainLater (never sync retain on caller).
        PlainFileSafeWriter.replaceLocal(file, entries.toString().toByteArray(Charsets.UTF_8)) { it.isNotEmpty() }
        org.ncssar.rid2caltopo.storage.OperationalStorage.retainLater(file)
    }

    private fun schedulePersist() {
        if (!persist.compareAndSet(false, true)) return
        CaltopoRecoveryWork.offMainArchive {
            try {
                while (true) {
                    persist.set(false)
                    synchronized(lock) {
                        loadLocked()
                        saveLocked()
                    }
                    if (!persist.get()) break
                }
            } catch (error: Exception) {
                persist.set(false)
                CaltopoClient.CTWarn(
                    "FlightPublicationLedger",
                    "persist failed: ${error.message}",
                    error,
                )
            }
        }
    }

    /**
     * Applies the disposition in memory immediately (publication gate sees it before return),
     * then persists on the archive worker.
     */
    @JvmStatic
    fun remember(flightId: String, disposition: Disposition) {
        val id = flightId.trim()
        if (id.isEmpty()) return
        synchronized(lock) {
            // Do not touch disk on the caller thread. Seed memory if cold so Don't Publish
            // blocks publication before the worker merges the file.
            if (!loaded && entries.length() == 0) {
                entries = JSONObject()
            }
            val prior = Disposition.fromWire(entries.optString(id))
            val next = merge(prior, disposition)
            if (prior == next && entries.has(id)) return
            entries.put(id, next.wire)
        }
        schedulePersist()
    }

    private fun merge(prior: Disposition?, next: Disposition): Disposition = when {
        prior == Disposition.PUBLISHED -> Disposition.PUBLISHED
        next == Disposition.PUBLISHED -> Disposition.PUBLISHED
        // Discard and Don't Publish are permanent operator refusals; never downgrade them.
        prior == Disposition.DISCARDED || next == Disposition.DISCARDED -> Disposition.DISCARDED
        prior == Disposition.DECLINED || next == Disposition.DECLINED -> Disposition.DECLINED
        prior == Disposition.LOCAL_ARCHIVED || next == Disposition.LOCAL_ARCHIVED -> Disposition.LOCAL_ARCHIVED
        else -> next
    }

    @JvmStatic
    fun disposition(flightId: String): Disposition? {
        val id = flightId.trim()
        if (id.isEmpty()) return null
        synchronized(lock) {
            // Prefer in-memory decisions without forcing a main-thread file hydrate.
            if (entries.has(id)) return Disposition.fromWire(entries.optString(id))
            if (loaded) return Disposition.fromWire(entries.optString(id))
        }
        val main = android.os.Looper.getMainLooper()
        if (main != null && android.os.Looper.myLooper() == main) {
            // Never read the ledger file on Main; schedule a worker hydrate.
            schedulePersist()
            return null
        }
        synchronized(lock) {
            runCatching { loadLocked() }
            return Disposition.fromWire(entries.optString(id))
        }
    }

    /** Any remembered disposition blocks automatic map/tracker publication. */
    @JvmStatic
    fun blocksPublication(flightId: String): Boolean = disposition(flightId) != null

    /** Declined, suppressed, local-archived, and published flights must not enter awaiting-map. */
    @JvmStatic
    fun blocksAwaitingMapPromotion(flightId: String): Boolean = blocksPublication(flightId)

    /** True when the operator declined/discarded or the flight never received DCP acknowledgement. */
    @JvmStatic
    fun isDeclinedOrSuppressed(flightId: String): Boolean {
        val d = disposition(flightId) ?: return false
        return d == Disposition.DECLINED || d == Disposition.DISCARDED ||
            d == Disposition.SUPPRESSED || d == Disposition.LOCAL_ARCHIVED
    }

    @JvmStatic
    @androidx.annotation.VisibleForTesting
    fun allForTesting(): Map<String, Disposition> {
        synchronized(lock) {
            loadLocked()
            val out = linkedMapOf<String, Disposition>()
            for (key in entries.keys()) {
                Disposition.fromWire(entries.optString(key))?.let { out[key] = it }
            }
            return out
        }
    }

    @JvmStatic
    @androidx.annotation.VisibleForTesting
    fun clearForTesting() {
        synchronized(lock) {
            entries = JSONObject()
            loaded = testFile != null
            if (testFile != null) saveLocked()
        }
    }

    /** Drain scheduled persist for tests that assert durable file contents. */
    @JvmStatic
    @androidx.annotation.VisibleForTesting
    fun flushForTesting() {
        val done = java.util.concurrent.CountDownLatch(1)
        CaltopoRecoveryWork.offMainArchive {
            try {
                synchronized(lock) {
                    loadLocked()
                    saveLocked()
                    persist.set(false)
                }
            } finally {
                done.countDown()
            }
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }
}

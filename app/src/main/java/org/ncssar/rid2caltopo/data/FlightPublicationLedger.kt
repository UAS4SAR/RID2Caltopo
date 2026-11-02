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
 */
object FlightPublicationLedger {
    enum class Disposition(val wire: String) {
        /** Operator chose Don't Publish / Ignore on the Drone Confirmation Panel. */
        DECLINED("declined"),
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

    @JvmStatic
    fun useFileForTesting(file: File?) {
        synchronized(lock) {
            testFile = file
            loaded = false
            entries = JSONObject()
        }
    }

    private fun file(): File? = testFile
        ?: R2CApplication.getAppCtxt()?.let {
            org.ncssar.rid2caltopo.storage.OperationalStorage.stateFile(it, "flight-publication-ledger.json")
        }

    private fun load() {
        if (loaded) return
        val file = file() ?: return
        entries = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        loaded = true
    }

    private fun save() {
        val file = file() ?: return
        file.parentFile?.mkdirs()
        PlainFileSafeWriter.replace(file, entries.toString().toByteArray(Charsets.UTF_8)) { it.isNotEmpty() }
        org.ncssar.rid2caltopo.storage.OperationalStorage.retainLater(file)
    }

    @JvmStatic
    fun remember(flightId: String, disposition: Disposition) {
        val id = flightId.trim()
        if (id.isEmpty()) return
        synchronized(lock) {
            load()
            val prior = Disposition.fromWire(entries.optString(id))
            // Published / declined win over weaker suppressions; never downgrade published.
            val next = merge(prior, disposition)
            if (prior == next && entries.has(id)) return
            entries.put(id, next.wire)
            save()
        }
    }

    private fun merge(prior: Disposition?, next: Disposition): Disposition = when {
        prior == Disposition.PUBLISHED -> Disposition.PUBLISHED
        next == Disposition.PUBLISHED -> Disposition.PUBLISHED
        prior == Disposition.DECLINED || next == Disposition.DECLINED -> Disposition.DECLINED
        prior == Disposition.LOCAL_ARCHIVED || next == Disposition.LOCAL_ARCHIVED -> Disposition.LOCAL_ARCHIVED
        else -> next
    }

    @JvmStatic
    fun disposition(flightId: String): Disposition? {
        val id = flightId.trim()
        if (id.isEmpty()) return null
        synchronized(lock) {
            load()
            return Disposition.fromWire(entries.optString(id))
        }
    }

    /** Any remembered disposition blocks automatic map/tracker publication. */
    @JvmStatic
    fun blocksPublication(flightId: String): Boolean = disposition(flightId) != null

    /** Declined, suppressed, local-archived, and published flights must not enter awaiting-map. */
    @JvmStatic
    fun blocksAwaitingMapPromotion(flightId: String): Boolean = blocksPublication(flightId)

    /** True when the operator declined or the flight never received DCP acknowledgement. */
    @JvmStatic
    fun isDeclinedOrSuppressed(flightId: String): Boolean {
        val d = disposition(flightId) ?: return false
        return d == Disposition.DECLINED || d == Disposition.SUPPRESSED || d == Disposition.LOCAL_ARCHIVED
    }

    @JvmStatic
    @androidx.annotation.VisibleForTesting
    fun allForTesting(): Map<String, Disposition> {
        synchronized(lock) {
            load()
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
            if (testFile != null) save()
        }
    }
}

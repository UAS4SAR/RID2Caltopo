package org.ncssar.rid2caltopo.video

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.app.isDatedArchiveDirectoryName
import org.ncssar.rid2caltopo.app.parseArchiveDirectoryDateMs
import org.ncssar.rid2caltopo.app.todayArchiveDirectoryName
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.PlainFileSafeWriter
import org.ncssar.rid2caltopo.data.RidFlightId
import java.io.File
import java.io.IOException
import java.util.Date

/**
 * v2 migration into per-flight clue folders `files/clues/<flightId>/`.
 *
 * Sources moved (every clue has a flight; none is dropped):
 *  - p75 day folders `clues/<tracks-ddMMMyyyy>/` (index + photos),
 *  - the original flat root `clues.json` + `<id>.jpg`,
 *  - the same layouts hydrated back from Archive Dir `application-logs/operational/clues`
 *    (the hydrated working alias is the migration root; managed removal also clears the
 *    Archive Dir copies).
 *
 * Flight id: `binding.flightId`; when absent (migration only), a unique Archive Dir flight-log
 * hint for the aircraft + day, else a stable synthetic id from aircraft + day.
 *
 * Crash-safe and idempotent: copy → verify → rewrite flight indexes → verify → only then remove
 * the old copies. Existing destination rows are never demoted (confirmed uploads stay confirmed).
 * Discard tombstones (`deleted-clue-ids.json`) stay at the root. Runs only off Main.
 */
object AndroidClueFlightFolderMigration {
    private const val TAG = "ClueFlightFolderMig"
    private const val PREFS = "android_clue_flight_folder_migration"
    private const val KEY_DONE = "completed_v2"
    internal const val FLAG_FILE = ".flight-folder-migration-v2-done"
    private const val LEGACY_FLAG_FILE = ".flight-folder-migration-v1-done"

    /** Archive flight-log hints: `CANONICALAIRCRAFT|tracks-ddMMMyyyy` → flight ids seen. */
    internal data class FlightHints(val flightsByAircraftDay: Map<String, List<String>> = emptyMap()) {
        fun unique(aircraft: String, day: String): String? =
            flightsByAircraftDay["${RidFlightId.canonicalAircraftId(aircraft)}|$day"]?.distinct()?.singleOrNull()
    }

    /**
     * Run the migration on a worker. [managed] is true on the Archive Dir clues-prepare path,
     * where removals also delete the Archive Dir copies; otherwise only the local working copy
     * is removed (a later hydrate brings leftovers back and the next managed run clears them).
     */
    @JvmStatic
    @JvmOverloads
    fun migrateIfNeeded(context: Context, root: File, managed: Boolean = false) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "AndroidClueFlightFolderMigration must run off Main"
        }
        val app = context.applicationContext
        // A completed marker still yields to leftovers (e.g. Archive Dir hydrate re-merged old layouts).
        if (isComplete(app, root) && !hasLegacyWork(root)) return
        try {
            val result = migrate(
                root = root,
                hints = { loadFlightHints(app) },
                removeOld = { file ->
                    if (managed) runCatching { org.ncssar.rid2caltopo.storage.OperationalStorage.remove(file) }
                    else runCatching { if (file.exists()) file.delete() }
                    !file.exists()
                },
                retainNew = { file ->
                    runCatching { org.ncssar.rid2caltopo.storage.OperationalStorage.retainLater(file) }
                },
            )
            markComplete(app, root)
            if (result.records > 0) {
                CaltopoClient.CTInfo(
                    TAG,
                    "Migrated clues into flight folders count=${result.records} flights=${result.flights} " +
                        "recovered=${result.recoveredFlightIds} managed=$managed",
                )
            }
        } catch (error: Exception) {
            CaltopoClient.CTWarn(TAG, "Flight-folder migration deferred; originals retained", error)
        }
    }

    /** Test/JVM entry point (no SharedPreferences). Returns records written into flight indexes. */
    internal fun migrateRoot(
        root: File,
        hints: () -> FlightHints = { FlightHints() },
        removeOld: (File) -> Boolean = { !it.exists() || it.delete() },
        retainNew: (File) -> Unit = {},
    ): Int = migrate(root, hints, removeOld, retainNew).records

    internal data class Result(val records: Int, val flights: Int, val recoveredFlightIds: Int)

    /** True while any pre-v2 layout (flat root or `tracks-*` day dirs) remains under [root]. */
    internal fun hasLegacyWork(root: File): Boolean {
        val entries = root.listFiles() ?: return false
        return entries.any { file ->
            when {
                file.isFile -> file.name == AndroidClueFlightFolders.INDEX_FILENAME ||
                    file.name.endsWith(".jpg", ignoreCase = true)
                file.isDirectory -> isDatedArchiveDirectoryName(file.name)
                else -> false
            }
        }
    }

    /** [stub] marks a record synthesized from an orphan JPEG; real index rows always outrank it. */
    private class Source(val record: AndroidClueRecord, val dir: File, val day: String?, val stub: Boolean = false)

    private fun migrate(
        root: File,
        hints: () -> FlightHints,
        removeOld: (File) -> Boolean,
        retainNew: (File) -> Unit,
    ): Result {
        if (!hasLegacyWork(root)) {
            if (root.isDirectory || root.mkdirs()) markFlagFile(root)
            return Result(0, 0, 0)
        }
        val sources = ArrayList<Source>()
        val oldIndexFiles = ArrayList<File>()
        val sourceDirs = listOf<Pair<File, String?>>(root to null) +
            AndroidClueFlightFolders.legacyDayFolders(root).sortedBy { it.name }.map { it to it.name }
        for ((dir, day) in sourceDirs) {
            val index = File(dir, AndroidClueFlightFolders.INDEX_FILENAME)
            val rows = if (index.isFile) {
                oldIndexFiles += index
                loadRecords(index)
            } else emptyList()
            rows.forEach { sources += Source(it, dir, day) }
            val indexedNames = rows.flatMap {
                listOf(baseName(it.imageFilename), baseName(it.thumbnailFilename))
            }.toSet()
            // Orphan JPEGs without an index row → pending stub (never dropped).
            for (photo in dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }) {
                val name = photo.name
                if (!name.endsWith(".jpg", ignoreCase = true) || name.endsWith("-thumb.jpg", ignoreCase = true)) continue
                if (name in indexedNames) continue
                val id = name.substring(0, name.length - 4)
                if (id.isBlank()) continue
                val modified = photo.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                sources += Source(
                    AndroidClueRecord(
                        id = id,
                        mapKey = "unassigned",
                        lat = 0.0,
                        lng = 0.0,
                        alt = Double.NaN,
                        title = "Recovered clue",
                        description = "",
                        createdAtMs = modified,
                        sourceDesignator = "",
                        imageFilename = name,
                        thumbnailFilename = "$id-thumb.jpg",
                        publishToCaltopo = true,
                        uploadState = "pending",
                        lastUploadError = "reindexed-orphan-photo",
                    ),
                    dir,
                    day,
                    stub = true,
                )
            }
        }

        // Resolve flight + dedupe same-id rows from day + root + hydrated copies.
        val hintsByUse by lazy(hints)
        var recovered = 0
        val byId = LinkedHashMap<String, Pair<AndroidClueRecord, Source>>()
        for (source in sources) {
            val (flightId, wasRecovered) = resolveFlight(source.record, source.day) { hintsByUse }
            val folder = AndroidClueFlightFolders.folderNameFor(flightId)
            val record = source.record.copy(
                imageFilename = "$folder/${baseName(source.record.imageFilename)}",
                thumbnailFilename = "$folder/${baseName(source.record.thumbnailFilename)}",
                binding = source.record.binding?.let {
                    if (it.flightId.isNullOrBlank()) it.copy(flightId = flightId) else it
                },
            )
            val prior = byId[record.id]
            if (prior == null) {
                byId[record.id] = record to source
                if (wasRecovered) recovered++
            } else {
                val winner = when {
                    prior.second.stub && !source.stub -> record
                    !prior.second.stub && source.stub -> prior.first
                    else -> preferred(prior.first, record, incomingWinsTies = true)
                }
                byId[record.id] = if (winner === record) record to source else prior
            }
        }

        // 1) Copy photos into flight folders; verify. Old copies stay until the indexes verify.
        val movable = ArrayList<File>()
        val newFiles = ArrayList<File>()
        for ((record, source) in byId.values) {
            copyPhoto(root, source, record.imageFilename, source.record.imageFilename, movable, newFiles)
            copyPhoto(root, source, record.thumbnailFilename, source.record.thumbnailFilename, movable, newFiles)
        }
        // Other sources for the same id (duplicate old copies) — also removable once verified.
        for (source in sources) {
            val winner = byId[source.record.id] ?: continue
            if (winner.second === source) continue
            for (name in listOf(source.record.imageFilename, source.record.thumbnailFilename)) {
                oldPhotoCandidates(root, source, name).firstOrNull { it.isFile }?.let { old ->
                    val dest = File(root, "${winner.first.imageFilename.substringBefore('/')}/${baseName(name)}")
                    if (dest.isFile && dest.length() > 0L && old.length() == dest.length()) movable += old
                }
            }
        }

        // 2) Rewrite flight indexes (merge with any existing destination rows), then verify.
        val stubIds = byId.values.filter { it.second.stub }.map { it.first.id }.toSet()
        val byFolder = byId.values.map { it.first }.groupBy { it.imageFilename.substringBefore('/') }
        for ((folder, incoming) in byFolder) {
            val dir = File(root, folder)
            dir.mkdirs()
            val index = File(dir, AndroidClueFlightFolders.INDEX_FILENAME)
            val merged = LinkedHashMap<String, AndroidClueRecord>()
            if (index.isFile) loadRecords(index).forEach { merged[it.id] = it }
            for (record in incoming) {
                val prior = merged[record.id]
                merged[record.id] = when {
                    prior == null -> record
                    record.id in stubIds -> prior // orphan-photo stubs never overwrite a real row
                    else -> preferred(prior, record, incomingWinsTies = false)
                }
            }
            val array = JSONArray()
            merged.values.forEach { array.put(it.toJson()) }
            PlainFileSafeWriter.replace(index, array.toString(2).toByteArray(Charsets.UTF_8)) { it.isNotEmpty() }
            newFiles += index
        }
        verify(root, byFolder)

        // 3) New copies are durable locally: queue Archive Dir retain, then remove old copies.
        newFiles.forEach(retainNew)
        for (old in movable.distinct()) removeOld(old)
        // Destination indexes live in flight folders; only legacy root/day indexes are removed.
        for (index in oldIndexFiles) removeOld(index)
        for (dayDir in AndroidClueFlightFolders.legacyDayFolders(root)) {
            if (dayDir.listFiles().isNullOrEmpty()) removeOld(dayDir)
        }
        File(root, LEGACY_FLAG_FILE).takeIf { it.exists() }?.let { removeOld(it) }
        markFlagFile(root)
        return Result(byId.size, byFolder.size, recovered)
    }

    private fun resolveFlight(
        record: AndroidClueRecord,
        sourceDay: String?,
        hints: () -> FlightHints,
    ): Pair<String, Boolean> {
        record.binding?.flightId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it to false }
        val aircraft = record.binding?.aircraftId?.takeIf { it.isNotBlank() } ?: record.sourceDesignator
        val ownershipDay = todayArchiveDirectoryName(Date(record.ownershipTimeMs))
        val days = listOfNotNull(sourceDay, ownershipDay).distinct()
        if (RidFlightId.canonicalAircraftId(aircraft).isNotEmpty()) {
            for (day in days) {
                hints().unique(aircraft, day)?.let { return it to true }
            }
        }
        val day = sourceDay ?: ownershipDay
        val dayStart = parseArchiveDirectoryDateMs(day) ?: 0L
        val canonical = RidFlightId.canonicalAircraftId(aircraft).ifEmpty { "UNKNOWN" }
        return RidFlightId.make(canonical, dayStart) to true
    }

    /** Never demote a confirmed upload; otherwise [incomingWinsTies] picks on equal/newer timestamps. */
    private fun preferred(
        existing: AndroidClueRecord,
        incoming: AndroidClueRecord,
        incomingWinsTies: Boolean,
    ): AndroidClueRecord = when {
        existing.uploadConfirmed() && !incoming.uploadConfirmed() -> existing
        incoming.uploadConfirmed() && !existing.uploadConfirmed() -> incoming
        incoming.createdAtMs > existing.createdAtMs -> if (incomingWinsTies) incoming else existing
        incoming.createdAtMs == existing.createdAtMs && incomingWinsTies -> incoming
        else -> existing
    }

    private fun oldPhotoCandidates(root: File, source: Source, name: String): List<File> =
        listOf(File(source.dir, baseName(name)), File(root, name), File(root, baseName(name)))

    private fun copyPhoto(
        root: File,
        source: Source,
        destRelative: String,
        oldName: String,
        movable: MutableList<File>,
        newFiles: MutableList<File>,
    ) {
        val dest = File(root, destRelative)
        val candidates = oldPhotoCandidates(root, source, oldName)
        // Zero-byte leftovers carry nothing; clear them with the migrated originals.
        candidates.filter { it.isFile && it.length() == 0L }.forEach { movable += it }
        val src = candidates.firstOrNull { it.isFile && it.length() > 0L }
        if (src == null) {
            // Photo-missing rows stay indexed; an existing destination copy is kept.
            if (dest.isFile && dest.length() > 0L) newFiles += dest
            return
        }
        if (src.canonicalFile == dest.canonicalFile) return
        if (!(dest.isFile && dest.length() == src.length())) {
            dest.parentFile?.mkdirs()
            PlainFileSafeWriter.replace(dest, src.readBytes()) { it.isNotEmpty() }
        }
        if (!(dest.isFile && dest.length() == src.length())) {
            throw IOException("Clue photo copy verify failed")
        }
        newFiles += dest
        movable += src
    }

    private fun verify(root: File, byFolder: Map<String, List<AndroidClueRecord>>) {
        for ((folder, expected) in byFolder) {
            val index = File(File(root, folder), AndroidClueFlightFolders.INDEX_FILENAME)
            if (!index.isFile) throw IOException("Flight index missing after migration")
            val ids = loadRecords(index).map { it.id }.toSet()
            for (record in expected) {
                if (record.id !in ids) throw IOException("Migrated clue missing from flight index")
                val image = File(root, record.imageFilename)
                if (image.isFile && image.length() == 0L) throw IOException("Empty migrated photo")
            }
        }
    }

    private fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun loadRecords(index: File): List<AndroidClueRecord> {
        val array = JSONArray(index.readText())
        val out = ArrayList<AndroidClueRecord>(array.length())
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: throw IOException("Invalid clue index entry")
            out += json.toAndroidClueRecord() ?: throw IOException("Unreadable clue index entry")
        }
        return out
    }

    /** Test helper: clear completion so migrate runs again. */
    @JvmStatic
    fun resetForTests(context: Context?, root: File) {
        context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.remove(KEY_DONE)?.commit()
        File(root, FLAG_FILE).delete()
    }

    @JvmStatic
    fun isComplete(context: Context, root: File): Boolean {
        if (File(root, FLAG_FILE).isFile) return true
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)
    }

    private fun markComplete(context: Context, root: File) {
        markFlagFile(root)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).commit()
    }

    private fun markFlagFile(root: File) {
        root.mkdirs()
        runCatching {
            PlainFileSafeWriter.replace(
                File(root, FLAG_FILE),
                "1".toByteArray(Charsets.UTF_8),
            ) { it.isNotEmpty() }
        }
    }

    /**
     * Best-effort Archive Dir flight-log hints (unique flight per aircraft + day). Only reached
     * when a clue has no flight id; may do provider IO, so callers stay on a worker.
     */
    private fun loadFlightHints(context: Context): FlightHints {
        val archive = runCatching { CaltopoClient.GetArchiveDir() }.getOrNull() ?: return FlightHints()
        if (!archive.isDirectory || !archive.canRead()) return FlightHints()
        val hints = linkedMapOf<String, MutableList<String>>()
        try {
            for (dayDir in archive.listFiles().orEmpty()) {
                val dayName = dayDir.name ?: continue
                if (!dayDir.isDirectory || !isDatedArchiveDirectoryName(dayName)) continue
                for (file in dayDir.listFiles().orEmpty()) {
                    val name = file.name ?: continue
                    if (!file.isFile || !name.endsWith(".json", ignoreCase = true)) continue
                    if (name.endsWith(".review.json", ignoreCase = true)) continue
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    }.getOrNull() ?: continue
                    val properties = runCatching {
                        JSONObject(String(bytes, Charsets.UTF_8))
                            .optJSONArray("features")
                            ?.optJSONObject(0)
                            ?.optJSONObject("properties")
                    }.getOrNull() ?: continue
                    val flightId = properties.optString("r2c_flight_id").takeIf { it.isNotEmpty() } ?: continue
                    val rid = properties.optJSONObject("r2c_prop")?.optString("rid").orEmpty()
                    val canonical = RidFlightId.canonicalAircraftId(rid)
                    if (canonical.isEmpty()) continue
                    hints.getOrPut("$canonical|$dayName") { mutableListOf() } += flightId
                }
            }
        } catch (_: Exception) {
            // Archive Dir unavailable mid-scan — fall back to synthetic ids.
        }
        return FlightHints(hints)
    }
}

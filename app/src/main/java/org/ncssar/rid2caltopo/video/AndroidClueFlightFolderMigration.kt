package org.ncssar.rid2caltopo.video

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.app.isDatedArchiveDirectoryName
import org.ncssar.rid2caltopo.app.todayArchiveDirectoryName
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.PlainFileSafeWriter
import java.io.File
import java.util.Date

/**
 * One-time move of legacy flat `files/clues/{id}.jpg` + root `clues.json` into
 * per-flight-day folders `files/clues/<tracks-ddMMMyyyy>/`, matching Apple layout.
 *
 * Crash-safe: copy → verify → rewrite day indexes → only then remove flat originals.
 * Never demotes uploadConfirmed rows back to pending. Runs only off Main.
 */
object AndroidClueFlightFolderMigration {
    private const val TAG = "ClueFlightFolderMig"
    private const val PREFS = "android_clue_flight_folder_migration"
    private const val KEY_DONE = "completed_v1"
    private const val FLAG_FILE = ".flight-folder-migration-v1-done"

    @JvmStatic
    fun migrateIfNeeded(context: Context, root: File) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "AndroidClueFlightFolderMigration must run off Main"
        }
        val app = context.applicationContext
        if (isComplete(app, root)) return
        try {
            val count = migrateRoot(root, loadFlightDayHints(app))
            markComplete(app, root)
            if (count > 0) {
                CaltopoClient.CTInfo(TAG, "Migrated flat clues into day folders count=$count")
            }
        } catch (error: Exception) {
            CaltopoClient.CTWarn(TAG, "Flight-folder migration deferred; flat originals retained", error)
        }
    }

    /**
     * Core migration against one clues root. Used by [migrateIfNeeded] and JVM unit tests
     * (no SharedPreferences). Returns the number of records written into day indexes.
     */
    internal fun migrateRoot(root: File, flightDayHints: Map<String, String> = emptyMap()): Int {
        if (File(root, FLAG_FILE).isFile) return 0
        val legacyIndex = File(root, "clues.json")
        val flatPhotos = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg", ignoreCase = true) }
            .orEmpty()
        if (!legacyIndex.isFile && flatPhotos.isEmpty()) {
            markFlagFile(root)
            return 0
        }
        val records = loadLegacyRecords(legacyIndex)
        val migrated = mutableListOf<AndroidClueRecord>()
        for (record in records) {
            migrated += migrateOne(root, record, flightDayHints)
        }
        // Orphan flat JPEGs without an index row → pending stub under ownership day.
        val indexedNames = migrated.map { File(it.imageFilename).name }.toSet()
        for (photo in flatPhotos) {
            if (photo.name.endsWith("-thumb.jpg", ignoreCase = true)) continue
            if (photo.name in indexedNames) continue
            val id = photo.name.removeSuffix(".jpg").removeSuffix(".JPG")
            if (id.isBlank()) continue
            val day = todayArchiveDirectoryName(
                Date(photo.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()),
            )
            migrated += migrateOne(
                root,
                AndroidClueRecord(
                    id = id,
                    mapKey = "unassigned",
                    lat = 0.0,
                    lng = 0.0,
                    alt = Double.NaN,
                    title = "Recovered clue",
                    description = "",
                    createdAtMs = photo.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                    sourceDesignator = "",
                    imageFilename = photo.name,
                    thumbnailFilename = "$id-thumb.jpg",
                    publishToCaltopo = true,
                    uploadState = "pending",
                    lastUploadError = "reindexed-orphan-photo",
                ),
                flightDayHints,
                forcedDay = day,
            )
        }
        writeDayIndexes(root, migrated)
        verifyDayIndexes(root, migrated)
        removeLegacyFlat(root, migrated)
        markFlagFile(root)
        return migrated.size
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

    private fun loadLegacyRecords(index: File): List<AndroidClueRecord> {
        if (!index.isFile) return emptyList()
        val array = JSONArray(index.readText())
        val out = ArrayList<AndroidClueRecord>(array.length())
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            toRecord(json)?.let { out += it }
        }
        return out
    }

    private fun toRecord(json: JSONObject): AndroidClueRecord? {
        // Reuse store parsing via a tiny local decode — keep uploadState intact.
        return runCatching {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val imageFilename = json.optString("imageFilename").takeIf { it.isNotBlank() } ?: return null
            val thumbnailFilename = json.optString("thumbnailFilename").takeIf { it.isNotBlank() } ?: return null
            AndroidClueRecord(
                id = id,
                mapKey = json.optString("mapKey"),
                lat = json.optDouble("lat", Double.NaN),
                lng = json.optDouble("lng", Double.NaN),
                alt = json.optDouble("alt", Double.NaN),
                title = json.optString("title", "Local marker"),
                description = json.optString("description"),
                createdAtMs = json.optLong("createdAtMs"),
                sourceDesignator = json.optString("sourceDesignator"),
                imageFilename = imageFilename,
                thumbnailFilename = thumbnailFilename,
                publishToCaltopo = json.optBoolean("publishToCaltopo"),
                destinationTeamId = json.optString("destinationTeamId"),
                destinationMapTitle = json.optString("destinationMapTitle"),
                uploadState = json.optString("uploadState", "legacy"),
                lastUploadError = json.optString("lastUploadError"),
                uploadAttempts = json.optInt("uploadAttempts", 0),
                caltopoMediaId = json.optString("caltopoMediaId"),
                caltopoLinkId = json.optString("caltopoLinkId"),
                caltopoMarkerId = json.optString("caltopoMarkerId"),
                caltopoPartsReconciled = json.optBoolean("caltopoPartsReconciled", false),
                caltopoRemotelyDeleted = json.optBoolean("caltopoRemotelyDeleted", false),
                binding = org.ncssar.rid2caltopo.data.ClueBinding.fromJson(json.optJSONObject("binding")),
            ).takeIf { it.lat.isFinite() && it.lng.isFinite() }
        }.getOrNull()
    }

    /**
     * Prefer Archive Dir day that holds [flightId] metadata when reachable without blocking;
     * otherwise day from ownership time.
     */
    private fun resolveDay(
        record: AndroidClueRecord,
        flightDayHints: Map<String, String>,
        forcedDay: String? = null,
    ): String {
        if (forcedDay != null && isDatedArchiveDirectoryName(forcedDay)) return forcedDay
        val flightId = record.binding?.flightId?.trim().orEmpty()
        if (flightId.isNotEmpty()) {
            flightDayHints[flightId]?.takeIf { isDatedArchiveDirectoryName(it) }?.let { return it }
        }
        return todayArchiveDirectoryName(Date(record.ownershipTimeMs))
    }

    private fun loadFlightDayHints(context: Context): Map<String, String> {
        // Best-effort, non-blocking: only local document listing already available.
        val archive = runCatching { CaltopoClient.GetArchiveDir() }.getOrNull() ?: return emptyMap()
        if (!archive.isDirectory || !archive.canRead()) return emptyMap()
        val hints = linkedMapOf<String, String>()
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
                    val flightId = runCatching {
                        org.json.JSONObject(String(bytes, Charsets.UTF_8))
                            .optJSONArray("features")
                            ?.optJSONObject(0)
                            ?.optJSONObject("properties")
                            ?.optString("r2c_flight_id")
                            ?.takeIf { it.isNotEmpty() }
                    }.getOrNull() ?: continue
                    hints.putIfAbsent(flightId, dayName)
                }
            }
        } catch (_: Exception) {
            // Archive Dir unavailable mid-scan — fall back to ownership-time days.
        }
        return hints
    }

    private fun migrateOne(
        root: File,
        record: AndroidClueRecord,
        flightDayHints: Map<String, String>,
        forcedDay: String? = null,
    ): AndroidClueRecord {
        val day = resolveDay(record, flightDayHints, forcedDay)
        val imageBase = record.imageFilename.substringAfterLast('/').substringAfterLast('\\')
        val thumbBase = record.thumbnailFilename.substringAfterLast('/').substringAfterLast('\\')
        val destImageRel = "$day/$imageBase"
        val destThumbRel = "$day/$thumbBase"
        val dayDir = File(root, day)
        dayDir.mkdirs()
        val destImage = File(root, destImageRel)
        val destThumb = File(root, destThumbRel)
        val srcImage = File(root, imageBase).takeIf { it.isFile }
            ?: File(root, record.imageFilename).takeIf { it.isFile }
        val srcThumb = File(root, thumbBase).takeIf { it.isFile }
            ?: File(root, record.thumbnailFilename).takeIf { it.isFile }
        // Copy-then-verify; photo-missing rows stay indexed.
        if (srcImage != null && srcImage.canonicalFile != destImage.canonicalFile) {
            PlainFileSafeWriter.replace(destImage, srcImage.readBytes()) { it.isNotEmpty() }
            check(destImage.isFile && destImage.length() > 0L) { "Clue photo copy failed id=${record.id}" }
        }
        if (srcThumb != null && srcThumb.canonicalFile != destThumb.canonicalFile) {
            PlainFileSafeWriter.replace(destThumb, srcThumb.readBytes()) { it.isNotEmpty() }
        }
        // Preserve uploadConfirmed / uploadState exactly — never re-queue published clues.
        return record.copy(
            imageFilename = destImageRel,
            thumbnailFilename = destThumbRel,
        )
    }

    private fun writeDayIndexes(root: File, records: List<AndroidClueRecord>) {
        val byDay = records.groupBy { it.imageFilename.substringBefore('/') }
        for ((day, dayRecords) in byDay) {
            if (!isDatedArchiveDirectoryName(day)) continue
            val dayDir = File(root, day)
            dayDir.mkdirs()
            val array = JSONArray()
            dayRecords.forEach { array.put(it.toMigrationJson()) }
            PlainFileSafeWriter.replace(
                File(dayDir, "clues.json"),
                array.toString(2).toByteArray(Charsets.UTF_8),
            ) { it.isNotEmpty() }
        }
    }

    private fun verifyDayIndexes(root: File, records: List<AndroidClueRecord>) {
        val byDay = records.groupBy { it.imageFilename.substringBefore('/') }
        for ((day, dayRecords) in byDay) {
            val index = File(File(root, day), "clues.json")
            check(index.isFile) { "Day index missing after migration day=$day" }
            val loaded = JSONArray(index.readText())
            val ids = (0 until loaded.length()).mapNotNull { loaded.optJSONObject(it)?.optString("id") }.toSet()
            for (record in dayRecords) {
                check(record.id in ids) { "Migrated clue missing from day index id=${record.id}" }
                // Photos: verified when present; missing photos stay indexed as pending/failed.
                val image = File(root, record.imageFilename)
                if (image.isFile) {
                    check(image.length() > 0L) { "Empty migrated photo id=${record.id}" }
                }
            }
        }
    }

    private fun removeLegacyFlat(root: File, records: List<AndroidClueRecord>) {
        for (record in records) {
            val imageBase = record.imageFilename.substringAfterLast('/')
            val thumbBase = record.thumbnailFilename.substringAfterLast('/')
            val flatImage = File(root, imageBase)
            val flatThumb = File(root, thumbBase)
            val destImage = File(root, record.imageFilename)
            val destThumb = File(root, record.thumbnailFilename)
            if (flatImage.isFile && destImage.isFile &&
                flatImage.canonicalFile != destImage.canonicalFile
            ) {
                flatImage.delete()
            }
            if (flatThumb.isFile && destThumb.isFile &&
                flatThumb.canonicalFile != destThumb.canonicalFile
            ) {
                flatThumb.delete()
            }
        }
        val legacy = File(root, "clues.json")
        if (legacy.isFile) legacy.delete()
    }

    private fun AndroidClueRecord.toMigrationJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("mapKey", mapKey)
        .put("lat", lat)
        .put("lng", lng)
        .put("alt", alt.takeIf { it.isFinite() } ?: JSONObject.NULL)
        .put("title", title)
        .put("description", description)
        .put("createdAtMs", createdAtMs)
        .put("sourceDesignator", sourceDesignator)
        .put("imageFilename", imageFilename)
        .put("thumbnailFilename", thumbnailFilename)
        .put("publishToCaltopo", publishToCaltopo)
        .put("destinationTeamId", destinationTeamId)
        .put("destinationMapTitle", destinationMapTitle)
        .put("uploadState", uploadState)
        .put("lastUploadError", lastUploadError)
        .put("uploadAttempts", uploadAttempts)
        .put("caltopoMediaId", caltopoMediaId)
        .put("caltopoLinkId", caltopoLinkId)
        .put("caltopoMarkerId", caltopoMarkerId)
        .put("caltopoPartsReconciled", caltopoPartsReconciled)
        .put("caltopoRemotelyDeleted", caltopoRemotelyDeleted)
        .also { json -> binding?.let { json.put("binding", it.toJson()) } }
}

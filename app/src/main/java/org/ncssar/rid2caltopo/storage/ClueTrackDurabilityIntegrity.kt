package org.ncssar.rid2caltopo.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.PlainFileSafeWriter
import org.ncssar.rid2caltopo.data.WaypointTrackJournal
import java.io.File
import java.util.Locale

/**
 * Startup integrity for clue photos/indexes and unfinished waypoint journals.
 * Always runs off Main; never blocks LiveTrack start or clue upload lanes.
 */
object ClueTrackDurabilityIntegrity {
    private const val TAG = "ClueTrackDurability"

    data class Summary(
        val orphansReindexed: Int = 0,
        val missingPhotos: Int = 0,
        val journalsRecovered: Int = 0,
        val journalsRetained: Int = 0,
    )

    @JvmStatic
    fun runAtStartup(context: Context) {
        val app = context.applicationContext
        Thread({
            try {
                val summary = scan(app)
                CaltopoClient.CTDebug(
                    TAG,
                    String.format(
                        Locale.US,
                        "startup integrity orphansReindexed=%d missingPhotos=%d journalsRecovered=%d journalsRetained=%d",
                        summary.orphansReindexed,
                        summary.missingPhotos,
                        summary.journalsRecovered,
                        summary.journalsRetained,
                    ),
                )
            } catch (error: Exception) {
                CaltopoClient.CTWarn(TAG, "startup integrity scan raised; retained data left untouched", error)
            }
        }, "clue-track-durability").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    /** Testable scan of one clues directory + unfinished journals. */
    @JvmStatic
    fun scan(context: Context): Summary {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "ClueTrackDurabilityIntegrity must run off Main"
        }
        val cluesRoot = File(context.filesDir, "clues")
        val clueSummary = reconcileClueDirectory(cluesRoot)
        val beforeJournals = countJournals()
        try {
            WaypointTrackJournal.recoverUnfinished()
        } catch (error: Exception) {
            CaltopoClient.CTWarn(TAG, "recoverUnfinished raised; journals retained", error)
        }
        val afterJournals = countJournals()
        val recovered = (beforeJournals - afterJournals).coerceAtLeast(0)
        return clueSummary.copy(
            journalsRecovered = recovered,
            journalsRetained = afterJournals,
        )
    }

    /** Orphan photos → stub index rows; index without photo → keep and flag failed/orphan. */
    internal fun reconcileClueDirectory(root: File): Summary {
        if (!root.isDirectory && !root.mkdirs()) {
            return Summary()
        }
        val indexFile = File(root, "clues.json")
        val byId = linkedMapOf<String, JSONObject>()
        if (indexFile.isFile) {
            runCatching {
                val array = JSONArray(indexFile.readText())
                for (i in 0 until array.length()) {
                    val json = array.optJSONObject(i) ?: continue
                    val id = json.optString("id").trim()
                    if (id.isNotEmpty()) byId[id] = JSONObject(json.toString())
                }
            }.onFailure {
                CaltopoClient.CTWarn(TAG, "clues.json unreadable; leaving as-is for manual recovery", it as? Exception)
                return Summary()
            }
        }
        var orphansReindexed = 0
        var missingPhotos = 0
        val photoIds = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") && !it.name.endsWith("-thumb.jpg") }
            ?.mapNotNull { file ->
                val name = file.name.removeSuffix(".jpg")
                if (name.isBlank() || name.contains("-thumb")) null else name to file
            }
            .orEmpty()
        for ((id, file) in photoIds) {
            if (byId.containsKey(id)) continue
            val thumb = File(root, "$id-thumb.jpg")
            val stub = JSONObject()
                .put("id", id)
                .put("mapKey", "unassigned")
                .put("lat", 0.0)
                .put("lng", 0.0)
                .put("alt", JSONObject.NULL)
                .put("title", "Recovered clue")
                .put("description", "")
                .put("createdAtMs", file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis())
                .put("sourceDesignator", "")
                .put("imageFilename", file.name)
                .put("thumbnailFilename", if (thumb.isFile) thumb.name else file.name)
                .put("publishToCaltopo", true)
                .put("destinationTeamId", "")
                .put("uploadState", "pending")
                .put("lastUploadError", "reindexed-orphan-photo")
            byId[id] = stub
            orphansReindexed++
        }
        for ((id, json) in byId) {
            val imageName = json.optString("imageFilename")
            if (imageName.isBlank()) continue
            val image = File(root, imageName)
            if (image.isFile) continue
            missingPhotos++
            if (json.optString("uploadState") != "failed") {
                json.put("uploadState", "failed")
                json.put("lastUploadError", "missing-photo")
            }
        }
        if (orphansReindexed > 0 || missingPhotos > 0 || !indexFile.isFile) {
            val out = JSONArray()
            byId.values.forEach { out.put(it) }
            PlainFileSafeWriter.replace(indexFile, out.toString(2).toByteArray(Charsets.UTF_8)) { it.isNotEmpty() }
        }
        return Summary(orphansReindexed = orphansReindexed, missingPhotos = missingPhotos)
    }

    private fun countJournals(): Int {
        // Mirror WaypointTrackJournal root layout for a cheap pre/post count.
        val base = org.ncssar.rid2caltopo.app.R2CApplication.getAppCtxt()?.let {
            File(it.filesDir, "operational-state/waypoint-track-journals")
        } ?: return 0
        return base.listFiles { _, name -> name.endsWith(".ndjson") }?.size ?: 0
    }
}

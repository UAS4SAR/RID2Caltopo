package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.app.R2CApplication
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Flights whose operator explicitly released an unverified Mine claim.
 * Tracker upload of that archive stays held until this set contains the flight id.
 * The release never invents a pilot; callers record it only when a pilot was already stored.
 */
object OwnershipReviewReleases {
    private val trackerUploads = ConcurrentHashMap.newKeySet<String>()
    private var loaded = false
    private var testFile: File? = null

    @JvmStatic fun useFileForTesting(file: File?) {
        testFile = file
        trackerUploads.clear()
        loaded = file == null
    }

    private fun file(): File? = testFile ?: R2CApplication.getAppCtxt()?.let { context ->
        org.ncssar.rid2caltopo.storage.ArchiveDirAvailability.operationalStateFile(
            context.filesDir,
            "ownership-review-releases.json",
        )
    }

    @JvmStatic fun allowTrackerUpload(flightId: String) {
        val id = flightId.trim()
        if (id.isEmpty()) return
        load()
        if (!trackerUploads.add(id)) return
        persist()
    }

    @JvmStatic fun allowsTrackerUpload(flightId: String): Boolean {
        val id = flightId.trim()
        if (id.isEmpty()) return false
        load()
        return trackerUploads.contains(id)
    }

    private fun load() {
        if (loaded) return
        val file = file()
        loaded = true
        if (file == null || !file.exists()) return
        val array = runCatching { JSONObject(file.readText()).optJSONArray("trackerUploads") }.getOrNull() ?: return
        for (index in 0 until array.length()) {
            val id = array.optString(index).trim()
            if (id.isNotEmpty()) trackerUploads.add(id)
        }
    }

    private fun persist() {
        val file = file() ?: return
        val array = JSONArray()
        trackerUploads.forEach { array.put(it) }
        val body = JSONObject().put("trackerUploads", array).toString().toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        PlainFileSafeWriter.replaceLocal(file, body) { it.isNotEmpty() }
    }
}

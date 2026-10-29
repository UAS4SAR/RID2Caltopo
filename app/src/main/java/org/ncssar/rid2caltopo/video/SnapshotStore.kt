package org.ncssar.rid2caltopo.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.AwaitingMapClueMatch
import org.ncssar.rid2caltopo.data.ClueBinding
import org.ncssar.rid2caltopo.data.ClueBindingText
import org.ncssar.rid2caltopo.data.PlainFileSafeWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Metadata for one clue whose image is retained in the app's private storage. */
data class AndroidClueRecord(
    val id: String,
    val mapKey: String,
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val title: String,
    val description: String,
    val createdAtMs: Long,
    val sourceDesignator: String,
    val imageFilename: String,
    val thumbnailFilename: String,
    val publishToCaltopo: Boolean,
    val destinationTeamId: String = "",
    val uploadState: String = "legacy",
    val lastUploadError: String = "",
    /** Waypoint binding (optional; records saved before binding existed have none). */
    val binding: ClueBinding? = null,
) {
    /** Capture time used for flight ownership: the bound capture time on the drone clock. */
    val ownershipTimeMs: Long get() = binding?.captureTimeMs ?: createdAtMs

    /** Text for CalTopo and the KMZ: the stored description plus the binding block. */
    val publishedDescription: String get() = ClueBindingText.publishedDescription(description, binding)
}

/**
 * Durable local-first clue storage, matching the Apple clue-store contract.
 *
 * Images and metadata are committed before a caller starts a CalTopo upload. The index is
 * replaced atomically so a process death cannot leave a partially-written JSON document.
 */
class AndroidClueStore private constructor(
    private var root: File,
    private val context: Context? = null,
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "clues"), context.applicationContext)

    private val indexFile get() = File(root, "clues.json")
    private val storedRecords = LinkedHashMap<String, AndroidClueRecord>()
    private var loaded = false
    private val records: LinkedHashMap<String, AndroidClueRecord>
        get() {
            if (!loaded) loadIndex()
            return storedRecords
        }

    @Synchronized
    fun recordsForMap(mapKey: String): List<AndroidClueRecord> =
        records.values
            .filter { it.mapKey == mapKey && imageFile(it).isFile }
            .sortedByDescending { it.createdAtMs }

    @Synchronized
    fun save(
        mapKey: String,
        lat: Double,
        lng: Double,
        alt: Double,
        title: String,
        description: String,
        createdAtMs: Long,
        sourceDesignator: String,
        bitmap: Bitmap,
        publishToCaltopo: Boolean,
        destinationTeamId: String = "",
        binding: ClueBinding? = null,
    ): AndroidClueRecord {
        val id = UUID.randomUUID().toString().lowercase()
        val imageFilename = "$id.jpg"
        val thumbnailFilename = "$id-thumb.jpg"
        val record = AndroidClueRecord(
            id = id,
            mapKey = mapKey,
            lat = lat,
            lng = lng,
            alt = alt,
            title = title.ifBlank { "Local marker" },
            description = description,
            createdAtMs = createdAtMs,
            sourceDesignator = sourceDesignator,
            imageFilename = imageFilename,
            thumbnailFilename = thumbnailFilename,
            publishToCaltopo = publishToCaltopo,
            destinationTeamId = destinationTeamId,
            uploadState = if (publishToCaltopo) "pending" else "local",
            binding = binding,
        )
        val encodeStarted = System.nanoTime()
        val imageBytes = encodeJpeg(bitmap, 90)
        val thumbnailBytes = encodeJpeg(clueThumbnail(bitmap), 78)
        if (context != null) org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("ClueStore",
            "Save id=$id encodedMs=${(System.nanoTime() - encodeStarted) / 1_000_000}")
        saveEncoded(record, imageBytes, thumbnailBytes)
        return record
    }

    internal fun saveEncoded(
        record: AndroidClueRecord,
        imageBytes: ByteArray,
        thumbnailBytes: ByteArray,
    ) {
        val started = System.nanoTime()
        require(record.lat.isFinite() && record.lng.isFinite()) { "Clue location is invalid" }
        require(imageBytes.isNotEmpty()) { "Clue image is empty" }
        require(thumbnailBytes.isNotEmpty()) { "Clue thumbnail is empty" }
        val directory = synchronized(this) { selectRoot(); root }
        context?.let { org.ncssar.rid2caltopo.app.FlightStorage.prepareWrite(it, imageBytes.size.toLong() + thumbnailBytes.size) }
        directory.mkdirs()
        val imageFile = File(directory, record.imageFilename)
        val thumbnailFile = File(directory, record.thumbnailFilename)
        val preparedAt = System.nanoTime()
        try {
            replaceFileAtomically(imageFile, imageBytes)
            val imageAt = System.nanoTime()
            replaceFileAtomically(thumbnailFile, thumbnailBytes)
            val thumbnailAt = System.nanoTime()
            synchronized(this) {
                selectRoot()
                records[record.id] = record
                persistIndex()
            }
            if (context != null) org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("ClueStore",
                "Save id=${record.id} storageCheckMs=${(preparedAt - started) / 1_000_000} " +
                    "imageMs=${(imageAt - preparedAt) / 1_000_000} thumbnailMs=${(thumbnailAt - imageAt) / 1_000_000} " +
                    "indexMs=${(System.nanoTime() - thumbnailAt) / 1_000_000}")
        } catch (error: Exception) {
            synchronized(this) { records.remove(record.id) }
            imageFile.delete()
            thumbnailFile.delete()
            throw error
        }
    }

    fun delete(id: String): Boolean {
        val record = synchronized(this) {
            val removed = records.remove(id) ?: return false
            try {
                persistIndex()
            } catch (error: Exception) {
                records[id] = removed
                throw error
            }
            removed
        }
        return try {
            if (context != null) {
                org.ncssar.rid2caltopo.storage.OperationalStorage.remove(imageFile(record))
                org.ncssar.rid2caltopo.storage.OperationalStorage.remove(thumbnailFile(record))
            } else {
                imageFile(record).delete()
                thumbnailFile(record).delete()
            }
            true
        } catch (error: Exception) {
            synchronized(this) {
                records[id] = record
                persistIndex()
            }
            throw error
        }
    }

    /**
     * Assigns the unassigned publishable clues owned by [flight] (shared rule in AwaitingMapClueMatch,
     * with [otherFlights] competing for them) to the chosen map.
     */
    fun bindAwaitingFlight(flight: JSONObject, otherFlights: List<JSONObject>, mapId: String, teamId: String) {
        val owned = awaitingFlightClues(flight, otherFlights).map { it.id }.toSet()
        synchronized(this) {
            records.values.toList().filter { it.id in owned && it.mapKey == "unassigned" && it.publishToCaltopo }.forEach {
                records[it.id] = it.copy(mapKey = "map:$mapId", destinationTeamId = teamId, uploadState = "pending")
            }
            persistIndex()
        }
    }

    /** Clue photos that belong to an awaiting-map flight (shared rule in AwaitingMapClueMatch). */
    fun awaitingFlightClues(flight: JSONObject, otherFlights: List<JSONObject>): List<AndroidClueRecord> {
        // File checks stay outside the store monitor. Flight recovery was holding it across
        // image existence checks, and pending uploads waited on that same monitor.
        val snapshot = synchronized(this) { records.values.toList() }
        val present = snapshot.filter { imageFile(it).isFile }
        return AwaitingMapClueMatch.ownedClues(present, flight, otherFlights,
            { it.sourceDesignator }, { it.ownershipTimeMs }, { it.binding?.flightId })
    }

    /** Every stored clue and local marker whose image is present. */
    @Synchronized
    fun allRecords(): List<AndroidClueRecord> = records.values.filter { imageFile(it).isFile }

    @Synchronized
    fun record(id: String): AndroidClueRecord? = records[id]

    /** Replaces stored records (binding refresh); returns the ones that changed. */
    @Synchronized
    fun update(changed: List<AndroidClueRecord>): List<AndroidClueRecord> {
        val applied = changed.filter { records.containsKey(it.id) && records[it.id] != it }
        if (applied.isEmpty()) return emptyList()
        val prior = applied.associate { it.id to records.getValue(it.id) }
        applied.forEach { records[it.id] = it }
        try {
            persistIndex()
        } catch (error: Exception) {
            prior.forEach { (id, record) -> records[id] = record }
            throw error
        }
        return applied
    }

    @Synchronized
    fun pendingForMap(mapId: String, teamId: String): List<AndroidClueRecord> {
        if (!loaded) loadIndex()
        return records.values.filter { it.publishToCaltopo && it.uploadState == "pending" &&
            mapId.isNotBlank() && teamId.isNotBlank() && it.mapKey == "map:$mapId" && it.destinationTeamId == teamId }
    }

    @Synchronized
    fun recordUploadResult(id: String, success: Boolean, error: String = "", terminal: Boolean = false) {
        val record = records[id] ?: return
        records[id] = record.copy(uploadState = if (success) "published" else if (terminal) "failed" else "pending", lastUploadError = error)
        try {
            persistIndex()
        } catch (failure: Exception) {
            records[id] = record
            throw failure
        }
    }

    fun imageFile(record: AndroidClueRecord): File = File(root, record.imageFilename)

    fun thumbnailFile(record: AndroidClueRecord): File = File(root, record.thumbnailFilename)

    fun loadThumbnail(record: AndroidClueRecord): Bitmap? {
        val path = thumbnailFile(record).absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, options)
    }

    @Synchronized
    private fun loadIndex() {
        loaded = false
        selectRoot()
        org.ncssar.rid2caltopo.storage.OperationalStorage.requireFor(root)
        val prepared = LinkedHashMap<String, AndroidClueRecord>()
        if (indexFile.exists()) {
            try {
                val array = JSONArray(indexFile.readText())
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: throw java.io.IOException("Invalid clue index entry")
                    val record = json.toAndroidClueRecord() ?: throw java.io.IOException("Unreadable clue index entry")
                    // Missing media can be temporary. Never discard its retained metadata.
                    prepared[record.id] = record
                }
            } catch (error: Exception) {
                throw java.io.IOException("Clue index unavailable; original index and photos preserved", error)
            }
        }
        storedRecords.clear()
        storedRecords.putAll(prepared)
        loaded = true
    }

    private fun selectRoot() {
        val app = context ?: return
        val next = if (org.ncssar.rid2caltopo.data.CaltopoClient.GetArchiveUri() == null) {
            check(org.ncssar.rid2caltopo.data.CaltopoClient.HasArchiveDirForCurrentSession()) { "Choose Archive Dir before saving clues." }
            org.ncssar.rid2caltopo.storage.TemporaryStorage.directory(app, "clues")
        } else File(app.filesDir, "clues")
        if (next != root) { root = next; loaded = false }
    }

    @Synchronized
    private fun persistIndex() {
        root.mkdirs()
        val array = JSONArray()
        records.values.forEach { array.put(it.toJson()) }
        replaceFileAtomically(indexFile, array.toString(2).toByteArray(Charsets.UTF_8))
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            "Unable to encode clue JPEG"
        }
        return output.toByteArray()
    }

    private fun replaceFileAtomically(target: File, bytes: ByteArray) {
        // .partial + fsync + verify + atomic move (FlightArchiveWriter.kt).
        PlainFileSafeWriter.replace(target, bytes) { it.isNotEmpty() }
    }

    companion object {
        private var shared: AndroidClueStore? = null
        @JvmStatic @Synchronized fun shared(context: Context): AndroidClueStore =
            shared ?: AndroidClueStore(context).also { shared = it }
        private const val THUMBNAIL_MAX_SIDE = 180

        internal fun forDirectory(root: File): AndroidClueStore = AndroidClueStore(root)

        private fun thumbnailSampleSize(width: Int, height: Int): Int {
            val longest = maxOf(width, height)
            var sampleSize = 1
            while (longest / sampleSize > THUMBNAIL_MAX_SIDE) {
                sampleSize *= 2
            }
            return sampleSize
        }

        private fun clueThumbnail(bitmap: Bitmap): Bitmap {
            val maxSide = THUMBNAIL_MAX_SIDE
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= maxSide) return bitmap
            val scale = maxSide.toDouble() / longest.toDouble()
            return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }
    }
}

private fun AndroidClueRecord.toJson(): JSONObject = JSONObject()
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
    .put("uploadState", uploadState)
    .put("lastUploadError", lastUploadError)
    .also { json -> binding?.let { json.put("binding", it.toJson()) } }

private fun JSONObject.toAndroidClueRecord(): AndroidClueRecord? {
    val id = optString("id").takeIf { it.isNotBlank() } ?: return null
    val imageFilename = optString("imageFilename").takeIf { it.isNotBlank() } ?: return null
    val thumbnailFilename = optString("thumbnailFilename").takeIf { it.isNotBlank() } ?: return null
    return AndroidClueRecord(
        id = id,
        mapKey = optString("mapKey"),
        lat = optDouble("lat", Double.NaN),
        lng = optDouble("lng", Double.NaN),
        alt = optDouble("alt", Double.NaN),
        title = optString("title", "Local marker"),
        description = optString("description"),
        createdAtMs = optLong("createdAtMs"),
        sourceDesignator = optString("sourceDesignator"),
        imageFilename = imageFilename,
        thumbnailFilename = thumbnailFilename,
        publishToCaltopo = optBoolean("publishToCaltopo"),
        destinationTeamId = optString("destinationTeamId"),
        uploadState = optString("uploadState", "legacy"),
        lastUploadError = optString("lastUploadError"),
        binding = ClueBinding.fromJson(optJSONObject("binding")),
    ).takeIf { it.lat.isFinite() && it.lng.isFinite() }
}

internal fun clueShareReport(record: AndroidClueRecord, format: CoordinateDisplayFormat): String {
    val captured = java.time.Instant.ofEpochMilli(record.createdAtMs).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
    return "${record.title}\nAircraft: ${record.sourceDesignator}\nCaptured: $captured\nLocation: ${CoordinateFormatter.format(record.lat, record.lng, format)} (${format.label})\n\n${record.publishedDescription}"
}

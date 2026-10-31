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

    private val storedRecords = LinkedHashMap<String, AndroidClueRecord>()
    private var loaded = false

    fun recordsForMap(mapKey: String): List<AndroidClueRecord> {
        ensureLoaded()
        // File existence stays outside the store monitor (same as awaitingFlightClues).
        val snapshot = synchronized(this) { storedRecords.values.toList() }
        return snapshot
            .filter { it.mapKey == mapKey && imageFile(it).isFile }
            .sortedByDescending { it.createdAtMs }
    }

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
        // JPEG encode must not hold the store monitor — upload flush shares that lock.
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
        ensureLoaded()
        val directory = synchronized(this) { selectRoot(); root }
        // App-private clue bytes must not wait on Archive Dir hydration.
        context?.let {
            org.ncssar.rid2caltopo.app.FlightStorage.prepareAppPrivateWrite(
                it,
                imageBytes.size.toLong() + thumbnailBytes.size,
            )
        }
        directory.mkdirs()
        val imageFile = File(directory, record.imageFilename)
        val thumbnailFile = File(directory, record.thumbnailFilename)
        val preparedAt = System.nanoTime()
        try {
            replaceFileAtomically(imageFile, imageBytes)
            val imageAt = System.nanoTime()
            replaceFileAtomically(thumbnailFile, thumbnailBytes)
            val thumbnailAt = System.nanoTime()
            // Memory publish under the store monitor; index fsync stays outside so
            // pending uploads / flush cannot ANR waiting on disk (8222efe9 class).
            val indexSnapshot: List<AndroidClueRecord>
            val indexRoot: File
            synchronized(this) {
                selectRoot()
                storedRecords[record.id] = record
                indexSnapshot = storedRecords.values.toList()
                indexRoot = root
            }
            persistIndexSnapshot(indexSnapshot, indexRoot)
            if (context != null) org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("ClueStore",
                "Save id=${record.id} storageCheckMs=${(preparedAt - started) / 1_000_000} " +
                    "imageMs=${(imageAt - preparedAt) / 1_000_000} thumbnailMs=${(thumbnailAt - imageAt) / 1_000_000} " +
                    "indexMs=${(System.nanoTime() - thumbnailAt) / 1_000_000}")
        } catch (error: Exception) {
            synchronized(this) { storedRecords.remove(record.id) }
            imageFile.delete()
            thumbnailFile.delete()
            throw error
        }
    }

    fun delete(id: String): Boolean {
        ensureLoaded()
        val removed: AndroidClueRecord
        val indexSnapshot: List<AndroidClueRecord>
        val indexRoot: File
        synchronized(this) {
            removed = storedRecords.remove(id) ?: return false
            indexSnapshot = storedRecords.values.toList()
            indexRoot = root
        }
        try {
            persistIndexSnapshot(indexSnapshot, indexRoot)
        } catch (error: Exception) {
            synchronized(this) { storedRecords[id] = removed }
            throw error
        }
        return try {
            if (context != null) {
                org.ncssar.rid2caltopo.storage.OperationalStorage.remove(imageFile(removed))
                org.ncssar.rid2caltopo.storage.OperationalStorage.remove(thumbnailFile(removed))
            } else {
                imageFile(removed).delete()
                thumbnailFile(removed).delete()
            }
            true
        } catch (error: Exception) {
            val restoreSnapshot: List<AndroidClueRecord>
            val restoreRoot: File
            synchronized(this) {
                storedRecords[id] = removed
                restoreSnapshot = storedRecords.values.toList()
                restoreRoot = root
            }
            persistIndexSnapshot(restoreSnapshot, restoreRoot)
            throw error
        }
    }

    /**
     * Assigns the unassigned publishable clues owned by [flight] (shared rule in AwaitingMapClueMatch,
     * with [otherFlights] competing for them) to the chosen map.
     */
    fun bindAwaitingFlight(flight: JSONObject, otherFlights: List<JSONObject>, mapId: String, teamId: String) {
        val owned = awaitingFlightClues(flight, otherFlights).map { it.id }.toSet()
        val indexSnapshot: List<AndroidClueRecord>?
        val indexRoot: File?
        synchronized(this) {
            var changed = false
            storedRecords.values.toList().filter { it.id in owned && it.mapKey == "unassigned" && it.publishToCaltopo }.forEach {
                val updated = it.copy(mapKey = "map:$mapId", destinationTeamId = teamId, uploadState = "pending")
                if (storedRecords[it.id] != updated) {
                    storedRecords[it.id] = updated
                    changed = true
                }
            }
            if (!changed) {
                indexSnapshot = null
                indexRoot = null
            } else {
                indexSnapshot = storedRecords.values.toList()
                indexRoot = root
            }
        }
        if (indexSnapshot != null && indexRoot != null) {
            persistIndexSnapshot(indexSnapshot, indexRoot)
        }
    }

    /** Clue photos that belong to an awaiting-map flight (shared rule in AwaitingMapClueMatch). */
    fun awaitingFlightClues(flight: JSONObject, otherFlights: List<JSONObject>): List<AndroidClueRecord> {
        ensureLoaded()
        // File checks stay outside the store monitor. Flight recovery was holding it across
        // image existence checks, and pending uploads waited on that same monitor.
        val snapshot = synchronized(this) { storedRecords.values.toList() }
        val present = snapshot.filter { imageFile(it).isFile }
        return AwaitingMapClueMatch.ownedClues(present, flight, otherFlights,
            { it.sourceDesignator }, { it.ownershipTimeMs }, { it.binding?.flightId })
    }

    /** Every stored clue and local marker whose image is present. */
    fun allRecords(): List<AndroidClueRecord> {
        ensureLoaded()
        val snapshot = synchronized(this) { storedRecords.values.toList() }
        return snapshot.filter { imageFile(it).isFile }
    }

    fun record(id: String): AndroidClueRecord? {
        ensureLoaded()
        return synchronized(this) { storedRecords[id] }
    }

    /** Replaces stored records (binding refresh); returns the ones that changed. */
    fun update(changed: List<AndroidClueRecord>): List<AndroidClueRecord> {
        ensureLoaded()
        val applied: List<AndroidClueRecord>
        val prior: Map<String, AndroidClueRecord>
        val indexSnapshot: List<AndroidClueRecord>
        val indexRoot: File
        synchronized(this) {
            applied = changed.filter { storedRecords.containsKey(it.id) && storedRecords[it.id] != it }
            if (applied.isEmpty()) return emptyList()
            prior = applied.associate { it.id to storedRecords.getValue(it.id) }
            applied.forEach { storedRecords[it.id] = it }
            indexSnapshot = storedRecords.values.toList()
            indexRoot = root
        }
        try {
            persistIndexSnapshot(indexSnapshot, indexRoot)
        } catch (error: Exception) {
            synchronized(this) { prior.forEach { (id, record) -> storedRecords[id] = record } }
            throw error
        }
        return applied
    }

    fun pendingForMap(mapId: String, teamId: String): List<AndroidClueRecord> {
        ensureLoaded()
        return synchronized(this) {
            storedRecords.values.filter { it.publishToCaltopo && it.uploadState == "pending" &&
                mapId.isNotBlank() && teamId.isNotBlank() && it.mapKey == "map:$mapId" &&
                org.ncssar.rid2caltopo.data.CluePublicationIdentity.sameTeam(it.destinationTeamId, teamId) }
        }
    }

    /** Pending publishes that did not match the connected map, for the retry log. */
    fun pendingPublishSummary(): String {
        ensureLoaded()
        return synchronized(this) {
            storedRecords.values
                .filter { it.publishToCaltopo && (it.uploadState == "pending" || it.uploadState == "uploading") }
                .joinToString(limit = 8) { "${it.id.take(8)} map=${it.mapKey} team=${it.destinationTeamId} state=${it.uploadState}" }
                .ifEmpty { "none" }
        }
    }

    /**
     * Publish outcome. Permanent 4xx ([terminal]=true) keeps the clue as `failed` with
     * [error] surfaced; never deletes the index row or photo.
     */
    fun recordUploadResult(id: String, success: Boolean, error: String = "", terminal: Boolean = false) {
        ensureLoaded()
        val prior: AndroidClueRecord
        val indexSnapshot: List<AndroidClueRecord>
        val indexRoot: File
        synchronized(this) {
            prior = storedRecords[id] ?: return
            storedRecords[id] = prior.copy(
                uploadState = if (success) "published" else if (terminal) "failed" else "pending",
                lastUploadError = error,
            )
            indexSnapshot = storedRecords.values.toList()
            indexRoot = root
        }
        try {
            persistIndexSnapshot(indexSnapshot, indexRoot)
        } catch (failure: Exception) {
            synchronized(this) { storedRecords[id] = prior }
            throw failure
        }
    }

    /** Marks a pending clue as actively uploading (UI + Manage Storage visibility). */
    fun markUploading(id: String) {
        ensureLoaded()
        val prior: AndroidClueRecord
        val indexSnapshot: List<AndroidClueRecord>
        val indexRoot: File
        synchronized(this) {
            prior = storedRecords[id] ?: return
            if (!prior.publishToCaltopo || prior.uploadState == "published") return
            if (prior.uploadState == "uploading") return
            storedRecords[id] = prior.copy(uploadState = "uploading")
            indexSnapshot = storedRecords.values.toList()
            indexRoot = root
        }
        try {
            persistIndexSnapshot(indexSnapshot, indexRoot)
        } catch (failure: Exception) {
            synchronized(this) { storedRecords[id] = prior }
            throw failure
        }
    }

    /** Pending + failed publishable clues for the connected map (visibility / badges). */
    fun unpublishedForMap(mapId: String, teamId: String): List<AndroidClueRecord> {
        ensureLoaded()
        return synchronized(this) {
            storedRecords.values.filter {
                it.publishToCaltopo &&
                    (it.uploadState == "pending" || it.uploadState == "uploading" || it.uploadState == "failed") &&
                    mapId.isNotBlank() && teamId.isNotBlank() && it.mapKey == "map:$mapId" &&
                    org.ncssar.rid2caltopo.data.CluePublicationIdentity.sameTeam(it.destinationTeamId, teamId)
            }
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

    /** Load index without holding the store monitor across disk I/O. */
    private fun ensureLoaded() {
        if (loaded) return
        // Bound retries if Archive Dir / root flips during the unlocked read.
        repeat(3) {
            if (loaded) return
            val directory: File
            synchronized(this) {
                if (loaded) return
                selectRoot()
                directory = root
            }
            org.ncssar.rid2caltopo.storage.OperationalStorage.requireFor(directory)
            val prepared = LinkedHashMap<String, AndroidClueRecord>()
            val index = File(directory, "clues.json")
            if (index.exists()) {
                try {
                    val array = JSONArray(index.readText())
                    for (i in 0 until array.length()) {
                        val json = array.optJSONObject(i) ?: throw java.io.IOException("Invalid clue index entry")
                        val record = json.toAndroidClueRecord() ?: throw java.io.IOException("Unreadable clue index entry")
                        // A process kill mid-upload leaves "uploading"; treat as pending on reload.
                        val normalized = if (record.uploadState == "uploading") {
                            record.copy(uploadState = "pending")
                        } else record
                        // Missing media can be temporary. Never discard its retained metadata.
                        prepared[record.id] = normalized
                    }
                } catch (error: Exception) {
                    throw java.io.IOException("Clue index unavailable; original index and photos preserved", error)
                }
            }
            synchronized(this) {
                if (loaded) return
                selectRoot()
                if (root != directory) return@repeat
                storedRecords.clear()
                storedRecords.putAll(prepared)
                loaded = true
                return
            }
        }
        check(loaded) { "Clue index could not be loaded; Archive Dir changed repeatedly." }
    }

    private fun selectRoot() {
        val app = context ?: return
        val next = if (org.ncssar.rid2caltopo.data.CaltopoClient.GetArchiveUri() == null) {
            check(org.ncssar.rid2caltopo.data.CaltopoClient.HasArchiveDirForCurrentSession()) { "Choose Archive Dir before saving clues." }
            org.ncssar.rid2caltopo.storage.TemporaryStorage.directory(app, "clues")
        } else File(app.filesDir, "clues")
        if (next != root) { root = next; loaded = false }
    }

    /**
     * Atomic index write. Must not run while holding the store monitor — fsync can
     * block for seconds and stall clue upload / connectivity flush on the same lock.
     */
    private fun persistIndexSnapshot(snapshot: List<AndroidClueRecord>, directory: File) {
        directory.mkdirs()
        val array = JSONArray()
        snapshot.forEach { array.put(it.toJson()) }
        replaceFileAtomically(
            File(directory, "clues.json"),
            array.toString(2).toByteArray(Charsets.UTF_8),
        )
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

/**
 * Uploads the first pending clue for this map and account. Submit and retry both
 * identify that account with [org.ncssar.rid2caltopo.data.CluePublicationIdentity].
 */
internal fun publishFirstPendingClue(
    store: AndroidClueStore,
    mapId: String,
    teamId: String,
    folderId: String,
    jpegFor: (AndroidClueRecord) -> ByteArray,
    done: java.util.function.Consumer<org.ncssar.rid2caltopo.data.CaltopoOp>?,
): AndroidClueRecord? {
    val record = store.pendingForMap(mapId, teamId).firstOrNull() ?: return null
    org.ncssar.rid2caltopo.data.CaltopoSession.PublishStoredPhoto(
        mapId, teamId, record.id, record.lat, record.lng, record.title, record.publishedDescription,
        folderId, record.createdAtMs, jpegFor(record), done,
    )
    return record
}

internal fun clueShareReport(record: AndroidClueRecord, format: CoordinateDisplayFormat): String {
    val captured = java.time.Instant.ofEpochMilli(record.createdAtMs).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
    return "${record.title}\nAircraft: ${record.sourceDesignator}\nCaptured: $captured\nLocation: ${CoordinateFormatter.format(record.lat, record.lng, format)} (${format.label})\n\n${record.publishedDescription}"
}

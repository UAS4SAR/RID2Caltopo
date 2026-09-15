package org.ncssar.rid2caltopo.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
)

/**
 * Durable local-first clue storage, matching the Apple clue-store contract.
 *
 * Images and metadata are committed before a caller starts a CalTopo upload. The index is
 * replaced atomically so a process death cannot leave a partially-written JSON document.
 */
class AndroidClueStore private constructor(
    private val root: File,
    private val context: Context? = null,
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "clues"), context.applicationContext)

    private val indexFile = File(root, "clues.json")
    private val records = LinkedHashMap<String, AndroidClueRecord>()

    init {
        loadIndex()
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
        )
        val imageBytes = encodeJpeg(bitmap, 90)
        val thumbnailBytes = encodeJpeg(clueThumbnail(bitmap), 78)
        saveEncoded(record, imageBytes, thumbnailBytes)
        return record
    }

    @Synchronized
    internal fun saveEncoded(
        record: AndroidClueRecord,
        imageBytes: ByteArray,
        thumbnailBytes: ByteArray,
    ) {
        require(record.lat.isFinite() && record.lng.isFinite()) { "Clue location is invalid" }
        require(imageBytes.isNotEmpty()) { "Clue image is empty" }
        require(thumbnailBytes.isNotEmpty()) { "Clue thumbnail is empty" }
        context?.let { org.ncssar.rid2caltopo.app.FlightStorage.prepareWrite(it, imageBytes.size.toLong() + thumbnailBytes.size) }
        root.mkdirs()
        val imageFile = imageFile(record)
        val thumbnailFile = thumbnailFile(record)
        try {
            replaceFileAtomically(imageFile, imageBytes)
            replaceFileAtomically(thumbnailFile, thumbnailBytes)
            records[record.id] = record
            persistIndex()
        } catch (error: Exception) {
            records.remove(record.id)
            imageFile.delete()
            thumbnailFile.delete()
            throw error
        }
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val record = records.remove(id) ?: return false
        return try {
            persistIndex()
            imageFile(record).delete()
            thumbnailFile(record).delete()
            true
        } catch (error: Exception) {
            records[id] = record
            throw error
        }
    }

    fun imageFile(record: AndroidClueRecord): File = File(root, record.imageFilename)

    fun thumbnailFile(record: AndroidClueRecord): File = File(root, record.thumbnailFilename)

    fun loadThumbnail(record: AndroidClueRecord): Bitmap? =
        BitmapFactory.decodeFile(thumbnailFile(record).absolutePath)

    @Synchronized
    private fun loadIndex() {
        records.clear()
        val text = runCatching { indexFile.readText() }.getOrNull() ?: return
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val record = json.toAndroidClueRecord() ?: continue
            if (imageFile(record).isFile) records[record.id] = record
        }
    }

    @Synchronized
    private fun persistIndex() {
        records.entries.removeAll { !imageFile(it.value).isFile }
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
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.flush()
        }
        moveReplacing(temporary, target)
    }

    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        internal fun forDirectory(root: File): AndroidClueStore = AndroidClueStore(root)

        private fun clueThumbnail(bitmap: Bitmap): Bitmap {
            val maxSide = 180
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
    ).takeIf { it.lat.isFinite() && it.lng.isFinite() }
}

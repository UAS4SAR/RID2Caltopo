package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Byte-preserving mission intake. Filenames supplied by a browser never become storage paths. */
class MappingPhotoStore(val directory: File) {
    init { org.ncssar.rid2caltopo.storage.OperationalStorage.requireFor(directory) }
    private val originals = File(directory, "originals").apply { mkdirs() }
    private val pending = File(directory, "pending").apply { mkdirs() }
    private val records = File(directory, "inventory").apply { mkdirs() }

    @Synchronized fun register(name: String, size: Long, digest: String): JSONObject {
        require(name.length in 1..180 && name.none { it == '/' || it == '\\' || it.code < 32 } &&
            name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }) { "Choose original JPEG photos." }
        require(size in 3..MAX_PHOTO_BYTES) { "Photo must be between 3 bytes and 128 MiB." }
        checkDigest(digest)
        val recordFile = File(records, "$digest.json")
        if (recordFile.exists()) {
            val existing = readRecord(digest)
            require(existing.getLong("sizeBytes") == size) { "Photo identity conflicts with an existing transfer." }
            return progress(digest)
        }
        require(records.listFiles().orEmpty().size < 10000) { "This mission has reached its photo limit." }
        // Reserve enough room for all previously registered files and this new one.
        val reserved = records.listFiles().orEmpty().sumOf {
            val record = JSONObject(it.readText())
            if (record.optBoolean("complete")) 0L else (record.getLong("sizeBytes") - File(pending, "${record.getString("sha256")}.part").length()).coerceAtLeast(0)
        }
        require(pending.usableSpace - reserved - size >= FREE_SPACE_RESERVE) { "Not enough free tablet storage for these originals." }
        writeJson(recordFile, JSONObject().put("name", name).put("sizeBytes", size).put("sha256", digest).put("complete", false))
        return progress(digest)
    }

    @Synchronized fun progress(digest: String): JSONObject {
        val record = readRecord(digest)
        val complete = record.optBoolean("complete") && File(originals, "$digest.jpg").length() == record.getLong("sizeBytes")
        return JSONObject(record.toString()).put("complete", complete)
            .put("offset", if (complete) record.getLong("sizeBytes") else File(pending, "$digest.part").length())
    }

    @Synchronized fun append(digest: String, offset: Long, bytes: ByteArray): JSONObject {
        val record = readRecord(digest)
        require(!record.optBoolean("complete")) { "This photo is already stored." }
        val file = File(pending, "$digest.part")
        require(offset == file.length() && offset >= 0) { "Upload position changed. Select the files again to resume." }
        require(bytes.isNotEmpty() && bytes.size <= CHUNK_BYTES && offset + bytes.size <= record.getLong("sizeBytes")) { "Unsupported photo chunk." }
        require(pending.usableSpace - bytes.size >= FREE_SPACE_RESERVE) { "Tablet storage is full. Originals already received are retained." }
        MappingMissionStorage.invalidate(file)
        try { RandomAccessFile(file, "rw").use { it.seek(offset); it.write(bytes); it.fd.sync() } }
        finally { MappingMissionStorage.invalidate(file) }
        org.ncssar.rid2caltopo.storage.OperationalStorage.retainChunk(file, offset, bytes)
        return progress(digest)
    }

    @Synchronized fun commit(digest: String): JSONObject {
        val record = readRecord(digest)
        if (record.optBoolean("complete")) return progress(digest)
        val retained = File(originals, "$digest.jpg")
        val pendingFile = File(pending, "$digest.part")
        val file = if (pendingFile.exists()) pendingFile else retained
        require(file.length() == record.getLong("sizeBytes")) { "Photo is incomplete. Select the files again to resume." }
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            if (stream.read() != 0xff || stream.read() != 0xd8) {
                file.delete()
                throw IllegalArgumentException("File is not an original JPEG. Choose the flight's original JPEGs.")
            }
        }
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual != digest) {
            file.delete()
            throw IllegalArgumentException("Photo checksum failed. Select it again to retry from the beginning.")
        }
        require(file == retained || file.renameTo(retained)) { "Could not retain the original photo." }
        org.ncssar.rid2caltopo.storage.OperationalStorage.retain(File(originals, "$digest.jpg"))
        org.ncssar.rid2caltopo.storage.OperationalStorage.forgetPending(pendingFile)
        writeJson(File(records, "$digest.json"), record.put("complete", true))
        return progress(digest)
    }

    @Synchronized fun inventory(): JSONObject {
        val photos = records.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { progress(it.nameWithoutExtension) }
        val completed = photos.filter { it.optBoolean("complete") }
        return JSONObject().put("photoCount", completed.size).put("storedBytes", completed.sumOf { it.getLong("sizeBytes") })
            .put("pendingCount", photos.size - completed.size).put("freeBytes", pending.usableSpace)
    }

    @Synchronized fun finish(digests: List<String>): JSONObject {
        require(digests.isNotEmpty() && digests.size <= 10000) { "Select the flight's original photos first." }
        digests.distinct().forEach { require(progress(it).getBoolean("complete")) { "Some selected originals are still incomplete." } }
        writeJson(File(directory, "received-batch.json"), JSONObject().put("sha256", JSONArray(digests.distinct()))
            .put("completedAt", java.time.Instant.now().toString()))
        return inventory()
    }

    @Synchronized fun importOriginal(name: String, source: File, digest: String): JSONObject {
        val current = register(name, source.length(), digest)
        if (current.getBoolean("complete")) return current
        val hash = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            require(input.read() == 255 && input.read() == 216) { "Only original JPEGs are accepted." }
        }
        source.inputStream().use { input -> val buffer=ByteArray(65536); while(true){val count=input.read(buffer);if(count<0)break;hash.update(buffer,0,count)} }
        require(hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)} == digest) { "Stored original checksum changed." }
        val destination=File(originals,"$digest.jpg")
        if (!destination.exists()) runCatching { java.nio.file.Files.createLink(destination.toPath(),source.toPath()) }
            .getOrElse { source.copyTo(destination,overwrite=false,bufferSize=1024*1024) }
        File(pending,"$digest.part").delete()
        org.ncssar.rid2caltopo.storage.OperationalStorage.retain(destination)
        writeJson(File(records,"$digest.json"),readRecord(digest).put("complete",true))
        return progress(digest)
    }

    private fun readRecord(digest: String): JSONObject {
        checkDigest(digest)
        val file = File(records, "$digest.json")
        require(file.isFile) { "Register this photo before uploading it." }
        return JSONObject(file.readText())
    }

    companion object {
        const val CHUNK_BYTES = 1024 * 1024
        const val MAX_PHOTO_BYTES = 128L * 1024 * 1024
        const val FREE_SPACE_RESERVE = 256L * 1024 * 1024
        fun checkDigest(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) { "Invalid photo checksum." } }
        fun writeJson(file: File, value: JSONObject) {
            org.ncssar.rid2caltopo.storage.OperationalStorage.requireFor(file)
            MappingMissionStorage.invalidate(file)
            val temporary = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temporary).use { it.write(value.toString(2).toByteArray()); it.fd.sync() }
            check(temporary.renameTo(file)) { "Could not save the mission inventory." }
            org.ncssar.rid2caltopo.storage.OperationalStorage.retain(file)
            MappingMissionStorage.invalidate(file)
        }
    }
}

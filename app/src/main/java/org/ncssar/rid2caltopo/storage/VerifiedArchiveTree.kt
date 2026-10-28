package org.ncssar.rid2caltopo.storage

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Small interface shared by the Android document-provider adapter and fault-injection tests. */
internal interface ArchiveTree {
    data class Entry(val name: String, val directory: Boolean, val bytes: Long, val modified: Long,
                     val mimeType: String? = null)
    fun list(path: String): List<Entry>
    fun exists(path: String): Boolean
    fun read(path: String): InputStream
    fun write(path: String, input: InputStream)
    fun mkdirs(path: String)
    fun rename(from: String, to: String): Boolean
    fun delete(path: String)
    fun refresh() {}
}

/** Worker-only. Never removes the previous durable bytes until the replacement is verified. */
internal class VerifiedArchiveTree(private val tree: ArchiveTree, private val journalDirectory: String = JOURNAL,
    private val cancelled: () -> Unit = {}) {
    init { checked(journalDirectory) }
    companion object {
        const val JOURNAL = "application-logs/storage-recovery"
        fun checked(path: String): String {
            require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it.isBlank() || it == "." || it == ".." })
            return path
        }
        fun digest(input: InputStream, check: () -> Unit = {}): String {
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            while (true) { check(); val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun json(input: InputStream, maximum: Int = 8 * 1024 * 1024): JSONObject {
            val bytes = bytes(input, maximum)
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
        fun bytes(input: InputStream, maximum: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                check(output.size().toLong() + count <= maximum) { "Archive metadata exceeds its supported size." }
                output.write(buffer, 0, count)
            }
        }
    }
    private fun hash(path: String): String = tree.read(path).use { digest(it, cancelled) }
    private fun copy(from: String, to: String) { tree.read(from).use { tree.write(to, it) } }

    @Synchronized fun put(source: File, destination: String, replace: Boolean = true, modified: Long? = null) {
        checked(destination); cancelled()
        recover()
        require(source.isFile) { "Retained source file is unavailable: ${source.name}" }
        val expected = source.inputStream().use { digest(it, cancelled) }
        val matches = tree.exists(destination) && hash(destination) == expected
        if (matches) {
            if (modified == null || metadataMatches(destination, modified, expected)) return
        } else if (tree.exists(destination)) {
            check(replace) { "Conflicting archive file: $destination. Original files were retained." }
        }
        val id = UUID.randomUUID().toString()
        val stage = "$destination.r2c-stage-$id"
        val backup = "$destination.r2c-previous-$id"
        if (!matches) {
            source.inputStream().use { tree.write(stage, it) }
            check(hash(stage) == expected && source.inputStream().use { digest(it, cancelled) } == expected) {
                "File changed or archive verification failed: ${source.name}. Original files were retained."
            }
        }
        val oldHash = if (tree.exists(destination)) hash(destination) else ""
        val record = JSONObject().put("path", destination).put("stage", stage).put("backup", backup)
            .put("sha256", expected).put("previousSha256", oldHash)
        if (modified != null) record.put("modified", modified)
        val journal = "$journalDirectory/$id.json"
        val bytes = record.toString().toByteArray()
        tree.write(journal, bytes.inputStream())
        check(tree.read(journal).use { it.readBytes().contentEquals(bytes) }) { "Archive journal could not be verified." }
        finish(record)
        tree.delete(journal)
    }

    /** Recover before any archived file is hydrated or a source migration copy is removed. */
    @Synchronized fun recover() {
        for (entry in tree.list(journalDirectory).filter { !it.directory && it.name.endsWith(".json") }) {
            cancelled()
            val path = "$journalDirectory/${entry.name}"
            val record = try { tree.read(path).use { json(it, 16 * 1024) } }
            catch (_: org.json.JSONException) {
                val quarantined = path.removeSuffix(".json") + ".invalid"
                if (!tree.rename(path, quarantined)) { copy(path, quarantined); check(hash(path) == hash(quarantined)); tree.delete(path) }
                continue
            }
            finish(record); tree.delete(path)
        }
    }

    private fun finish(record: JSONObject) {
        val target = checked(record.getString("path"))
        val stage = checked(record.getString("stage"))
        val backup = checked(record.getString("backup"))
        val expected = record.getString("sha256")
        val previous = record.getString("previousSha256")
        require(stage.startsWith("$target.r2c-stage-") && backup.startsWith("$target.r2c-previous-"))
        if (tree.exists(target) && hash(target) == expected) {
            publishMetadata(record, target, expected)
            if (tree.exists(stage)) tree.delete(stage)
            if (tree.exists(backup)) tree.delete(backup)
            return
        }
        check(tree.exists(stage) && hash(stage) == expected) { "Archive recovery requires its verified staging file; nothing was removed." }
        if (tree.exists(target)) {
            val currentHash = hash(target)
            if (currentHash != previous) {
                check(previous.isBlank() || (tree.exists(backup) && hash(backup) == previous)) {
                    "Archive changed during recovery; original copies were retained."
                }
                // A provider can die during its non-atomic final copy. Keep the
                // unexpected bytes as recovery data before retrying publication.
                val interrupted = "$target.r2c-interrupted-${UUID.randomUUID()}"
                copy(target, interrupted)
                check(hash(interrupted) == currentHash)
                tree.delete(target)
            }
            if (previous.isNotBlank() && !tree.exists(backup)) {
                if (!tree.rename(target, backup)) copy(target, backup)
            }
            if (previous.isNotBlank()) check(hash(backup) == previous) { "Previous archive copy could not be verified." }
        }
        // Providers need not implement atomic rename. The recovery record and verified
        // previous copy remain until the visible target has been checked byte-for-byte.
        if (!tree.rename(stage, target)) copy(stage, target)
        check(hash(target) == expected) { "Archive replacement verification failed; recovery copies were retained." }
        publishMetadata(record, target, expected)
        if (tree.exists(stage)) tree.delete(stage)
        if (tree.exists(backup)) tree.delete(backup)
    }

    private fun metadataBytes(modified: Long, sha256: String): ByteArray = JSONObject()
        .put("modified", modified).put("sha256", sha256).toString().toByteArray()

    private fun metadataMatches(target: String, modified: Long, sha256: String): Boolean {
        val path = "$target.r2c-info"
        if (!tree.exists(path)) return false
        val expected = metadataBytes(modified, sha256)
        // A torn metadata file is reconstructed from the still-durable data journal.
        return runCatching { tree.read(path).use { bytes(it, 16 * 1024).contentEquals(expected) } }.getOrDefault(false)
    }

    private fun publishMetadata(record: JSONObject, target: String, expected: String) {
        if (!record.has("modified")) return // Older recovery journals remain supported.
        val modified = record.getLong("modified")
        if (metadataMatches(target, modified, expected)) return
        val path = "$target.r2c-info"
        val bytes = metadataBytes(modified, expected)
        // The verified journal already holds everything needed to reconstruct this
        // derived file. Keep that journal and previous data until metadata verifies;
        // a second staging/backup/journal transaction is unnecessary.
        tree.write(path, bytes.inputStream())
        check(tree.read(path).use { Companion.bytes(it, 16 * 1024).contentEquals(bytes) }) {
            "Archive metadata could not be verified; recovery copies were retained."
        }
    }
}

package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Crash-safe replacement of flight archive files (GeoJSON, KMZ). Apple mirrors this in
// OperationalSafeFileWriter.swift. A crash leaves either the old file or the new one, never a torn
// file, and the launch sweep finishes or rolls back anything left behind.

object FlightArchiveFiles {
    const val PARTIAL_SUFFIX = ".partial"
    const val BACKUP_SUFFIX = ".bak"

    /** Default validator by extension: KMZ must be a readable zip with doc.kml; JSON must parse. */
    fun validatorFor(name: String): (ByteArray) -> Boolean {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".kmz") -> FlightKmz::isValidArchive
            lower.endsWith(".json") || lower.endsWith(".geojson") -> { bytes ->
                bytes.isNotEmpty() && runCatching { JSONTokener(String(bytes, Charsets.UTF_8)).nextValue(); true }.getOrDefault(false)
            }
            else -> { bytes -> bytes.isNotEmpty() }
        }
    }
}

/** Plain files (app storage, the session's temporary archive folder): `.partial` + fsync + verify + atomic move. */
object PlainFileSafeWriter {
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun replace(target: File, bytes: ByteArray, validator: (ByteArray) -> Boolean = FlightArchiveFiles.validatorFor(target.name)) {
        if (!validator(bytes)) throw IOException("verification failed before write: ${target.name}")
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + FlightArchiveFiles.PARTIAL_SUFFIX)
        try {
            FileOutputStream(partial).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            val readBack = partial.readBytes()
            if (!readBack.contentEquals(bytes) || !validator(readBack)) throw IOException("verification failed: ${partial.name}")
            move(partial, target)
        } catch (error: Exception) {
            partial.delete()
            throw error as? IOException ?: IOException(error)
        }
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Finishes verified `.partial` files and removes torn ones in [directory] and its immediate subdirectories. */
    @JvmStatic
    fun sweep(directory: File): List<String> {
        val actions = mutableListOf<String>()
        val folders = listOf(directory) + (directory.listFiles()?.filter { it.isDirectory } ?: emptyList())
        for (folder in folders) {
            folder.listFiles()?.filter { it.isFile && it.name.endsWith(FlightArchiveFiles.PARTIAL_SUFFIX) }?.forEach { partial ->
                val target = File(folder, partial.name.removeSuffix(FlightArchiveFiles.PARTIAL_SUFFIX))
                val relative = if (folder == directory) partial.name else "${folder.name}/${partial.name}"
                val data = runCatching { partial.readBytes() }.getOrNull()
                if (data != null && FlightArchiveFiles.validatorFor(target.name)(data) && runCatching { move(partial, target) }.isSuccess) {
                    actions += "completed $relative"
                } else {
                    partial.delete()
                    actions += "discarded $relative"
                }
            }
        }
        return actions
    }
}

/** One folder of the operator archive tree (a SAF day folder in production, a fake in tests). */
interface ArchiveFolder {
    fun exists(name: String): Boolean
    fun read(name: String): ByteArray?
    /** Creates [name] with exactly that display name if needed, truncates it, writes and syncs. */
    @Throws(IOException::class)
    fun write(name: String, mimeType: String, bytes: ByteArray)
    fun rename(from: String, to: String): Boolean
    fun delete(name: String): Boolean
}

/** Durable list of SAF replacements in progress, kept in app storage so the launch sweep can finish them. */
class SafRewriteJournal(private val file: File) {
    data class Entry(val dayDirectory: String, val name: String)

    @Synchronized
    fun entries(): List<Entry> {
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { Entry(it.optString("day"), it.optString("name")) }
                ?.takeIf { it.dayDirectory.isNotEmpty() && it.name.isNotEmpty() }
        }
    }

    @Synchronized
    fun add(entry: Entry) {
        val current = entries()
        if (entry in current) return
        save(current + entry)
    }

    @Synchronized
    fun remove(entry: Entry) {
        save(entries().filterNot { it == entry })
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("day", it.dayDirectory).put("name", it.name)) }
        PlainFileSafeWriter.replace(file, array.toString().toByteArray(Charsets.UTF_8))
    }
}

/**
 * SAF folders cannot rename over an existing document, so a replace is: journal, write
 * `<name>.partial`, verify it, rename the old file to `<name>.bak`, rename the partial into place,
 * delete the backup, clear the journal. [recover] finishes or rolls back any interrupted step.
 */
object SafArchiveWriter {
    enum class Recovery { COMPLETED, KEPT, ROLLED_BACK, NOTHING }

    @Throws(IOException::class)
    fun replace(
        folder: ArchiveFolder,
        dayDirectory: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
        journal: SafRewriteJournal,
        validator: (ByteArray) -> Boolean = FlightArchiveFiles.validatorFor(name),
    ) {
        if (!validator(bytes)) throw IOException("verification failed before write: $name")
        val entry = SafRewriteJournal.Entry(dayDirectory, name)
        val partial = name + FlightArchiveFiles.PARTIAL_SUFFIX
        journal.add(entry)
        try {
            if (folder.exists(partial)) folder.delete(partial)
            folder.write(partial, "application/octet-stream", bytes)
            val readBack = folder.read(partial)
            if (readBack == null || !readBack.contentEquals(bytes) || !validator(readBack)) {
                folder.delete(partial)
                throw IOException("verification failed: $partial")
            }
            commit(folder, name)
        } catch (error: Exception) {
            // A failed replace leaves the old file. If that cannot be confirmed, the journal entry
            // stays so the launch sweep finishes the job.
            if (runCatching { rollBack(folder, name) }.getOrDefault(false)) journal.remove(entry)
            throw error as? IOException ?: IOException(error)
        }
        journal.remove(entry)
    }

    /** Restores the old file after a failed replace; true when no partial or backup remains. */
    private fun rollBack(folder: ArchiveFolder, name: String): Boolean {
        val partial = name + FlightArchiveFiles.PARTIAL_SUFFIX
        val backup = name + FlightArchiveFiles.BACKUP_SUFFIX
        if (!folder.exists(name) && folder.exists(backup)) folder.rename(backup, name)
        if (folder.exists(partial)) folder.delete(partial)
        if (folder.exists(name) && folder.exists(backup)) folder.delete(backup)
        return !folder.exists(partial) && !folder.exists(backup)
    }

    private fun commit(folder: ArchiveFolder, name: String) {
        val partial = name + FlightArchiveFiles.PARTIAL_SUFFIX
        val backup = name + FlightArchiveFiles.BACKUP_SUFFIX
        if (folder.exists(name)) {
            // A backup next to a live file is stale; one without a live file is the only old copy.
            if (folder.exists(backup) && !folder.delete(backup)) throw IOException("could not clear $backup")
            if (!folder.rename(name, backup)) throw IOException("could not move $name aside")
        }
        if (!folder.rename(partial, name)) {
            if (folder.exists(backup) && !folder.exists(name)) folder.rename(backup, name)
            throw IOException("could not rename $partial")
        }
        if (folder.exists(backup)) folder.delete(backup)
    }

    /** Brings one interrupted replacement to a consistent state. */
    fun recover(folder: ArchiveFolder, name: String, validator: (ByteArray) -> Boolean = FlightArchiveFiles.validatorFor(name)): Recovery {
        val partial = name + FlightArchiveFiles.PARTIAL_SUFFIX
        val backup = name + FlightArchiveFiles.BACKUP_SUFFIX
        val partialValid = folder.read(partial)?.let(validator) == true
        if (partialValid) {
            if (folder.exists(name) && !(folder.read(name)?.let(validator) == true)) folder.delete(name)
            return runCatching { commit(folder, name); Recovery.COMPLETED }.getOrElse { Recovery.NOTHING }
        }
        if (folder.exists(partial)) folder.delete(partial)
        if (folder.read(name)?.let(validator) == true) {
            if (folder.exists(backup)) folder.delete(backup)
            return Recovery.KEPT
        }
        if (folder.read(backup)?.let(validator) == true) {
            if (folder.exists(name)) folder.delete(name)
            return if (folder.rename(backup, name)) Recovery.ROLLED_BACK else Recovery.NOTHING
        }
        return Recovery.NOTHING
    }

    /** Launch sweep: recovers every journaled replacement whose folder can be resolved. */
    fun sweep(journal: SafRewriteJournal, resolve: (String) -> ArchiveFolder?): List<String> {
        val actions = mutableListOf<String>()
        for (entry in journal.entries()) {
            val folder = resolve(entry.dayDirectory)
            if (folder == null) {
                actions += "unresolved ${entry.dayDirectory}/${entry.name}"
                continue
            }
            val result = runCatching { recover(folder, entry.name) }.getOrDefault(Recovery.NOTHING)
            actions += "${result.name.lowercase()} ${entry.dayDirectory}/${entry.name}"
            journal.remove(entry)
        }
        return actions
    }
}

/** A durable request to rebuild one flight's KMZ from its archived GeoJSON and every clue it owns. */
data class FlightArchiveRewriteJob(
    val aircraftId: String,
    val dayDirectory: String,
    val geoJsonFilename: String,
    val kmzFilename: String,
    val attempts: Int = 0,
    val lastError: String? = null,
) {
    val id: String get() = "$dayDirectory/$geoJsonFilename"

    fun toJson(): JSONObject = JSONObject()
        .put("aircraftID", aircraftId)
        .put("dayDirectory", dayDirectory)
        .put("geoJSONFilename", geoJsonFilename)
        .put("kmzFilename", kmzFilename)
        .put("attempts", attempts)
        .put("lastError", lastError ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): FlightArchiveRewriteJob? {
            val day = json.optString("dayDirectory").takeIf { it.isNotEmpty() } ?: return null
            val geo = json.optString("geoJSONFilename").takeIf { it.isNotEmpty() } ?: return null
            return FlightArchiveRewriteJob(json.optString("aircraftID"), day, geo, json.optString("kmzFilename"),
                json.optInt("attempts"), json.optStringOrNull("lastError"))
        }
    }
}

/** Persistent queue of KMZ rewrites; survives process death and is retried at launch. */
class FlightArchiveRewriteQueue(private val file: File) {
    @Synchronized
    fun jobs(): List<FlightArchiveRewriteJob> {
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(FlightArchiveRewriteJob::fromJson) }
    }

    @Synchronized
    fun enqueue(job: FlightArchiveRewriteJob) {
        val current = jobs()
        if (current.any { it.id == job.id }) return
        save(current + job)
    }

    @Synchronized
    fun complete(id: String) = save(jobs().filterNot { it.id == id })

    @Synchronized
    fun recordFailure(id: String, error: String) =
        save(jobs().map { if (it.id == id) it.copy(attempts = it.attempts + 1, lastError = error) else it })

    private fun save(jobs: List<FlightArchiveRewriteJob>) {
        val array = JSONArray()
        jobs.forEach { array.put(it.toJson()) }
        PlainFileSafeWriter.replace(file, array.toString().toByteArray(Charsets.UTF_8))
    }
}

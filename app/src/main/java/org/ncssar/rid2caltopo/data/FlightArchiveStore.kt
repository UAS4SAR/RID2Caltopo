package org.ncssar.rid2caltopo.data

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.ncssar.rid2caltopo.app.R2CApplication
import org.ncssar.rid2caltopo.video.AndroidClueRecord
import org.ncssar.rid2caltopo.video.AndroidClueStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SAF day folder adapter for [SafArchiveWriter]. */
class DocumentFileArchiveFolder(private val context: Context, private val dir: DocumentFile) : ArchiveFolder {
    private fun find(name: String): DocumentFile? = dir.findFile(name)?.takeIf { it.isFile }

    override fun exists(name: String): Boolean = find(name) != null

    override fun read(name: String): ByteArray? = find(name)?.let { file ->
        runCatching { context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } }.getOrNull()
    }

    override fun write(name: String, mimeType: String, bytes: ByteArray) {
        val file = find(name) ?: DocumentFileCompat.createFileWithExactName(dir, mimeType, name)
            ?: throw IOException("could not create $name")
        if (file.name != name) {
            file.delete()
            throw IOException("provider renamed $name to ${file.name}")
        }
        val output = context.contentResolver.openOutputStream(file.uri, "wt") ?: throw IOException("could not open $name")
        output.use {
            it.write(bytes)
            it.flush()
            (it as? FileOutputStream)?.fd?.sync()
        }
    }

    override fun rename(from: String, to: String): Boolean {
        val file = find(from) ?: return false
        return file.renameTo(to) && find(to) != null
    }

    override fun delete(name: String): Boolean = find(name)?.delete() ?: true
}

/**
 * Writes flight archives (GeoJSON + backup KMZ) crash-safely and keeps each flight's KMZ in step with
 * its clues: written for every archived flight, rewritten through a durable queue when a clue for an
 * already-archived flight is saved, changed or deleted, and swept at launch.
 */
object FlightArchiveStore {
    private const val TAG = "FlightArchiveStore"
    const val GEOJSON_MIME = "application/geo+json"
    const val KMZ_MIME = "application/vnd.google-earth.kmz"
    @Volatile private var swept = false
    private val lock = Any()

    private fun context(): Context? = R2CApplication.getAppCtxt()
    private fun journal(context: Context) = SafRewriteJournal(File(context.filesDir, "flight-archive-journal.json"))
    @JvmStatic fun rewriteQueue(context: Context) = FlightArchiveRewriteQueue(File(context.filesDir, "flight-kmz-rewrites.json"))

    private fun plainDirectory(dir: DocumentFile): File? =
        dir.uri.takeIf { it.scheme == "file" }?.path?.let { File(it) }

    /** Crash-safe replace of one file in an archive day folder (plain or SAF). */
    @JvmStatic
    fun writeFile(dir: DocumentFile, name: String, mimeType: String, bytes: ByteArray): Boolean {
        val context = context() ?: return false
        return try {
            org.ncssar.rid2caltopo.app.FlightStorage.prepareWrite(context, bytes.size.toLong())
            val plain = plainDirectory(dir)
            if (plain != null) {
                PlainFileSafeWriter.replace(File(plain, name), bytes)
            } else {
                SafArchiveWriter.replace(DocumentFileArchiveFolder(context, dir), dir.name.orEmpty(), name, mimeType, bytes, journal(context))
            }
            dir.findFile(name)?.let { org.ncssar.rid2caltopo.app.FlightStorage.documentChanged(context, it) }
            true
        } catch (error: Exception) {
            CaltopoClient.CTError(TAG, "writeFile($name) failed; the previous file is unchanged", error)
            false
        }
    }

    private fun read(dir: DocumentFile, name: String): ByteArray? {
        val context = context() ?: return null
        plainDirectory(dir)?.let { return File(it, name).takeIf { file -> file.isFile }?.readBytes() }
        return DocumentFileArchiveFolder(context, dir).read(name)
    }

    /**
     * Archives a finished flight: GeoJSON first, then its KMZ with every clue and local marker it owns.
     * Clue bindings were fixed at Submit and are not changed here. A KMZ failure is queued for a
     * retry; returns whether the GeoJSON was written.
     */
    @JvmStatic
    fun writeFlight(
        dir: DocumentFile,
        geoJsonName: String,
        geoJson: String,
        title: String,
        aircraftId: String,
        flightId: String,
        points: List<ClueBindingPoint>,
    ): Boolean {
        val context = context() ?: return false
        if (!writeFile(dir, geoJsonName, GEOJSON_MIME, geoJson.toByteArray(Charsets.UTF_8))) return false
        val day = dir.name.orEmpty()
        val kmzName = FlightArchiveRebuild.kmzName(geoJsonName)
        try {
            val store = AndroidClueStore.shared(context)
            val designator = AwaitingMapClueMatch.designatorOfLabel(title)
            val self = AwaitingMapClueMatch.Candidate(flightId, designator, points.map { it.timeMs })
            val others = AwaitingMapFlights.allEntries().filter { it.optString("id") != flightId }
                .map { AwaitingMapClueMatch.Candidate.of(it) } +
                archivesAround(listOf(day)).filterKeys { it != flightId && !it.endsWith("/$geoJsonName") }
                    .map { (id, entry) -> entry.contents.candidate(id) }
            val clues = AwaitingMapClueMatch.ownedClues(store.allRecords(), flightId, listOf(self) + others,
                { it.sourceDesignator }, { it.ownershipTimeMs }, { it.binding?.flightId }).sortedBy { it.ownershipTimeMs }
            val kmz = FlightKmz.archive(title, points.map { FlightKmzPoint(it.latitude, it.longitude, it.altitudeMeters) },
                clues.map { FlightArchiveRebuild.kmzClue(it, jpeg(store, it)) })
            if (!writeFile(dir, kmzName, KMZ_MIME, kmz)) throw IOException("KMZ write failed")
            CaltopoClient.CTDebug(TAG, "writeFlight(): $day/$kmzName with ${clues.size} clue(s)")
        } catch (error: Exception) {
            CaltopoClient.CTError(TAG, "writeFlight(): KMZ for $day/$geoJsonName queued for retry", error)
            runCatching { rewriteQueue(context).enqueue(FlightArchiveRewriteJob(aircraftId, day, geoJsonName, kmzName)) }
        }
        return true
    }

    private fun jpeg(store: AndroidClueStore, record: AndroidClueRecord): ByteArray? =
        runCatching { store.imageFile(record).takeIf { it.isFile }?.readBytes() }.getOrNull()

    data class ArchiveEntry(val dayDirectory: String, val fileName: String, val contents: FlightArchiveContents)

    private fun dayName(timeMs: Long): String =
        "tracks-" + SimpleDateFormat("ddMMMyyyy", Locale.US).format(Date(timeMs))

    private fun dayFolder(day: String): DocumentFile? =
        CaltopoClient.GetArchiveDir()?.findFile(day)?.takeIf { it.isDirectory }

    /** Archived flights in [days], keyed by their flight id (r2c_flight_id) or "day/file". */
    private fun archivesAround(days: List<String>): Map<String, ArchiveEntry> {
        val result = LinkedHashMap<String, ArchiveEntry>()
        for (day in days.distinct()) {
            val dir = dayFolder(day) ?: continue
            for (file in dir.listFiles()) {
                val name = file.name ?: continue
                if (!file.isFile || !name.endsWith(".json")) continue
                val bytes = read(dir, name) ?: continue
                val contents = FlightArchiveRebuild.decode(bytes) ?: continue
                if (contents.points.isEmpty()) continue
                val id = FlightArchiveRebuild.flightId(bytes) ?: "$day/$name"
                result[id] = ArchiveEntry(day, name, contents)
            }
        }
        return result
    }

    private fun daysAround(timeMs: Long): List<String> =
        listOf(timeMs - 86_400_000L, timeMs, timeMs + 86_400_000L).map(::dayName)

    /**
     * Queues a KMZ rewrite when [record] belongs to a flight that has already been archived (a clue
     * submitted, changed or deleted after its flight ended). Returns true when a job was queued.
     */
    @JvmStatic
    fun enqueueRewrite(record: AndroidClueRecord): Boolean {
        val context = context() ?: return false
        val flightId = record.binding?.flightId
        if (flightId != null && WaypointTrack.IsLiveFlight(flightId)) return false
        val archives = archivesAround(daysAround(record.ownershipTimeMs))
        val owner = AwaitingMapClueMatch.ownerId(record.sourceDesignator, record.ownershipTimeMs, flightId,
            archives.map { (id, entry) -> entry.contents.candidate(id) }) ?: return false
        val entry = archives[owner] ?: return false
        rewriteQueue(context).enqueue(FlightArchiveRewriteJob(entry.contents.remoteId, entry.dayDirectory, entry.fileName,
            FlightArchiveRebuild.kmzName(entry.fileName)))
        CaltopoClient.CTDebug(TAG, "enqueueRewrite(): ${entry.dayDirectory}/${entry.fileName} for clue ${record.id}")
        return true
    }

    /** Runs every queued KMZ rewrite; returns the clue records whose binding changed (fallback binding). */
    @JvmStatic
    fun processRewrites(): List<AndroidClueRecord> = synchronized(lock) {
        val context = context() ?: return emptyList()
        val queue = rewriteQueue(context)
        val store = AndroidClueStore.shared(context)
        val changedRecords = mutableListOf<AndroidClueRecord>()
        for (job in queue.jobs()) {
            try {
                val dir = dayFolder(job.dayDirectory) ?: throw IOException("day folder ${job.dayDirectory} unavailable")
                val bytes = read(dir, job.geoJsonFilename)
                if (bytes == null) {
                    queue.complete(job.id)
                    continue
                }
                val contents = FlightArchiveRebuild.decode(bytes)
                if (contents == null) {
                    queue.complete(job.id)
                    continue
                }
                val selfId = FlightArchiveRebuild.flightId(bytes) ?: job.id
                val days = listOf(job.dayDirectory) + contents.points.firstOrNull()?.let { daysAround(it.timeMs) }.orEmpty()
                val archives = archivesAround(days).filterKeys { it != selfId } + (selfId to ArchiveEntry(job.dayDirectory, job.geoJsonFilename, contents))
                val owned = FlightArchiveRebuild.ownedClues(selfId,
                    archives.mapValues { it.value.contents }, store.allRecords())
                val bound = owned.map { FlightArchiveRebuild.bindingFallback(it, contents) }
                changedRecords += store.update(bound)
                val kmz = FlightKmz.archive(contents.title, contents.kmzPoints, bound.map { FlightArchiveRebuild.kmzClue(it, jpeg(store, it)) })
                if (!writeFile(dir, job.kmzFilename, KMZ_MIME, kmz)) throw IOException("KMZ write failed")
                queue.complete(job.id)
                CaltopoClient.CTDebug(TAG, "processRewrites(): rewrote ${job.dayDirectory}/${job.kmzFilename} with ${bound.size} clue(s)")
            } catch (error: Exception) {
                CaltopoClient.CTWarn(TAG, "processRewrites(): ${job.id} will be retried", error)
                runCatching { queue.recordFailure(job.id, error.message ?: error.javaClass.simpleName) }
            }
        }
        changedRecords
    }

    /** Once per launch: finish or roll back interrupted replacements, then retry queued rewrites. */
    @JvmStatic
    fun launchSweep(): List<AndroidClueRecord> {
        val context = context() ?: return emptyList()
        if (!swept) {
            swept = true
            runCatching {
                val archive = CaltopoClient.GetArchiveDir()
                val plain = archive?.let { plainDirectory(it) }
                val actions = if (plain != null) PlainFileSafeWriter.sweep(plain) else emptyList()
                val safActions = SafArchiveWriter.sweep(journal(context)) { day ->
                    dayFolder(day)?.let { DocumentFileArchiveFolder(context, it) }
                }
                if (actions.isNotEmpty() || safActions.isNotEmpty()) {
                    CaltopoClient.CTInfo(TAG, "launchSweep(): ${(actions + safActions).joinToString(", ")}")
                }
            }.onFailure { CaltopoClient.CTError(TAG, "launchSweep() failed", it as? Exception ?: Exception(it)) }
        }
        return processRewrites()
    }
}

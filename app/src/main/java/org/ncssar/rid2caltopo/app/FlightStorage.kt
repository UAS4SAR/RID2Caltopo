package org.ncssar.rid2caltopo.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.ncssar.rid2caltopo.data.CaltopoClient
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

internal object FlightRetentionPolicy {
    fun needsCleanup(used: Long, incoming: Long = 0, maximum: Long): Boolean =
        used >= maximum * 9 / 10 - incoming.coerceAtLeast(0)
    data class Day(val name: String, val date: LocalDate, val bytes: Long, val protected: Boolean)
    fun candidates(days: List<Day>, used: Long, maximum: Long, maxDays: Long, today: LocalDate): List<String> {
        var remaining = used
        return days.sortedBy { it.date }.filter { day ->
            if (!day.protected && (day.date < today.minusDays(maxDays) || remaining > maximum)) {
                remaining -= day.bytes
                true
            } else false
        }.map { it.name }
    }
}

enum class FlightStorageIssue(val message: String) {
    ARCHIVE_REQUIRED("Choose or reconnect your archive folder to check its usage and enable flight recording. The previous folder may be suggested, but access must be granted again."),
    DEVICE_LOW("Device storage is low. Free device space in Manage Storage or Android Settings. Increasing the allowance will not create free device space."),
    ALLOWANCE("Cleanup could not restore 10% free within the Flight Storage allowance. Today and active files stay protected. Please increase the allowance.");

    val shouldNotify: Boolean get() = this != ARCHIVE_REQUIRED
}

internal fun flightStorageIssue(archiveReady: Boolean, deviceLow: Boolean, allowanceInsufficient: Boolean): FlightStorageIssue? = when {
    !archiveReady -> FlightStorageIssue.ARCHIVE_REQUIRED
    deviceLow -> FlightStorageIssue.DEVICE_LOW
    allowanceInsufficient -> FlightStorageIssue.ALLOWANCE
    else -> null
}

/** Owns retention for the selected document-provider archive and local recording working copies. */
object FlightStorage {
    const val DEFAULT_MAX_BYTES = 10_000_000_000L
    const val DEFAULT_MAX_DAYS = 30L
    private val lock = Any()
    private val protectedDays = mutableMapOf<String, String>()
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val pendingDocuments = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val documentDrainQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var monitor: FlightStorageFileMonitor? = null
    private var listener: Runnable? = null
    private var initialized = false
    private var estimatedUsed = 0L
    private var sweepUsed = 0L
    private var sweepAvailable: Long? = null
    private var lastSweepDay = ""
    private var lastIssue: FlightStorageIssue? = null
    private var checkQueued = false
    private var forceQueued = false
    private val fileSizes = mutableMapOf<String, Long>()
    val pressure = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    @JvmStatic fun observe(context: Context, callback: Runnable) = synchronized(lock) {
        listener = callback
        if (monitor == null) monitor = FlightStorageFileMonitor(auxiliaryRoots(context)) { file -> fileChanged(context, file.path, file.length()) }
    }
    @JvmStatic fun stopObserving() = synchronized(lock) { listener = null; monitor?.stop(); monitor = null }
    @JvmStatic fun documentChanged(context: Context, document: DocumentFile) {
        fileChanged(context, document.uri.toString(), document.length())
    }
    @JvmStatic fun appendCompleted(context: Context?, key: String?, bytes: Long) {
        if (context == null || key == null || bytes <= 0) return
        // Called under the log writer's lock: never acquire the storage lock here.
        pendingDocuments.add(key)
        if (documentDrainQueued.compareAndSet(false, true)) worker.schedule({
            documentDrainQueued.set(false)
            val keys = pendingDocuments.toList()
            keys.forEach { pendingDocuments.remove(it) }
            keys.forEach { value ->
                val uri = android.net.Uri.parse(value)
                val size = if (uri.scheme == "file") File(uri.path!!).length()
                    else DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L
                fileChanged(context, value, size)
            }
        }, 250, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    private fun fileChanged(context: Context, key: String, bytes: Long) = synchronized(lock) {
        if (!initialized) return@synchronized
        val old = fileSizes[key] ?: 0L
        if (old == bytes) return@synchronized
        fileSizes[key] = bytes
        estimatedUsed = (estimatedUsed + bytes - old).coerceAtLeast(0)
        val newDay = lastSweepDay != todayArchiveDirectoryName()
        if (newDay || (captureBlocked && bytes < old) || (!captureBlocked && needsDemandCheck(context))) {
            requestCheck(context, newDay || bytes < old)
        }
    }
    private fun needsDemandCheck(context: Context, incoming: Long = 0): Boolean =
        FlightRetentionPolicy.needsCleanup(estimatedUsed, incoming, maximumBytes(context)) ||
            (sweepAvailable?.let { it - (estimatedUsed - sweepUsed) - incoming < RESERVE } ?: false)
    @JvmStatic fun prepareWrite(context: Context, incoming: Long) {
        val needed = synchronized(lock) { !initialized || (!captureBlocked && needsDemandCheck(context, incoming)) }
        if (needed) {
            scan(context, true, incoming.coerceAtLeast(0))
            val callback = synchronized(lock) { listener }
            callback?.run()
        }
    }
    @JvmStatic @JvmOverloads fun requestCheck(context: Context, force: Boolean = true) = synchronized(lock) {
        forceQueued = forceQueued || force
        if (checkQueued) return@synchronized
        checkQueued = true
        worker.execute {
            val run = synchronized(lock) {
                val force = forceQueued
                forceQueued = false; checkQueued = false
                force || !initialized || (!captureBlocked && needsDemandCheck(context))
            }
            if (run) {
                try { maintain(context) }
                catch (error: Exception) {
                    captureBlocked = true
                    pressure.value = "Storage could not be checked. Restore archive access or free device space in Manage Storage."
                    CaltopoClient.CTError("FlightStorage", "Demand cleanup failed", error)
                }
                val callback = synchronized(lock) { listener }
                callback?.run()
            }
        }
    }
    private const val RESERVE = 1_000_000_000L
    @Volatile var captureBlocked = false; private set
    data class Snapshot(val used: Long, val auxiliary: Long, val failures: List<String>,
                        val issue: FlightStorageIssue?, val archiveReady: Boolean, val deviceAvailable: Long) {
        val blocked: Boolean get() = issue != null
        val allowanceInsufficient: Boolean get() = issue == FlightStorageIssue.ALLOWANCE
        val message: String get() = issue?.message.orEmpty()
    }
    private fun prefs(context: Context) = context.getSharedPreferences("flight_storage", Context.MODE_PRIVATE)
    fun maximumBytes(context: Context) = prefs(context).getLong("maximum_bytes", DEFAULT_MAX_BYTES).coerceIn(100_000_000L, 1_000_000_000_000L)
    fun maximumDays(context: Context) = prefs(context).getLong("maximum_days", DEFAULT_MAX_DAYS).coerceIn(1L, 3650L)
    fun save(context: Context, bytes: Long, days: Long) {
        prefs(context).edit().putLong("maximum_bytes", bytes).putLong("maximum_days", days).apply()
        requestCheck(context)
    }
    @JvmStatic @JvmOverloads fun protect(name: String, owner: String = name) = synchronized(lock) { protectedDays[owner] = name; Unit }
    @JvmStatic fun release(owner: String) = synchronized(lock) {
        val released = protectedDays.remove(owner)
        if (released != null && captureBlocked) R2CApplication.getAppCtxt()?.let { requestCheck(it) }
        Unit
    }
    @JvmStatic fun isProtected(name: String): Boolean = synchronized(lock) {
        name == todayArchiveDirectoryName() || name in protectedDays.values
    }
    private fun size(file: DocumentFile, ledger: MutableMap<String, Long>? = null): Long =
        if (file.isDirectory) file.listFiles().sumOf { size(it, ledger) }
        else file.length().coerceAtLeast(0).also { ledger?.put(file.uri.toString(), it) }
    private fun auxiliaryRoots(context: Context) = listOf(
        File(context.filesDir, "mediamtx-recordings"),
        File(context.filesDir, "managed-video-session-recordings"),
        File(context.filesDir, "mediamtx-recordings-merged"),
        File(context.filesDir, "clues")
    )
    private fun auxiliaryFiles(context: Context) = auxiliaryRoots(context).flatMap { root ->
        if (root.exists()) root.walkTopDown().filter { it.isFile }.toList() else emptyList()
    }
    private fun fileDay(file: File) = if (file.name == "clues.json") todayArchiveDirectoryName()
        else todayArchiveDirectoryName(Date(file.lastModified()))
    fun documentsForDay(context: Context, name: String): List<DocumentFile> = synchronized(lock) {
        CaltopoClient.GetArchiveDir()?.findFile(name)?.listFiles().orEmpty().toList() +
            auxiliaryFiles(context).filter { fileDay(it) == name }.map(DocumentFile::fromFile)
    }
    fun directories(context: Context): List<ArchiveCleanupDirectoryOption> = synchronized(lock) {
        val folders = CaltopoClient.GetArchiveDir()?.listFiles()?.filter { it.isDirectory && isDatedArchiveDirectoryName(it.name) }.orEmpty()
        val auxiliary = auxiliaryFiles(context).groupBy(::fileDay)
        (folders.mapNotNull { it.name } + auxiliary.keys).distinct().mapNotNull { name ->
            val folder = folders.firstOrNull { it.name == name }
            val entries = folder?.listFiles().orEmpty().map(::documentFileToArchiveEntry) +
                auxiliary[name].orEmpty().map { ArchiveCleanupFileEntry(it.name, null, false, it.length()) }
            buildArchiveCleanupOption(name, 0, entries)?.let { option ->
                option.copy(isToday = option.isToday || auxiliary[name].orEmpty().any { isStaging(context, it) })
            }
        }.sortedByDescending { it.ageMs }
    }
    private fun isStaging(context: Context, file: File): Boolean =
        listOf(auxiliaryRoots(context)[0], auxiliaryRoots(context)[2]).any { file.path.startsWith(it.path + "/") }

    fun deleteDays(context: Context, names: List<String>): ArchiveCleanupDeleteResult = synchronized(lock) {
        val failures = mutableListOf<String>()
        var deleted = 0
        val auxiliary = auxiliaryFiles(context)
        for (name in names.distinct()) {
            if (!isDatedArchiveDirectoryName(name) || isProtected(name) || auxiliary.any { fileDay(it) == name && isStaging(context, it) }) {
                failures.add(name); continue
            }
            val folder = CaltopoClient.GetArchiveDir()?.findFile(name)
            var ok = folder == null || folder.delete()
            if (ok) auxiliary.filter { fileDay(it) == name }.forEach { if (!it.delete()) ok = false }
            if (ok) deleted++ else failures.add(name)
        }
        if (deleted > 0 || failures.isNotEmpty()) changes.value++
        requestCheck(context)
        ArchiveCleanupDeleteResult(deleted, failures)
    }
    @JvmStatic fun maintain(context: Context): Snapshot = scan(context, true)
    fun snapshot(context: Context): Snapshot = scan(context, false)
    private fun scan(context: Context, purge: Boolean, reserving: Long = 0): Snapshot = synchronized(lock) {
        val archive = CaltopoClient.GetArchiveDir()
        val folders = archive?.listFiles()?.filter { it.isDirectory && isDatedArchiveDirectoryName(it.name) }.orEmpty()
        val auxiliary = auxiliaryFiles(context)
        val ledger = mutableMapOf<String, Long>()
        val sizes = folders.associate { it.name!! to size(it, ledger) }.toMutableMap()
        auxiliary.forEach { sizes.merge(fileDay(it), it.length(), Long::plus); ledger[it.path] = it.length() }
        var used = sizes.values.sum()
        val today = LocalDate.now()
        val days = sizes.map { (name, bytes) ->
            val date = java.time.Instant.ofEpochMilli(parseArchiveDirectoryDateMs(name)!!).atZone(ZoneId.systemDefault()).toLocalDate()
            // Native recorder staging remains protected until its completion/sync path releases it.
            val staging = auxiliary.any { fileDay(it) == name && isStaging(context, it) }
            FlightRetentionPolicy.Day(name, date, bytes, isProtected(name) || staging)
        }
        val freeArchive = availableBytes(context, archive)
        val target = minOf((maximumBytes(context) * 9 / 10 - reserving).coerceAtLeast(0), freeArchive?.let { (used + it - RESERVE - reserving).coerceAtLeast(0) } ?: maximumBytes(context))
        val failures = mutableListOf<String>()
        if (purge) for (name in FlightRetentionPolicy.candidates(days, used, target, maximumDays(context), today)) {
            val folder = folders.firstOrNull { it.name == name }
            val ok = (folder == null || folder.delete())
            var allDeleted = ok
            if (ok) auxiliary.filter { fileDay(it) == name }.forEach { if (!it.delete()) allDeleted = false }
            if (allDeleted) {
                used -= sizes.getValue(name)
                folder?.uri?.toString()?.let { prefix -> ledger.keys.removeAll { it.startsWith(prefix) } }
                auxiliary.filter { fileDay(it) == name }.forEach { ledger.remove(it.path) }
            } else failures.add(name)
        }
        if (purge && used != sizes.values.sum()) changes.value++
        // Local staging and provider storage can be on different volumes: check both.
        val free = availableBytes(context, archive)
        val archiveReady = archive != null && archive.isDirectory && archive.canRead() && archive.canWrite()
        val deviceAvailable = context.filesDir.usableSpace
        val issue = flightStorageIssue(
            archiveReady = archiveReady,
            deviceLow = deviceAvailable - reserving < RESERVE || (free != null && free - reserving < RESERVE),
            allowanceInsufficient = used + reserving > maximumBytes(context) * 9 / 10,
        )
        val result = Snapshot(used, auxiliary.filter { it.exists() }.sumOf { it.length() }, failures, issue, archiveReady, deviceAvailable)
        val blocked = result.blocked
        if (purge) {
            val wasBlocked = captureBlocked
            captureBlocked = blocked
            sweepUsed = used; sweepAvailable = minOf(context.filesDir.usableSpace, free ?: Long.MAX_VALUE)
            initialized = true; estimatedUsed = used; lastSweepDay = todayArchiveDirectoryName()
            fileSizes.clear(); fileSizes.putAll(ledger)
            if (issue?.shouldNotify == true) {
                if (!wasBlocked || lastIssue != issue) pressure.value = result.message
            } else pressure.value = null
            lastIssue = issue
        }
        result
    }
    private fun availableBytes(context: Context, archive: DocumentFile?): Long? = try {
        if (archive == null) null
        else if (archive.uri.scheme == "file") File(archive.uri.path!!).usableSpace
        else context.contentResolver.query(
            android.provider.DocumentsContract.buildRootsUri(archive.uri.authority!!),
            arrayOf(android.provider.DocumentsContract.Root.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Root.COLUMN_AVAILABLE_BYTES),
            null, null, null
        )?.use { cursor ->
            val treeId = android.provider.DocumentsContract.getTreeDocumentId(archive.uri)
            var result: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if ((treeId == id || treeId.startsWith(if (id.endsWith(":")) id else id.trimEnd('/') + "/")) && !cursor.isNull(1)) result = cursor.getLong(1).takeIf { it >= 0 }
            }
            result
        }
    } catch (_: Exception) { null }
}

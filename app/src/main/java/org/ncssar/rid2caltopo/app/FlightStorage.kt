package org.ncssar.rid2caltopo.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.storage.DocumentArchiveTree
import org.ncssar.rid2caltopo.storage.OperationalStorage
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

/** File presence implies recording only while staging has been written within an hour. */
internal object RecordingStagingPolicy {
    const val INACTIVE_AFTER_MS = 60 * 60 * 1000L
    fun mayBeActive(modifiedMs: Long, nowMs: Long): Boolean =
        modifiedMs <= 0 || modifiedMs > nowMs - INACTIVE_AFTER_MS
}

/**
 * Why a dated flight folder cannot be deleted right now. Same reasons and wording
 * as iOS `FlightFolderProtection` (AppleFlightStorage.swift).
 */
sealed class FlightFolderProtection(val label: String) {
    object Today : FlightFolderProtection("today")
    object Recording : FlightFolderProtection("recording in progress")
    data class InUse(val owner: String) : FlightFolderProtection("in use by $owner")
    object ClueUploading : FlightFolderProtection("clue upload in progress")

    companion object {
        fun ownerLabel(owner: String): String = when {
            owner == "diagnostic-log" -> "diagnostic log"
            owner == "video-review" -> "video review"
            owner == "recorder" -> "recording"
            owner == "session" -> "app session"
            owner.startsWith("upload-") || owner.startsWith("archive-upload-") -> "archive upload"
            owner.startsWith("finalize-") -> "recording finalization"
            else -> owner.replace('-', ' ')
        }

        /** Manual deletion is blocked only by these reasons; pending/failed clues warn instead. */
        fun evaluate(
            name: String,
            today: String,
            owners: Map<String, String>,
            recorderStaging: Boolean,
            clueStates: List<String>,
        ): FlightFolderProtection? = when {
            name == today -> Today
            recorderStaging -> Recording
            owners.any { it.value == name } ->
                InUse(owners.filter { it.value == name }.keys.map(::ownerLabel).sorted().first())
            "uploading" in clueStates -> ClueUploading
            else -> null
        }

        /** Automatic cleanup also keeps days whose clues are still queued. Failed is permanent. */
        fun blocksAutomaticCleanup(clueStates: List<String>): Boolean =
            clueStates.any { it == "pending" || it == "uploading" }

        fun unuploadedClueCount(clueStates: List<String>): Int =
            clueStates.count { it == "pending" || it == "uploading" || it == "failed" }
    }
}

enum class FlightStorageIssue(val message: String) {
    ARCHIVE_REQUIRED("Choose or reconnect your archive folder to check its usage and enable flight recording. The previous folder may be suggested, but access must be granted again."),
    ARCHIVE_WRITE("Recording stopped because Archive Dir could not save recording data. Reconnect the archive storage and retry."),
    DEVICE_LOW("Device storage is low. Free device space in Manage Storage or Android Settings. Increasing the allowance will not create free device space."),
    ALLOWANCE("Cleanup could not restore 10% free within the Flight Storage allowance. Today and active files stay protected. Please increase the allowance.");

    val shouldNotify: Boolean get() = this != ARCHIVE_REQUIRED
}

internal fun flightStorageIssue(archiveReady: Boolean, deviceLow: Boolean, allowanceInsufficient: Boolean, archiveWriteFailed: Boolean = false): FlightStorageIssue? = when {
    !archiveReady -> FlightStorageIssue.ARCHIVE_REQUIRED
    archiveWriteFailed -> FlightStorageIssue.ARCHIVE_WRITE
    deviceLow -> FlightStorageIssue.DEVICE_LOW
    allowanceInsufficient -> FlightStorageIssue.ALLOWANCE
    else -> null
}

/** Owns retention for the selected document-provider archive and local recording working copies. */
object FlightStorage {
    const val DEFAULT_MAX_BYTES = 10_000_000_000L
    const val DEFAULT_MAX_DAYS = 30L
    private val lock = Any()
    private val stateLock = Any()
    private val protectedDays = mutableMapOf<String, String>()
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val pendingDocuments = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val documentDrainQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var monitor: FlightStorageFileMonitor? = null
    private var recorderMonitor: FlightStorageFileMonitor? = null
    private var listener: Runnable? = null
    private var initialized = false
    @Volatile private var recordingArchiveWriteFailed = false
    private var estimatedUsed = 0L
    private var sweepUsed = 0L
    private var sweepAvailable: Long? = null
    private var lastSweepDay = ""
    private var lastIssue: FlightStorageIssue? = null
    private var checkQueued = false
    private var forceQueued = false
    private var fileSizes = mutableMapOf<String, Long>()
    val pressure = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    @JvmStatic fun observe(context: Context, callback: Runnable) {
        synchronized(stateLock) { listener = callback }
        worker.execute {
            if (CaltopoClient.GetArchiveUri() != null) runCatching { OperationalStorage.requireRecorderReady(context) }.getOrElse { return@execute }
            val recorder = FlightStorageFileMonitor(listOf(File(context.filesDir, "mediamtx-recordings"))) { file ->
                if (file.isFile && file.path.startsWith(File(context.filesDir, "mediamtx-recordings").path + "/") && file.extension.lowercase() in setOf("mp4", "fmp4")) {
                    runCatching { org.ncssar.rid2caltopo.storage.OperationalStorage.retainGrowingRecording(file) }
                        .onSuccess { recordingArchiveWriteFailed = false }
                        .onFailure {
                            recordingArchiveWriteFailed = true; captureBlocked = true
                            pressure.value = FlightStorageIssue.ARCHIVE_WRITE.message
                            CaltopoClient.CTError("FlightStorage", "Recording checkpoint could not be retained", it as? Exception ?: Exception(it))
                            synchronized(stateLock) { listener }?.run()
                        }
                }
                fileChanged(context, file.path, file.length())
            }
            synchronized(stateLock) {
                if (listener === callback && recorderMonitor == null) recorderMonitor = recorder else recorder.stop()
            }
            // The recorder watcher is already checkpointing on its own executor while
            // other operational roots finish migration and projection preparation.
            if (CaltopoClient.GetArchiveUri() != null) runCatching { OperationalStorage.requireReady(context) }.getOrElse { return@execute }
            val candidate = FlightStorageFileMonitor(auxiliaryRoots(context).filterNot { it.name == "mediamtx-recordings" }) { file ->
                fileChanged(context, file.path, file.length())
            }
            synchronized(stateLock) {
                if (listener === callback && monitor == null) monitor = candidate else candidate.stop()
            }
        }
    }
    @JvmStatic fun stopObserving() = synchronized(stateLock) {
        listener = null; monitor?.stop(); monitor = null; recorderMonitor?.stop(); recorderMonitor = null
    }
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
    private fun fileChanged(context: Context, key: String, bytes: Long) {
      var notify: Runnable? = null
      synchronized(stateLock) {
        if (!initialized) return@synchronized
        val old = fileSizes[key] ?: 0L
        if (old == bytes) return@synchronized
        fileSizes[key] = bytes
        estimatedUsed = (estimatedUsed + bytes - old).coerceAtLeast(0)
        val newDay = lastSweepDay != todayArchiveDirectoryName()
        // Cleanup cannot run through an unprepared projection. Pause capture at the
        // existing allowance/reserve boundary while bulk startup is still in progress.
        if (!OperationalStorage.ready() && !captureBlocked && needsDemandCheck(context)) {
            captureBlocked = true
            val low = sweepAvailable?.let { it - (estimatedUsed - sweepUsed) < RESERVE } == true
            val issue = if (low) FlightStorageIssue.DEVICE_LOW else FlightStorageIssue.ALLOWANCE
            pressure.value = issue.message
            notify = listener
        }
        if (notify != null || newDay || (captureBlocked && bytes < old) || (!captureBlocked && needsDemandCheck(context))) {
            requestCheck(context, newDay || bytes < old)
        }
      }
      notify?.run()
    }
    private fun needsDemandCheck(context: Context, incoming: Long = 0): Boolean =
        FlightRetentionPolicy.needsCleanup(estimatedUsed, incoming, maximumBytes(context)) ||
            (sweepAvailable?.let { it - (estimatedUsed - sweepUsed) - incoming < RESERVE } ?: false)
    @JvmStatic fun prepareWrite(context: Context, incoming: Long) {
        val needed = synchronized(stateLock) { !initialized || (!captureBlocked && needsDemandCheck(context, incoming)) }
        if (needed) {
            scan(context, true, incoming.coerceAtLeast(0))
            val callback = synchronized(stateLock) { listener }
            callback?.run()
        }
    }
    private var transientCheckAttempt = 0
    private var transientRetryQueued = false
    internal fun isTransientStorageCheckFailure(error: Throwable): Boolean {
        if (OperationalStorage.isTransientPrepareFailure(error)) return true
        val message = error.message.orEmpty()
        return message.contains("Existing operational projection changed during preparation") ||
            message.contains("Missing file") ||
            message.contains("is child of") ||
            message.contains("Archive file is unavailable")
    }
    @JvmStatic @JvmOverloads fun requestCheck(context: Context, force: Boolean = true) {
        synchronized(stateLock) {
            forceQueued = forceQueued || force
            if (checkQueued) return
            checkQueued = true
        }
        worker.execute {
            val run = synchronized(stateLock) {
                val shouldForce = forceQueued
                forceQueued = false; checkQueued = false
                shouldForce || !initialized || (!captureBlocked && needsDemandCheck(context))
            }
            if (run) {
                try {
                    maintain(context)
                    synchronized(stateLock) { transientCheckAttempt = 0 }
                } catch (error: Exception) {
                    if (isTransientStorageCheckFailure(error)) {
                        val attempt = synchronized(stateLock) { transientCheckAttempt++ }
                        val delayMs = (1_000L shl attempt.coerceAtMost(4)).coerceAtMost(30_000L)
                        CaltopoClient.CTWarn("FlightStorage",
                            "Demand cleanup deferred (transient storage race); retry in ${delayMs}ms")
                        val shouldSchedule = synchronized(stateLock) {
                            if (transientRetryQueued) false else { transientRetryQueued = true; true }
                        }
                        if (shouldSchedule) {
                            worker.schedule({
                                synchronized(stateLock) { transientRetryQueued = false }
                                requestCheck(context, true)
                            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                        }
                    } else {
                        captureBlocked = true
                        pressure.value = "Storage could not be checked. Restore archive access or free device space in Manage Storage."
                        CaltopoClient.CTError("FlightStorage", "Demand cleanup failed", error)
                    }
                }
                val callback = synchronized(stateLock) { listener }
                callback?.run()
            }
        }
    }
    private const val RESERVE = 1_000_000_000L
    @Volatile var captureBlocked = false; private set
    data class Snapshot(val used: Long, val auxiliary: Long, val failures: List<String>,
                        val issue: FlightStorageIssue?, val archiveReady: Boolean, val deviceAvailable: Long,
                        val preparingWorkingCopies: Boolean = false) {
        val blocked: Boolean get() = issue != null
        val allowanceInsufficient: Boolean get() = issue == FlightStorageIssue.ALLOWANCE
        val message: String get() = issue?.message.orEmpty()
    }
    private fun prefs(context: Context) = context.getSharedPreferences("flight_storage", Context.MODE_PRIVATE)
    fun maximumBytes(context: Context) = prefs(context).getLong("maximum_bytes", DEFAULT_MAX_BYTES).coerceIn(100_000_000L, 1_000_000_000_000L)
    fun maximumDays(context: Context) = prefs(context).getLong("maximum_days", DEFAULT_MAX_DAYS).coerceIn(1L, 3650L)
    /** Called off the UI thread; acknowledge only after preferences reach disk. */
    fun save(context: Context, bytes: Long, days: Long): Boolean {
        require(bytes in 100_000_000L..1_000_000_000_000L && days in 1L..3650L)
        val stored = prefs(context).edit().putLong("maximum_bytes", bytes).putLong("maximum_days", days).commit()
        if (!stored || maximumBytes(context) != bytes || maximumDays(context) != days) return false
        requestCheck(context)
        return true
    }
    @JvmStatic @JvmOverloads fun protect(name: String, owner: String = name) = synchronized(stateLock) { protectedDays[owner] = name; Unit }
    @JvmStatic fun release(owner: String) = synchronized(stateLock) {
        val released = protectedDays.remove(owner)
        if (released != null && captureBlocked) R2CApplication.getAppCtxt()?.let { requestCheck(it) }
        Unit
    }
    @JvmStatic fun isProtected(name: String): Boolean = synchronized(stateLock) {
        name == todayArchiveDirectoryName() || name in protectedDays.values
    }
    /** Why manual deletion of this day is blocked, or null when it may be deleted. */
    fun protectionReason(context: Context, name: String): FlightFolderProtection? = storageIo(context) {
        val auxiliary = auxiliaryFiles(context)
        FlightFolderProtection.evaluate(
            name = name,
            today = todayArchiveDirectoryName(),
            owners = synchronized(stateLock) { protectedDays.toMap() },
            recorderStaging = auxiliary.any { fileDay(it) == name && isRecorderStaging(context, it) },
            clueStates = clueStates(context, name, auxiliary),
        )
    }
    fun unuploadedClueCount(context: Context, name: String): Int = storageIo(context) {
        FlightFolderProtection.unuploadedClueCount(clueStates(context, name, auxiliaryFiles(context)))
    }
    private fun auxiliaryRoots(context: Context) = listOf(
        File(context.filesDir, "mediamtx-recordings"),
        File(context.filesDir, "managed-video-session-recordings"),
        File(context.filesDir, "mediamtx-recordings-merged"),
        File(context.filesDir, "clues"),
        File(context.filesDir, "anr-traces"),
        File(context.filesDir, "review-annotations"),
        File(context.filesDir, "video-sei-originals")
    )
    private fun auxiliaryFiles(context: Context, cancelled: () -> Unit = {}) = auxiliaryRoots(context).flatMap { root ->
        cancelled()
        if (root.exists()) root.walkTopDown().onEnter { cancelled(); true }.filter { cancelled(); it.isFile }.toList() else emptyList()
    }
    private fun fileDay(file: File) = if (file.name == "clues.json") todayArchiveDirectoryName()
        else todayArchiveDirectoryName(Date(file.lastModified()))
    fun documentsForDay(context: Context, name: String): List<DocumentFile> = storageIo(context) {
        CaltopoClient.GetArchiveDir()?.findFile(name)?.listFiles().orEmpty().toList() +
            auxiliaryFiles(context).filter { fileDay(it) == name }.map(DocumentFile::fromFile)
    }
    fun directories(context: Context, cancelled: () -> Unit = {}): List<ArchiveCleanupDirectoryOption> = storageMetadata(context) {
        val started = android.os.SystemClock.elapsedRealtime()
        val preparing = CaltopoClient.GetArchiveUri() != null && !OperationalStorage.ready()
        val folders = CaltopoClient.GetArchiveDir()?.let { readFlightArchiveInventory(DocumentArchiveTree(context, it, requireKnownFileSizes = true), cancelled) }.orEmpty()
        val auxiliary = auxiliaryFiles(context, cancelled).groupBy(::fileDay)
        val owners = synchronized(stateLock) { protectedDays.toMap() }
        val result = (folders.map { it.name } + auxiliary.keys).distinct().mapNotNull { name ->
            cancelled()
            val folder = folders.firstOrNull { it.name == name }
            val dayFiles = auxiliary[name].orEmpty()
            val entries = folder?.entries.orEmpty() +
                auxiliary[name].orEmpty().map { ArchiveCleanupFileEntry(it.name, null, false, it.length()) }
            val states = if (preparing) emptyList() else clueStates(context, name, dayFiles)
            buildArchiveCleanupOption(name, folder?.modified ?: 0, entries)?.let { option ->
                val reason = if (preparing) FlightFolderProtection.InUse("storage preparation") else FlightFolderProtection.evaluate(
                    name, todayArchiveDirectoryName(), owners,
                    dayFiles.any { isRecorderStaging(context, it) }, states)
                option.copy(
                    isToday = reason != null,
                    protectionReason = reason?.label,
                    unuploadedClueCount = FlightFolderProtection.unuploadedClueCount(states),
                )
            }
        }.sortedByDescending { it.ageMs }
        CaltopoClient.CTDebug("FlightStorage", "Listed flight days count=${result.size} preparing=$preparing elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
        result
    }
    private fun isRecorderStaging(context: Context, file: File): Boolean =
        listOf(auxiliaryRoots(context)[0], auxiliaryRoots(context)[2]).any { file.path.startsWith(it.path + "/") } &&
            RecordingStagingPolicy.mayBeActive(file.lastModified(), System.currentTimeMillis())

    /** Upload states of CalTopo-bound clues whose image files belong to this day. */
    private fun clueStates(context: Context, name: String, auxiliary: List<File>): List<String> {
        val clues = File(context.filesDir, "clues")
        val dayFiles = auxiliary.filter { it.parentFile == clues && it.name != "clues.json" && fileDay(it) == name }
            .map { it.name }.toSet()
        if (dayFiles.isEmpty()) return emptyList()
        val index = runCatching { org.json.JSONArray(File(clues, "clues.json").readText()) }.getOrNull()
            // Unreadable index: treat the day's clue files as still queued (safe for cleanup).
            ?: return listOf("pending")
        return (0 until index.length()).mapNotNull { i ->
            val entry = index.optJSONObject(i) ?: return@mapNotNull null
            if (!entry.optBoolean("publishToCaltopo")) return@mapNotNull null
            if (listOf(entry.optString("imageFilename"), entry.optString("thumbnailFilename")).none { it in dayFiles }) return@mapNotNull null
            entry.optString("uploadState")
        }
    }

    fun deleteDays(context: Context, names: List<String>): ArchiveCleanupDeleteResult = storageIo(context) {
        val failures = mutableListOf<String>()
        var deleted = 0
        val auxiliary = auxiliaryFiles(context)
        for (name in names.distinct()) {
            // Pending/failed clue uploads no longer block a manual delete; the
            // confirmation warns how many clues were never uploaded.
            if (!isDatedArchiveDirectoryName(name) || protectionReason(context, name) != null) {
                failures.add(name); continue
            }
            val folder = CaltopoClient.GetArchiveDir()?.findFile(name)
            var ok = folder == null || folder.delete()
            if (ok) auxiliary.filter { fileDay(it) == name }.forEach { if (!org.ncssar.rid2caltopo.storage.OperationalStorage.removeIfManaged(it)) ok = false }
            if (ok) deleted++ else failures.add(name)
        }
        if (deleted > 0 || failures.isNotEmpty()) changes.value++
        requestCheck(context)
        ArchiveCleanupDeleteResult(deleted, failures)
    }
    @JvmStatic fun maintain(context: Context): Snapshot = scan(context, true)
    /** A metadata-only allowance check; bulk cleanup is queued without delaying RTMP. */
    @JvmStatic fun prepareRecordingStart(context: Context): Snapshot = storageMetadata(context) {
        // Serialize allowance initialization with actual retention cleanup, without
        // entering the unrelated operational-storage preparation barrier.
        val snapshot = synchronized(lock) { scanOnWorker(context, false, 0, seedEstimate = true) }
        requestCheck(context)
        snapshot
    }
    fun snapshot(context: Context, cancelled: () -> Unit = {}): Snapshot = storageMetadata(context) {
        scanOnWorker(context, false, 0, cancelled)
    }
    private fun scan(context: Context, purge: Boolean, reserving: Long = 0): Snapshot = storageIo(context) {
        scanOnWorker(context, purge, reserving)
    }
    private fun scanOnWorker(context: Context, purge: Boolean, reserving: Long, cancelled: () -> Unit = {}, seedEstimate: Boolean = false): Snapshot {
        val started = android.os.SystemClock.elapsedRealtime()
        val preparing = CaltopoClient.GetArchiveUri() != null && !OperationalStorage.ready()
        val archive = CaltopoClient.GetArchiveDir()
        val tree = archive?.let { DocumentArchiveTree(context, it, requireKnownFileSizes = true) }
        val folders = tree?.let { readFlightArchiveInventory(it, cancelled) }.orEmpty()
        val auxiliary = auxiliaryFiles(context, cancelled)
        val ledger = mutableMapOf<String, Long>()
        val sizes = folders.associate { it.name to it.bytes }.toMutableMap()
        if (purge || seedEstimate) for (folder in folders) for ((path, bytes) in folder.fileSizes) {
            cancelled()
            ledger[requireNotNull(tree).uri(path).toString()] = bytes
        }
        auxiliary.forEach { sizes.merge(fileDay(it), it.length(), Long::plus); ledger[it.path] = it.length() }
        var used = sizes.values.sum()
        val today = LocalDate.now()
        val days = if (!purge) emptyList() else sizes.map { (name, bytes) ->
            val date = java.time.Instant.ofEpochMilli(parseArchiveDirectoryDateMs(name)!!).atZone(ZoneId.systemDefault()).toLocalDate()
            // Native recorder staging remains protected until its completion/sync path releases it.
            // Queued (pending/uploading) clues are kept; failed uploads no longer pin a day forever.
            val staging = auxiliary.any { fileDay(it) == name && isRecorderStaging(context, it) }
            val clueHold = FlightFolderProtection.blocksAutomaticCleanup(clueStates(context, name, auxiliary))
            FlightRetentionPolicy.Day(name, date, bytes, isProtected(name) || staging || clueHold)
        }
        val freeArchive = availableBytes(context, archive)
        val target = minOf((maximumBytes(context) * 9 / 10 - reserving).coerceAtLeast(0), freeArchive?.let { (used + it - RESERVE - reserving).coerceAtLeast(0) } ?: maximumBytes(context))
        val failures = mutableListOf<String>()
        if (purge) for (name in FlightRetentionPolicy.candidates(days, used, target, maximumDays(context), today)) {
            val folder = folders.firstOrNull { it.name == name }
            val ledgerKeys = folder?.fileSizes?.keys?.map { requireNotNull(tree).uri(it).toString() }.orEmpty()
            val ok = folder == null || runCatching { requireNotNull(tree).delete(name); true }.getOrDefault(false)
            var allDeleted = ok
            if (ok) auxiliary.filter { fileDay(it) == name }.forEach { if (!org.ncssar.rid2caltopo.storage.OperationalStorage.removeIfManaged(it)) allDeleted = false }
            if (allDeleted) {
                used -= sizes.getValue(name)
                ledgerKeys.forEach { ledger.remove(it) }
                auxiliary.filter { fileDay(it) == name }.forEach { ledger.remove(it.path) }
            } else failures.add(name)
        }
        if (purge && used != sizes.values.sum()) changes.value++
        // Local staging and provider storage can be on different volumes: check both.
        val free = if (purge && used != sizes.values.sum()) availableBytes(context, archive) else freeArchive
        val archiveReady = archive != null && archive.isDirectory && archive.canRead() && archive.canWrite() &&
            (!seedEstimate || CaltopoClient.GetArchiveUri() != null)
        val deviceAvailable = context.filesDir.usableSpace
        val issue = flightStorageIssue(
            archiveReady = archiveReady,
            deviceLow = deviceAvailable - reserving < RESERVE || (free != null && free - reserving < RESERVE),
            allowanceInsufficient = used + reserving > maximumBytes(context) * 9 / 10,
            archiveWriteFailed = recordingArchiveWriteFailed,
        )
        val result = Snapshot(used, auxiliary.filter { it.exists() }.sumOf { it.length() }, failures, issue, archiveReady, deviceAvailable, preparing)
        val blocked = result.blocked
        if (purge || seedEstimate) synchronized(stateLock) {
            val wasBlocked = captureBlocked
            captureBlocked = blocked
            sweepUsed = used; sweepAvailable = minOf(deviceAvailable, free ?: Long.MAX_VALUE)
            initialized = true; estimatedUsed = used; lastSweepDay = todayArchiveDirectoryName()
            fileSizes = ledger
            if (issue?.shouldNotify == true) {
                if (!wasBlocked || lastIssue != issue) pressure.value = result.message
            } else pressure.value = null
            lastIssue = issue
        }
        CaltopoClient.CTDebug("FlightStorage", "Sized flights purge=$purge days=${folders.size} files=${folders.sumOf { it.fileSizes.size }} preparing=$preparing elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
        return result
    }
    /** Read-only sizing must not queue behind migration, hashing, hydration or retention cleanup. */
    private inline fun <T> storageMetadata(context: Context, action: () -> T): T {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Flight storage must run on a worker" }
        val archive = CaltopoClient.GetArchiveUri()
        val result = action()
        check(archive == CaltopoClient.GetArchiveUri()) { "Archive Dir changed while sizing storage. Retry the scan." }
        return result
    }
    private inline fun <T> storageIo(context: Context, action: () -> T): T {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Flight storage must run on a worker" }
        if (CaltopoClient.GetArchiveUri() != null) org.ncssar.rid2caltopo.storage.OperationalStorage.requireReady(context)
        return synchronized(lock, action)
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

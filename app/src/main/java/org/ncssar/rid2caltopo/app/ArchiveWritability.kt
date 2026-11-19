package org.ncssar.rid2caltopo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.storage.ArchiveRecheckTrigger
import org.ncssar.rid2caltopo.storage.ArchiveWritabilityTracker
import org.ncssar.rid2caltopo.storage.ArchiveWritabilityVerdict
import org.ncssar.rid2caltopo.storage.ArchiveWriteProblem
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-wide sticky archive writability (see [ArchiveWritabilityTracker]). While the archive
 * is not writable, recordings and logs stay in app-private tablet storage and are copied to
 * the archive folder once a remount, folder pick or periodic probe finds it writable again.
 */
object ArchiveWritability {
    private const val TAG = "ArchiveWritability"
    private const val PROBE_PREFIX = ".r2c-write-probe-"
    private const val PERIODIC_TICK_MS = 60_000L

    @JvmStatic
    val tracker = ArchiveWritabilityTracker(System::currentTimeMillis) { CaltopoClient.CTWarn(TAG, it) }

    private val _state = MutableStateFlow(tracker.verdict)
    val state: StateFlow<ArchiveWritabilityVerdict> = _state

    /** The not-writable verdict whose alert is waiting to be seen. Set once per state change. */
    private val _alert = MutableStateFlow<ArchiveWritabilityVerdict?>(null)
    val alert: StateFlow<ArchiveWritabilityVerdict?> = _alert

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "r2c-archive-recheck").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val started = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null

    init {
        tracker.addListener { _, new ->
            _state.value = new
            if (!new.writable) _alert.value = new else _alert.value = null
            val context = appContext ?: R2CApplication.getAppCtxt()
            if (context != null) {
                FlightStorage.requestCheck(context)
                if (new.writable) executor.execute { catchUp(context) }
            }
        }
    }

    @JvmStatic val writable: Boolean get() = tracker.writable

    /** Marks the archive not writable when [error] is a read-only or I/O failure. */
    @JvmStatic
    fun noteFailure(error: Throwable?, where: String): Boolean =
        tracker.noteFailure(error, where, archiveIsRemovable())

    fun dismissAlert(verdict: ArchiveWritabilityVerdict) {
        _alert.compareAndSet(verdict, null)
    }

    /** The map chip re-opens the alert on request; failed checks never do. */
    fun reshowAlert() {
        val current = tracker.verdict
        if (!current.writable) _alert.value = current
        org.ncssar.rid2caltopo.storage.MissingPreviousStorage.reshowAlert()
    }

    /** Tablet (app-private) storage still has room above the Flight Storage reserve. */
    @JvmStatic
    fun tabletFallbackAvailable(context: Context): Boolean = FlightStorage.tabletFallbackAvailable(context)

    @JvmStatic
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        appContext = app
        org.ncssar.rid2caltopo.storage.MissingPreviousStorage.log = { CaltopoClient.CTWarn("MissingPreviousStorage", it) }
        org.ncssar.rid2caltopo.storage.DetachedPreviousStorage.attach(app)
        val filter = IntentFilter().apply {
            // Probe only the active archive folder on a mount or removal. A swapped-out old
            // card is never an error; only losing the active archive's card raises the chip.
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        runCatching {
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    CaltopoClient.CTDebug(TAG, "Storage ${intent.action?.substringAfterLast('.')} ${intent.data}; rechecking archive")
                    requestRecheck(ArchiveRecheckTrigger.REMOUNT)
                    clearMissingPreviousIfMounted(context)
                }
            }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { CaltopoClient.CTWarn(TAG, "Storage mount receiver unavailable: ${it.message}") }
        executor.scheduleWithFixedDelay({
            if (tracker.recheckDue(ArchiveRecheckTrigger.PERIODIC)) recheckNow(ArchiveRecheckTrigger.PERIODIC)
        }, PERIODIC_TICK_MS, PERIODIC_TICK_MS, TimeUnit.MILLISECONDS)
    }

    /** A missing previous card that is mounted again: forget it and let storage prepare again. */
    private fun clearMissingPreviousIfMounted(context: Context) {
        // A swapped-out card came back: copy what stayed on it into the active archive.
        val detached = org.ncssar.rid2caltopo.storage.DetachedPreviousStorage.entries()
        if (detached.any { org.ncssar.rid2caltopo.storage.OperationalStorage.removableVolumeMounted(context, it.volume) }) {
            CaltopoClient.CTDebug(TAG, "Swapped-out card is back; copying what stayed on it into the archive folder")
            org.ncssar.rid2caltopo.storage.OperationalStorage.recoverDetachedNow(context)
        }
        val volume = org.ncssar.rid2caltopo.storage.MissingPreviousStorage.notice.value?.volume ?: return
        val mounted = runCatching {
            context.getSystemService(StorageManager::class.java)?.storageVolumes?.any {
                it.uuid.equals(volume, ignoreCase = true) && it.state == Environment.MEDIA_MOUNTED
            } == true
        }.getOrDefault(false)
        if (!mounted) return
        CaltopoClient.CTDebug(TAG, "Previous card $volume is back; preparing retained storage again")
        org.ncssar.rid2caltopo.storage.MissingPreviousStorage.clear()
        runCatching { org.ncssar.rid2caltopo.storage.OperationalStorage.retry(context) }
            .onFailure { CaltopoClient.CTWarn(TAG, "Retained storage retry failed: ${it.message}") }
    }

    /** Remount and folder pick always probe; periodic probes run at most every 5 minutes. */
    @JvmStatic
    fun requestRecheck(trigger: ArchiveRecheckTrigger) {
        runCatching { executor.execute { recheckNow(trigger) } }
        if (trigger == ArchiveRecheckTrigger.FOLDER_PICKED) folderPicked()
    }

    /**
     * A folder was picked in Manage Storage or the prompt: an old card is no longer the
     * operator's concern. Clear the previous-storage notice at once and prepare storage on the
     * active folder again, which retargets any links that still point at a removed card.
     */
    private fun folderPicked() {
        org.ncssar.rid2caltopo.storage.MissingPreviousStorage.clear()
        val context = appContext ?: R2CApplication.getAppCtxt() ?: return
        if (!org.ncssar.rid2caltopo.storage.OperationalStorage.ready()) {
            runCatching { org.ncssar.rid2caltopo.storage.OperationalStorage.retry(context) }
                .onFailure { CaltopoClient.CTWarn(TAG, "Storage retry after folder pick failed: ${it.message}") }
        }
    }

    private fun recheckNow(trigger: ArchiveRecheckTrigger) {
        val context = appContext ?: R2CApplication.getAppCtxt() ?: return
        runCatching { tracker.recheck(trigger, archiveIsRemovable()) { probe(context) } }
            .onFailure { CaltopoClient.CTWarn(TAG, "Archive recheck failed: ${it.message}") }
    }

    /** Creates, writes and deletes a small file in the archive root. Throws when that fails. */
    private fun probe(context: Context) {
        // GetArchiveUri may clear an inaccessible selection. Its hint still identifies the
        // folder the operator told us to use, so a genuinely missing active card is reported.
        val uri = CaltopoClient.GetArchiveUri() ?: CaltopoClient.GetArchiveUriSelectionHint() ?: return
        val state = volumeState(context, uri)
        if (state == Environment.MEDIA_MOUNTED_READ_ONLY) {
            throw IOException("Archive volume is mounted read-only (EROFS)")
        }
        val id = volumeId(uri)
        if (uri.authority == "com.android.externalstorage.documents" && id != null && id != "primary" &&
            state != Environment.MEDIA_MOUNTED) {
            throw IOException(ArchiveWritabilityTracker.missingCardMessage(id))
        }
        val root = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("Archive Dir is unavailable")
        if (!root.isDirectory) throw IOException("Archive Dir is unavailable")
        root.listFiles().filter { it.name?.startsWith(PROBE_PREFIX) == true }.forEach { runCatching { it.delete() } }
        val name = PROBE_PREFIX + System.currentTimeMillis()
        val file = root.createFile("application/octet-stream", name)
            ?: throw IOException("Archive Dir refused a new file (Read-only file system?)")
        try {
            val stream = context.contentResolver.openOutputStream(file.uri, "w")
                ?: throw IOException("Archive Dir probe could not be opened")
            stream.use { it.write(byteArrayOf(0x52)); it.flush() }
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun volumeId(uri: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
    }.getOrNull()

    private fun volumeState(context: Context, uri: Uri): String? = runCatching {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = volumeId(uri) ?: return null
        val manager = context.getSystemService(StorageManager::class.java) ?: return null
        val volume = manager.storageVolumes.firstOrNull { volume ->
            if (id == "primary") volume.isPrimary else volume.uuid.equals(id, ignoreCase = true)
        }
        volume?.state
    }.getOrNull()

    /** True when the archive folder is on a removable volume (an SD card). */
    @JvmStatic
    fun archiveIsRemovable(): Boolean {
        val uri = CaltopoClient.GetArchiveUri() ?: return false
        if (uri.authority != "com.android.externalstorage.documents") return false
        val id = volumeId(uri) ?: return false
        if (id == "primary") return false
        val context = appContext ?: R2CApplication.getAppCtxt() ?: return true
        return runCatching {
            context.getSystemService(StorageManager::class.java)?.storageVolumes
                ?.firstOrNull { it.uuid.equals(id, ignoreCase = true) }?.isRemovable ?: true
        }.getOrDefault(true)
    }

    /** Volume state says read-only: mark it without waiting for a failed write. */
    @JvmStatic
    fun noteVolumeReadOnly(where: String) {
        tracker.markNotWritable(ArchiveWriteProblem.READ_ONLY, "volume mounted read-only", where, archiveIsRemovable())
    }

    /** Copies what was kept in tablet storage while the archive was not writable. */
    private fun catchUp(context: Context) {
        CaltopoClient.CTDebug(TAG, "Copying recordings and logs kept in tablet storage to the archive folder")
        runCatching { CaltopoClient.CatchUpStagedSessionLogs(context) }
            .onFailure { CaltopoClient.CTWarn(TAG, "Session log catch-up failed: ${it.message}") }
        runCatching { org.ncssar.rid2caltopo.data.MediaMTXRecordingSync.syncSettledStagedRecordings(context) }
            .onFailure { CaltopoClient.CTWarn(TAG, "Recording catch-up failed: ${it.message}") }
    }
}

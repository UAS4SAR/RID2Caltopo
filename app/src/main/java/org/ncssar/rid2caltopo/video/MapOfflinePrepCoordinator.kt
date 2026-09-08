package org.ncssar.rid2caltopo.video

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call
import org.ncssar.rid2caltopo.app.MapOfflineDownloadService
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.video.mapcache.MapCacheMaintenanceScheduler
import org.ncssar.rid2caltopo.video.mapcache.TileFetchPriorityScheduler
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Process-lifetime signal used by app shutdown and relocation policy. */
object MapOfflinePrepRuntime {
    private val active = AtomicBoolean(false)
    private val lastActivityAtMsec = AtomicLong(0L)

    @Volatile
    private var cancelAction: ((Boolean) -> Unit)? = null

    @Volatile
    private var idlePolicyChangedAction: (() -> Unit)? = null

    @JvmStatic
    fun isActive(): Boolean = active.get()

    @JvmStatic
    fun lastActivityAtMsec(): Long = lastActivityAtMsec.get()

    internal fun noteProgress() {
        if (active.get()) lastActivityAtMsec.set(System.currentTimeMillis())
    }

    internal fun claimStallRecovery(nowMsec: Long, thresholdMsec: Long): Boolean {
        if (!active.get()) return false
        while (true) {
            val lastActivity = lastActivityAtMsec.get()
            if (nowMsec - lastActivity < thresholdMsec) return false
            if (lastActivityAtMsec.compareAndSet(lastActivity, nowMsec)) return true
        }
    }

    internal fun begin(
        onIdlePolicyChanged: () -> Unit = {},
        onCancel: (hideDialog: Boolean) -> Unit,
    ) {
        cancelAction = onCancel
        idlePolicyChangedAction = onIdlePolicyChanged
        lastActivityAtMsec.set(System.currentTimeMillis())
        active.set(true)
        onIdlePolicyChanged()
    }

    internal fun finish() {
        val wasActive = active.getAndSet(false)
        if (wasActive) lastActivityAtMsec.set(System.currentTimeMillis())
        cancelAction = null
        val onIdlePolicyChanged = idlePolicyChangedAction
        idlePolicyChangedAction = null
        if (wasActive) onIdlePolicyChanged?.invoke()
    }

    @JvmStatic
    fun cancelActive() {
        cancelAction?.invoke(true)
    }

    internal fun resetForTesting() {
        active.set(false)
        lastActivityAtMsec.set(0L)
        cancelAction = null
        idlePolicyChangedAction = null
    }
}

/** Owns a closeable worker resource for exactly one offline-preparation run. */
internal class OfflinePrepWorkerLease<T : Closeable>(private val factory: () -> T) {
    private var active: T? = null

    @Synchronized
    fun acquire(): T {
        check(active == null) { "Offline preparation worker lease is already active" }
        return factory().also { active = it }
    }

    @Synchronized
    fun release() {
        active?.close()
        active = null
    }
}

/**
 * Owns the Android offline-map job independently of any particular MapPane composition.
 * A replacement MapPane observes the same state and reattaches to the active progress UI.
 */
internal object AndroidMapOfflinePrepCoordinator {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val showDialog = mutableStateOf(false)
    val inFlight = mutableStateOf(false)
    val preset = mutableStateOf(OFFLINE_PREP_PRESETS[1])
    val includeDem = mutableStateOf(true)
    val demResolution = mutableStateOf(DemResolutionOption.MAXIMUM_1M)
    val includeContours = mutableStateOf(false)
    val maximizeThroughput = mutableStateOf(false)
    val areaMode = mutableStateOf(OfflinePrepAreaMode.Viewport)
    val boundaryId = mutableStateOf<String?>(null)
    val progress = mutableStateOf(OfflinePrepProgress())
    val cancelRequested = mutableStateOf(false)
    val completedSelectionKey = mutableStateOf<String?>(null)
    val job = mutableStateOf<Job?>(null)
    val autoCloseJob = mutableStateOf<Job?>(null)
    val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val tileWorkerLease = OfflinePrepWorkerLease(::TileFetchPriorityScheduler)

    fun begin(context: Context): TileFetchPriorityScheduler {
        val scheduler = tileWorkerLease.acquire()
        MapOfflinePrepRuntime.begin(
            onIdlePolicyChanged = CaltopoClient::CheckIdle,
            onCancel = ::requestCancel,
        )
        MapOfflineDownloadService.start(context.applicationContext)
        return scheduler
    }

    fun finish() {
        tileWorkerLease.release()
        MapOfflineDownloadService.stop()
        MapOfflinePrepRuntime.finish()
        MapCacheMaintenanceScheduler.resumeAfterOfflinePrep()
    }

    fun recoverStalledNetworkCalls(): Int {
        val calls = activeCalls.toList()
        calls.forEach(Call::cancel)
        return calls.size
    }

    fun requestCancel(hideDialog: Boolean = false) {
        cancelRequested.value = true
        progress.value = progress.value.copy(phase = "Cancelling")
        activeCalls.forEach(Call::cancel)
        job.value?.cancel()
        job.value = null
        autoCloseJob.value?.cancel()
        autoCloseJob.value = null
        activeCalls.clear()
        inFlight.value = false
        cancelRequested.value = false
        progress.value = progress.value.copy(phase = "Cancelled")
        if (hideDialog) showDialog.value = false
        finish()
    }
}

internal fun offlinePrepMenuStatus(inFlight: Boolean, progress: OfflinePrepProgress): String? {
    if (!inFlight) return null
    if (progress.totalBytes <= 0L) return progress.phase
    val percent = (progress.fraction * 100.0).toInt()
    return "$percent%"
}

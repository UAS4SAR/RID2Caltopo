package org.ncssar.rid2caltopo.data

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import java.net.URI
import java.util.Locale

/** Per-origin/map clock policy; accounts reading the same map share its budget. */
class FullMapReadRateGate(private val intervalMs: Long = 90_000L) {
    private data class State(var active: String? = null, var lastFinished: Long? = null)
    private val maps = mutableMapOf<String, State>()
    @Synchronized fun tryBegin(map: String, id: String, now: Long): Long {
        maps.entries.removeAll { it.value.active == null && it.value.lastFinished?.let { finished -> now - finished >= intervalMs } == true }
        val state = maps.getOrPut(map) { State() }
        if (state.active != null) return 250L
        val remaining = state.lastFinished?.let { (intervalMs - (now - it)).coerceAtLeast(0L) } ?: 0L
        if (remaining > 0) return remaining
        state.active = id
        return 0L
    }
    @Synchronized fun finish(map: String, id: String, now: Long): Boolean {
        val state = maps[map] ?: return false
        if (state.active != id) return false
        state.lastFinished = now; state.active = null
        return true
    }
}

/** Worker-only client. Waiting never occupies the ordinary updates/network executor. */
object CaltopoFullMapReads {
    const val MINIMUM_INTERVAL_MS = 90_000L
    private val uri = Uri.parse("content://org.ncssar.rid2caltopo.personal-session")
    @JvmStatic fun isFullMapRead(method: String, path: String) = method == "GET" &&
        Regex("/api/v1/map/[A-Za-z0-9]{3,16}/since/0").matches(path)
    @JvmStatic fun mapKey(origin: String, path: String): String {
        val url = URI(origin)
        require(url.scheme == "https" && url.host != null && url.userInfo == null)
        require(isFullMapRead("GET", path))
        val port = if (url.port == -1 || url.port == 443) "" else ":${url.port}"
        return "https://${url.host.lowercase(Locale.ROOT)}$port/${path.split('/')[4].uppercase(Locale.ROOT)}"
    }
    @JvmStatic @JvmOverloads fun acquire(context: Context, mapKey: String,
        cancelled: java.util.function.BooleanSupplier = java.util.function.BooleanSupplier { false }): AutoCloseable {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Full map reads must run on a worker" }
        val id = UUID.randomUUID().toString()
        val binder = Binder()
        val extras = Bundle().apply { putBinder("lease", binder); putString("mapKey", mapKey) }
        while (true) {
            if (Thread.currentThread().isInterrupted || cancelled.asBoolean) throw InterruptedException()
            val result = context.contentResolver.call(uri, "beginFullMapRead", id, extras)
                ?: error("Full map read limiter is unavailable")
            val delay = result.getLong("delayMs", -1L)
            check(delay >= 0) { "Full map read limiter rejected the request" }
            if (delay == 0L) return AutoCloseable {
                context.contentResolver.call(uri, "finishFullMapRead", id, null)
                // Retain the Binder lease until completion; process death also releases it.
                java.lang.ref.Reference.reachabilityFence(binder)
            }
            Thread.sleep(delay.coerceAtMost(1_000L))
        }
    }
}

/** Runs only in the private provider process, sharing each map's budget across callers. */
object CaltopoFullMapReadBroker {
    private val gate = FullMapReadRateGate(CaltopoFullMapReads.MINIMUM_INTERVAL_MS)
    private data class Lease(val map: String, val binder: IBinder, val death: IBinder.DeathRecipient)
    private val leases = mutableMapOf<String, Lease>()
    @Synchronized fun begin(map: String, id: String, binder: IBinder): Long {
        val delay = gate.tryBegin(map, id, SystemClock.elapsedRealtime())
        if (delay != 0L) return delay
        val death = IBinder.DeathRecipient { finish(id) }
        leases[id] = Lease(map, binder, death)
        try { binder.linkToDeath(death, 0) }
        catch (error: android.os.RemoteException) { finish(id); throw error }
        return 0L
    }
    @Synchronized fun finish(id: String) {
        val lease = leases.remove(id) ?: return
        gate.finish(lease.map, id, SystemClock.elapsedRealtime())
        runCatching { lease.binder.unlinkToDeath(lease.death, 0) }
    }
}

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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    @Synchronized fun finish(map: String, id: String, now: Long): Boolean = finish(map, id, now, true)

    /** A failed read releases the lease without starting the 90s cooldown. */
    @Synchronized fun finish(map: String, id: String, now: Long, charge: Boolean): Boolean {
        val state = maps[map] ?: return false
        if (state.active != id) return false
        state.active = null
        if (charge) state.lastFinished = now
        return true
    }
}

/** Closes the full-read lease. Only a completed HTTP success consumes the 90s budget. */
class FullMapReadLease(private val release: (Boolean) -> Unit) : AutoCloseable {
    private var charge = false
    fun succeeded() { charge = true }
    override fun close() { release(charge) }
}

/** Worker-only client. Waiting never occupies the ordinary updates/network executor. */
object CaltopoFullMapReads {
    const val MINIMUM_INTERVAL_MS = 90_000L
    private const val PROBE_TIMEOUT_MS = 3_000L
    private val uri = Uri.parse("content://org.ncssar.rid2caltopo.personal-session")
    private val localGate = FullMapReadRateGate()
    private val probeCalls = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "caltopo-full-read-gate").apply { isDaemon = true }
    }
    @JvmStatic fun isFullMapRead(method: String, path: String) = method == "GET" &&
        Regex("/api/v1/map/[A-Za-z0-9]{3,16}/since/0").matches(path)
    @JvmStatic fun mapKey(origin: String, path: String): String {
        val url = URI(origin)
        require(url.scheme == "https" && url.host != null && url.userInfo == null)
        require(isFullMapRead("GET", path))
        val port = if (url.port == -1 || url.port == 443) "" else ":${url.port}"
        return "https://${url.host.lowercase(Locale.ROOT)}$port/${path.split('/')[4].uppercase(Locale.ROOT)}"
    }
    /**
     * Signed personal reads stay in this process. The cookie probe can be frozen,
     * and a call into it then never returns, so the map open logs nothing.
     */
    @JvmStatic @JvmOverloads fun acquire(context: Context, mapKey: String,
        cancelled: java.util.function.BooleanSupplier = java.util.function.BooleanSupplier { false },
        localOnly: Boolean = false): FullMapReadLease {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Full map reads must run on a worker" }
        if (localOnly) return acquireLocal(mapKey, cancelled)
        val id = UUID.randomUUID().toString()
        val binder = Binder()
        val extras = Bundle().apply { putBinder("lease", binder); putString("mapKey", mapKey) }
        var announced = false
        while (true) {
            if (Thread.currentThread().isInterrupted || cancelled.asBoolean) throw InterruptedException()
            val delay = probeDelay(context, id, extras)
            if (delay == null) {
                CaltopoPersonalAccountConnection.logState("map open full-read probe unavailable; using local gate")
                return acquireLocal(mapKey, cancelled)
            }
            if (delay == 0L) return FullMapReadLease { charge ->
                context.contentResolver.call(uri, if (charge) "finishFullMapRead" else "abandonFullMapRead", id, null)
                // Retain the Binder lease until completion; process death also releases it.
                java.lang.ref.Reference.reachabilityFence(binder)
            }
            if (!announced) {
                CaltopoPersonalAccountConnection.logState("map open waiting full-read gate ${delay}ms")
                announced = true
            }
            Thread.sleep(delay.coerceAtMost(1_000L))
        }
    }

    private fun probeDelay(context: Context, id: String, extras: Bundle): Long? {
        val task = probeCalls.submit<Long?> {
            val result = context.contentResolver.call(uri, "beginFullMapRead", id, extras) ?: return@submit null
            val delay = result.getLong("delayMs", -1L)
            if (delay < 0) null else delay
        }
        return try {
            task.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            CaltopoPersonalAccountConnection.logState("map open full-read probe timed out")
            null
        } catch (error: Exception) {
            CaltopoPersonalAccountConnection.logState("map open full-read probe failed ${error.javaClass.simpleName}")
            null
        }
    }

    private fun acquireLocal(mapKey: String, cancelled: java.util.function.BooleanSupplier): FullMapReadLease {
        val id = UUID.randomUUID().toString()
        var announced = false
        while (true) {
            if (Thread.currentThread().isInterrupted || cancelled.asBoolean) throw InterruptedException()
            val delay = localGate.tryBegin(mapKey, id, SystemClock.elapsedRealtime())
            if (delay == 0L) return FullMapReadLease { charge ->
                localGate.finish(mapKey, id, SystemClock.elapsedRealtime(), charge)
            }
            if (!announced) {
                CaltopoPersonalAccountConnection.logState("map open waiting full-read gate ${delay}ms")
                announced = true
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
    @Synchronized fun finish(id: String) { end(id, true) }
    @Synchronized fun abandon(id: String) { end(id, false) }
    @Synchronized private fun end(id: String, charge: Boolean) {
        val lease = leases.remove(id) ?: return
        gate.finish(lease.map, id, SystemClock.elapsedRealtime(), charge)
        runCatching { lease.binder.unlinkToDeath(lease.death, 0) }
    }
}

package org.ncssar.rid2caltopo.app

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Saved settings and their dependent startup must finish before UI/service actions. */
object SettingsStartup {
    private val gate = ColdStartGate(Executors.newSingleThreadExecutor { work ->
        Thread(work, "r2c-cold-settings").apply { isDaemon = true }
    })
    private val delivery = Executor { Handler(Looper.getMainLooper()).post(it) }
    fun start(load: Runnable) = gate.start(load)
    @JvmStatic fun isReady(): Boolean = gate.isReady
    @JvmStatic fun whenReady(ready: Runnable, failed: Consumer<Throwable>) {
        gate.whenComplete(delivery) { error -> if (error == null) ready.run() else failed.accept(error) }
    }
    fun showWhenReady(activity: android.app.Activity, ready: () -> Unit) {
        if (isReady()) { ready(); return }
        val label = android.widget.TextView(activity).apply {
            text = "Loading saved settings…"
            gravity = android.view.Gravity.CENTER
        }
        activity.setContentView(label)
        whenReady({
            if (!activity.isDestroyed && !activity.isFinishing) ready()
        }, {
            if (!activity.isDestroyed && !activity.isFinishing)
                label.text = "Saved settings could not be loaded. Restart the app to retry. Your saved data has been kept."
        })
    }
    suspend fun awaitReady() = suspendCancellableCoroutine<Unit> { continuation ->
        whenReady({ if (continuation.isActive) continuation.resume(Unit) },
            { if (continuation.isActive) continuation.resumeWithException(it) })
    }
}

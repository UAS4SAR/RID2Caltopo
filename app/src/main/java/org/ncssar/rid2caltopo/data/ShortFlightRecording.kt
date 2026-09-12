package org.ncssar.rid2caltopo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun isShortFlight(durationSeconds: Double, distanceMeters: Double): Boolean =
    durationSeconds.isFinite() && distanceMeters.isFinite() && durationSeconds >= 0 &&
        distanceMeters >= 0 && durationSeconds < 60 && distanceMeters < 160.9344

internal data class ShortFlightPrompt(val id: String, val aircraft: String, val deadlineMillis: Long)

internal class ShortFlightRecordingGate(private val now: () -> Long) {
    private val callbacks = linkedMapOf<String, Runnable>()
    private val mutable = MutableStateFlow<List<ShortFlightPrompt>>(emptyList())
    val prompts = mutable.asStateFlow()
    private var active = false

    @Synchronized fun setActive(value: Boolean) {
        active = value
        if (!value) mutable.value.toList().forEach { decide(it.id, true) }
    }
    @Synchronized fun request(aircraft: String, seconds: Double, meters: Double, keep: Runnable): Boolean {
        if (!active || !isShortFlight(seconds, meters)) return false
        val prompt = ShortFlightPrompt(UUID.randomUUID().toString(), aircraft, now() + 10_000)
        callbacks[prompt.id] = keep
        mutable.value = mutable.value + prompt
        return true
    }
    @Synchronized fun decide(id: String, record: Boolean) {
        val prompt = mutable.value.firstOrNull { it.id == id } ?: return
        val keep = callbacks.remove(id) ?: return
        mutable.value = mutable.value.filterNot { it.id == id }
        if (record || now() >= prompt.deadlineMillis) keep.run()
    }
    @Synchronized fun expire() {
        mutable.value.filter { now() >= it.deadlineMillis }.forEach { decide(it.id, true) }
    }
    fun remainingSeconds(prompt: ShortFlightPrompt): Long = ((prompt.deadlineMillis - now() + 999) / 1000).coerceIn(0, 10)
}

object ShortFlightRecording {
    internal val gate = ShortFlightRecordingGate { System.nanoTime() / 1_000_000 }
    init {
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "short-flight-choice").apply { isDaemon = true } }
            .scheduleAtFixedRate({ gate.expire() }, 100, 100, TimeUnit.MILLISECONDS)
    }
    @JvmStatic fun request(aircraft: String, seconds: Double, meters: Double, keep: Runnable) = gate.request(aircraft, seconds, meters, keep)
}

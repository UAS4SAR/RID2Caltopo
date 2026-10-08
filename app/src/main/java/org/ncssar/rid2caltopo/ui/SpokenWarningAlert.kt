package org.ncssar.rid2caltopo.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.ProximityAlertConsent

enum class SpokenWarningKind(val phrase: String) {
    DroneTelemetry("Drone Telemetry"),
    Altitude("Altitude"),
    Proximity("Proximity"),
    ControllerSignalStrength("Stream Wi-Fi Signal Strength"),
    BridgeNotDetected("Bridge Not Detected"),
    VideoStreamRequest("Video Stream Request"),
}

data class SpokenWarningRequest(
    val requestId: Long,
    val kind: SpokenWarningKind,
    val phrase: String,
    val phrases: List<String>,
    val volumeFraction: Float,
    val playbackKinds: List<AlertBellKind?> = phrases.map { kind.toAlertBellKind() },
)

object SpokenWarningCenter {
    private data class WarningKey(
        val kind: SpokenWarningKind,
        val sourceKey: String,
    )

    private val _requests = MutableStateFlow<SpokenWarningRequest?>(null)
    val requests: StateFlow<SpokenWarningRequest?> = _requests.asStateFlow()

    private val lastRequestedAtMsByKey = linkedMapOf<WarningKey, Long>()
    private var nextRequestId = 1L

    @Synchronized
    fun requestWarning(
        kind: SpokenWarningKind,
        sourceKey: String,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = 0L,
        volumeFraction: Float = 1.0f,
    ) {
        requestWarningSequence(
            kinds = listOf(kind),
            sourceKey = sourceKey,
            nowMs = nowMs,
            cooldownMs = cooldownMs,
            volumeFraction = volumeFraction
        )
    }

    @Synchronized
    fun requestWarningSequence(
        kinds: List<SpokenWarningKind>,
        sourceKey: String,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = 0L,
        volumeFraction: Float = 1.0f,
    ) {
        val firstKind = kinds.firstOrNull() ?: return
        // Session mute (alert-bell panel) suppresses speech; settings enable/disable stay separate.
        // Settings "audio alarm test" still speaks so operators can verify volume.
        if (sourceKey != "audio-alarm-test" && kinds.any { AlertBellCenter.isMuted(it) }) return
        val key = WarningKey(firstKind, sourceKey)
        val lastRequestedAtMs = lastRequestedAtMsByKey[key]
        if (lastRequestedAtMs != null && nowMs - lastRequestedAtMs < cooldownMs) return

        lastRequestedAtMsByKey[key] = nowMs
        publish(firstKind, firstKind.phrase, kinds.map { it.phrase }, volumeFraction, kinds.map { if (sourceKey == "audio-alarm-test") null else it.toAlertBellKind() })
    }

    /**
     * Single-slot hand-off to [SpokenWarningPlayer]. A pending (not yet played)
     * proximity phrase is never dropped by a later warning in the same tick: the
     * new phrases are queued after it instead of replacing it.
     */
    private fun publish(kind: SpokenWarningKind, phrase: String, phrases: List<String>, volumeFraction: Float, playbackKinds: List<AlertBellKind?> = phrases.map { kind.toAlertBellKind() }) {
        val pending = _requests.value
        val keepProximity = pending != null && pending.kind == SpokenWarningKind.Proximity &&
            kind != SpokenWarningKind.Proximity
        _requests.value = if (keepProximity) {
            pending!!.copy(
                requestId = nextRequestId++,
                phrases = pending.phrases + phrases,
                playbackKinds = pending.playbackKinds + playbackKinds,
                volumeFraction = maxOf(pending.volumeFraction, volumeFraction.coerceIn(0f, 1f)),
            )
        } else {
            SpokenWarningRequest(
                requestId = nextRequestId++,
                kind = kind,
                phrase = phrase,
                phrases = phrases,
                playbackKinds = playbackKinds,
                volumeFraction = volumeFraction.coerceIn(0f, 1f),
            )
        }
    }

    @Synchronized
    fun requestAudioAlarmTest(nowMs: Long = System.currentTimeMillis()) {
        requestWarningSequence(
            kinds = listOf(
                SpokenWarningKind.DroneTelemetry,
                SpokenWarningKind.Altitude,
                SpokenWarningKind.Proximity,
                SpokenWarningKind.ControllerSignalStrength,
                SpokenWarningKind.BridgeNotDetected
            ),
            sourceKey = "audio-alarm-test",
            nowMs = nowMs,
            cooldownMs = 0L
        )
    }

    @Synchronized
    fun requestSpokenPhrase(
        kind: SpokenWarningKind,
        sourceKey: String,
        phrase: String,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = 0L,
        volumeFraction: Float = 1.0f,
    ) {
        if (AlertBellCenter.isMuted(kind)) return
        val key = WarningKey(kind, sourceKey)
        val lastRequestedAtMs = lastRequestedAtMsByKey[key]
        if (lastRequestedAtMs != null && nowMs - lastRequestedAtMs < cooldownMs) return
        lastRequestedAtMsByKey[key] = nowMs
        publish(kind, phrase, listOf(phrase), volumeFraction)
    }

    @Synchronized
    fun consume(requestId: Long): SpokenWarningRequest? {
        val current = _requests.value ?: return null
        if (current.requestId != requestId) return null
        _requests.value = null
        return current
    }

    @Synchronized
    fun cancelProximityWarning() {
        if (_requests.value?.kind == SpokenWarningKind.Proximity) _requests.value = null
        lastRequestedAtMsByKey.keys.removeAll { it.kind == SpokenWarningKind.Proximity }
    }

    @Synchronized
    fun resetForTests() {
        _requests.value = null
        lastRequestedAtMsByKey.clear()
        nextRequestId = 1L
    }
}

/**
 * Plays [SpokenWarningCenter] requests with a process-scoped TextToSpeech engine.
 * This used to live in a Compose effect, which does not run while the activity is
 * stopped (display off). Behaviour is otherwise unchanged: USAGE_ALARM speech,
 * rate 0.88, pitch 0.82, QUEUE_FLUSH, alarm-volume multiplier, and proximity
 * speech stops when proximity advisories are turned off.
 */
object SpokenWarningPlayer {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var speakingKind: SpokenWarningKind? = null

    private val pendingPlayback = java.util.concurrent.ConcurrentHashMap<String, AlertBellKind>()
    private var audioManager: android.media.AudioManager? = null

    fun start(context: Context, scope: CoroutineScope) {
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            scope.launch { onInitialized(status == TextToSpeech.SUCCESS) }
        }
        scope.launch {
            SpokenWarningCenter.requests.collect { request ->
                if (request != null) play(request)
            }
        }
        scope.launch {
            ProximityAlertConsent.state
                .map { it.enabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (!enabled && speakingKind == SpokenWarningKind.Proximity) {
                        pendingPlayback.clear()
                        tts?.stop()
                        speakingKind = null
                    }
                }
        }
    }

    fun stop() {
        val engine = tts ?: return
        tts = null
        ready = false
        pendingPlayback.clear()
        speakingKind = null
        try {
            engine.stop()
            engine.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun onInitialized(success: Boolean) {
        val engine = tts ?: return
        ready = success
        if (!success) return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {
                val kind = pendingPlayback.remove(id ?: return) ?: return
                if (!AlertBellCenter.isMuted(kind) && (audioManager?.getStreamVolume(android.media.AudioManager.STREAM_ALARM) ?: 0) > 0) {
                    AlertBellCenter.recordPlayback(kind)
                }
            }
            override fun onDone(id: String?) { id?.let { pendingPlayback.remove(it) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { id?.let { pendingPlayback.remove(it) } }
            override fun onStop(id: String?, interrupted: Boolean) { id?.let { pendingPlayback.remove(it) } }
        })
        engine.setSpeechRate(0.88f)
        engine.setPitch(0.82f)
        SpokenWarningCenter.requests.value?.let { play(it) }
    }

    private fun play(pendingRequest: SpokenWarningRequest) {
        val engine = tts ?: return
        if (!ready) return
        val currentRequest = SpokenWarningCenter.consume(pendingRequest.requestId) ?: return
        if (currentRequest.kind == SpokenWarningKind.Proximity && !ProximityAlertConsent.state.value.enabled) return
        if (AlertBellCenter.isMuted(currentRequest.kind)) return
        speakingKind = currentRequest.kind
        val volume = (currentRequest.volumeFraction * CaltopoClient.GetAlarmVolumeMultiplier())
            .coerceIn(0f, 1f)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        pendingPlayback.clear()
        engine.stop()
        currentRequest.phrases.forEachIndexed { index, phrase ->
            val kind = currentRequest.playbackKinds.getOrNull(index)
            if (kind != null && AlertBellCenter.isMuted(kind)) return@forEachIndexed
            val utteranceId = "r2c-spoken-warning-${currentRequest.requestId}-$index"
            if (volume > 0 && kind != null) pendingPlayback[utteranceId] = kind
            val result = engine.speak(
                phrase,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                params,
                utteranceId
            )
            if (result == TextToSpeech.ERROR) pendingPlayback.remove(utteranceId)
        }
    }
}

/** Starts process-scoped alert speech; it keeps working while the display is off. */
@Composable
fun SpokenWarningAlertHost() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AlertSpeechCoordinator.start(context.applicationContext)
    }
}

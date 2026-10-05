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
        val key = WarningKey(firstKind, sourceKey)
        val lastRequestedAtMs = lastRequestedAtMsByKey[key]
        if (lastRequestedAtMs != null && nowMs - lastRequestedAtMs < cooldownMs) return

        lastRequestedAtMsByKey[key] = nowMs
        val phrases = kinds.map { it.phrase }
        _requests.value = SpokenWarningRequest(
            requestId = nextRequestId++,
            kind = firstKind,
            phrase = firstKind.phrase,
            phrases = phrases,
            volumeFraction = volumeFraction.coerceIn(0f, 1f),
        )
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
        val key = WarningKey(kind, sourceKey)
        val lastRequestedAtMs = lastRequestedAtMsByKey[key]
        if (lastRequestedAtMs != null && nowMs - lastRequestedAtMs < cooldownMs) return
        lastRequestedAtMsByKey[key] = nowMs
        _requests.value = SpokenWarningRequest(
            requestId = nextRequestId++,
            kind = kind,
            phrase = phrase,
            phrases = listOf(phrase),
            volumeFraction = volumeFraction.coerceIn(0f, 1f),
        )
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

    fun start(context: Context, scope: CoroutineScope) {
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
        engine.setSpeechRate(0.88f)
        engine.setPitch(0.82f)
        SpokenWarningCenter.requests.value?.let { play(it) }
    }

    private fun play(pendingRequest: SpokenWarningRequest) {
        val engine = tts ?: return
        if (!ready) return
        val currentRequest = SpokenWarningCenter.consume(pendingRequest.requestId) ?: return
        if (currentRequest.kind == SpokenWarningKind.Proximity && !ProximityAlertConsent.state.value.enabled) return
        speakingKind = currentRequest.kind
        val volume = (currentRequest.volumeFraction * CaltopoClient.GetAlarmVolumeMultiplier())
            .coerceIn(0f, 1f)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        currentRequest.phrases.forEachIndexed { index, phrase ->
            engine.speak(
                phrase,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                params,
                "r2c-spoken-warning-${currentRequest.requestId}-$index"
            )
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

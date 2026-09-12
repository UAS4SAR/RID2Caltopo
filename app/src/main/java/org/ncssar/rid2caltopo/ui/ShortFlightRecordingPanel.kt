package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.ncssar.rid2caltopo.data.ShortFlightRecording

@Composable
fun ShortFlightRecordingPanel() {
    val gate = ShortFlightRecording.gate
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> gate.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        owner.lifecycle.addObserver(observer)
        gate.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { owner.lifecycle.removeObserver(observer); gate.setActive(false) }
    }
    val prompts by gate.prompts.collectAsState()
    prompts.forEach { prompt ->
        key(prompt.id) {
            var remaining by remember { mutableLongStateOf(gate.remainingSeconds(prompt)) }
            LaunchedEffect(prompt.id) {
                while (true) { remaining = gate.remainingSeconds(prompt); delay(100) }
            }
            Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                Column(Modifier.padding(12.dp)) {
                    Text("Short flight — Record (Yes/No)?", style = MaterialTheme.typography.titleMedium)
                    Text("${prompt.aircraft} · Yes automatically in ${remaining}s")
                    Row {
                        Button(onClick = { gate.decide(prompt.id, true) }) { Text("Yes — Record") }
                        TextButton(onClick = { gate.decide(prompt.id, false) }) { Text("No") }
                    }
                }
            }
        }
    }
}

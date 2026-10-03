package org.ncssar.rid2caltopo.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.data.*

@Composable
fun rememberPersonalCredentialsAction(onReady: () -> Unit): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val currentOnReady by rememberUpdatedState(onReady)
    fun load(onFailure: () -> Unit) {
        CaltopoPersonalSession.initialize(context)
        busy = true
        val generation = CaltopoPersonalSession.generation
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { CaltopoPersonalSession.readCatalog() } }
            busy = false
            if (generation != CaltopoPersonalSession.generation) return@launch
            result.fold(onSuccess = { json ->
                runCatching { CaltopoPersonalSession.acceptCatalog(json) }
                    .onSuccess { currentOnReady() }.onFailure { CaltopoClient.ShowToast("Personal maps could not load. Please retry.") }
            }, onFailure = {
                if (it is PersonalCaltopoLoginRequired) onFailure()
                else CaltopoClient.ShowToast("Personal maps could not load. Check your connection and retry.")
            })
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Close/Back may return RESULT_CANCELED after a successful browser sign-in.
        // Verify the account and catalog before choosing the next screen; never infer
        // authentication from the activity result or reopen login on cancellation.
        load { /* No authenticated session: leave the sign-in choices available. */ }
    }
    return busy to { load { launcher.launch(Intent(context, CaltopoPersonalProbeActivity::class.java).putExtra("picker", true).putExtra("catalog", true)) } }
}

@Composable
fun PersonalCaltopoLoginButton(modifier: Modifier = Modifier, onReady: () -> Unit = {}) {
    val (busy, open) = rememberPersonalCredentialsAction(onReady)
    Button(onClick = open, enabled = !busy, modifier = modifier) {
        Text(if (busy) "Loading personal maps…" else "Personal: ${CaltopoPersonalSession.username.ifBlank { "Sign in" }}")
    }
}

/** Explicit account editing never selects an incident map. */
@Composable
fun rememberPersonalAccountEditor(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val generation = CaltopoPersonalSession.generation
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { CaltopoPersonalSession.readCatalog() } }
            if (generation != CaltopoPersonalSession.generation) return@launch
            result.onSuccess { CaltopoPersonalSession.acceptCatalog(it) }.onFailure {
                if (it is PersonalCaltopoLoginRequired) {
                    CaltopoPersonalSession.username = ""
                    CaltopoPersonalSession.maps = emptyList()
                    CaltopoPersonalSession.catalogReady = false
                }
            }
        }
    }
    return {
        CaltopoPersonalSession.initialize(context)
        launcher.launch(Intent(context, CaltopoPersonalProbeActivity::class.java).putExtra("picker", true).putExtra("webOnly", true))
    }
}

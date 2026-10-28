package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.storage.OperationalStorage
import org.ncssar.rid2caltopo.storage.TemporaryStorage

@Composable
internal fun OtherStorageDialog(onBrowseArchive: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val state by OperationalStorage.status.collectAsState()
    if (pending != null) {
        val action = pending!!
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text("Clear $action?") }, text = {
            Column { Text(when (action) {
                "downloaded support data" -> "Removes saved support downloads. They can be downloaded again."
                "unused temporary files" -> "Removes abandoned files from earlier app runs. Current working files remain protected."
                else -> "Removes abandoned staging copies and unpublished map generations. The current retained files remain."
            })
            if (result.isNotEmpty()) Text(result) }
        }, confirmButton = { StorageActionButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val count = withContext(Dispatchers.IO) {
                        when (action) {
                            "downloaded support data" -> OperationalStorage.clearSupportData(context)
                            "unused temporary files" -> { TemporaryStorage.cleanAbandoned(context); null }
                            else -> OperationalStorage.clearRecovery()
                        }
                    }
                    result = count?.let { "Freed ${formatStorageBytes(it)}." } ?: "Unused temporary files cleared. Current working files remain."
                    pending = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { result = error.message ?: "Storage could not be cleared." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Clearing…" else "Clear") } }, dismissButton = { StorageActionButton(enabled = !busy, onClick = { pending = null }) { Text("Cancel") } })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Other Storage") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StorageActionButton(enabled = !state.busy, onClick = { result = ""; pending = "downloaded support data" }) { Text("Clear Downloaded Support Data") }
            StorageActionButton(enabled = !state.busy, onClick = { result = ""; pending = "unused temporary files" }) { Text("Clear Unused Temporary Files") }
            StorageActionButton(enabled = !state.busy, onClick = { result = ""; pending = "abandoned recovery copies" }) { Text("Clear Abandoned Recovery Copies") }
            StorageActionButton(onClick = onBrowseArchive) { Text("Browse Other Archive Files") }
            if (!state.ready) StorageActionButton(enabled = !state.busy, onClick = { OperationalStorage.retry(context) }) { Text("Retry Archive Preparation") }
            if (state.message.isNotEmpty()) Text(state.message)
            if (result.isNotEmpty()) Text(result)
        }
    }, confirmButton = { StorageActionButton(onClick = onDismiss) { Text("Close") } })
}

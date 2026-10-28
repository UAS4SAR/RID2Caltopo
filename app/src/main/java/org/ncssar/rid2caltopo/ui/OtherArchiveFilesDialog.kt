package org.ncssar.rid2caltopo.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.storage.DocumentArchiveTree
import org.ncssar.rid2caltopo.storage.VerifiedArchiveTree

internal fun isOtherArchivePath(path: String): Boolean {
    VerifiedArchiveTree.checked(path)
    val top = path.substringBefore('/').lowercase()
    return top !in setOf("cache", "application-logs") && !top.startsWith(".r2c-") &&
        !org.ncssar.rid2caltopo.app.isDatedArchiveDirectoryName(path.substringBefore('/')) &&
        !Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(top)
}

private data class OtherArchiveItem(val path: String, val name: String, val directory: Boolean, val bytes: Long, val rootURI: String)

@Composable
internal fun OtherArchiveFilesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<OtherArchiveItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<OtherArchiveItem?>(null) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(path, refresh) {
        loading = true
        try {
            items = withContext(Dispatchers.IO) {
                val root = CaltopoClient.GetArchiveDir() ?: error("Reconnect Archive Dir to browse these files.")
                val tree = DocumentArchiveTree(context, root)
                tree.list(path).map { entry ->
                    val relative = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                    OtherArchiveItem(relative, entry.name, entry.directory, entry.bytes, root.uri.toString())
                }.filter { isOtherArchivePath(it.path) }.sortedBy { it.name }
            }
            message = ""
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { message = error.message ?: "Archive files are unavailable." }
        finally { loading = false }
    }
    pending?.let { selected ->
        AlertDialog(onDismissRequest = { if (!deleting) pending = null }, title = { Text("Delete ${selected.name}?") }, text = {
            Column { Text(if (selected.directory) "Permanently deletes this folder and its contents." else "Permanently deletes this file."); if (message.isNotEmpty()) Text(message) }
        }, confirmButton = { StorageActionButton(enabled = !deleting, onClick = {
            deleting = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        check(isOtherArchivePath(selected.path))
                        val root = CaltopoClient.GetArchiveDir() ?: error("Archive Dir is unavailable.")
                        check(root.uri.toString() == selected.rootURI) { "Archive Dir changed. Reopen the browser." }
                        DocumentArchiveTree(context, root).delete(selected.path)
                    }
                    pending = null; refresh++
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "The entry could not be deleted." }
                finally { deleting = false }
            }
        }) { Text(if (deleting) "Deleting…" else "Delete") } }, dismissButton = { StorageActionButton(enabled = !deleting, onClick = { pending = null }) { Text("Cancel") } })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (path.isEmpty()) "Other Archive Files" else path.substringAfterLast('/')) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) { CircularProgressIndicator(); Text("Loading files…") }
            if (message.isNotEmpty()) Text(message)
            if (!loading && items.isEmpty() && message.isEmpty()) Text("No other files in this folder.")
            LazyColumn(Modifier.fillMaxWidth().height(440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.path }) { item -> Column {
                StorageActionButton(onClick = {
                    if (item.directory) path = item.path
                    else scope.launch {
                        try {
                            val uri = withContext(Dispatchers.IO) {
                                val root = CaltopoClient.GetArchiveDir() ?: error("Archive Dir is unavailable.")
                                check(root.uri.toString() == item.rootURI)
                                val original = DocumentArchiveTree(context, root).uri(item.path)
                                if (original.scheme == "file") androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(requireNotNull(original.path))) else original
                            }
                            val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.name.substringAfterLast('.').lowercase()) ?: "application/octet-stream"
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (error: Exception) { message = error.message ?: "No viewer is available." }
                    }
                }) { Text(if (item.directory) "${item.name}/" else "${item.name} — ${formatStorageBytes(item.bytes)}") }
                TextButton(onClick = { message = ""; pending = item }) { Text("Delete ${item.name}") }
            } }
            }
        }
    }, confirmButton = { StorageActionButton(onClick = onDismiss) { Text("Close") } }, dismissButton = {
        if (path.isNotEmpty()) StorageActionButton(onClick = { path = path.substringBeforeLast('/', "") }) { Text("Back") }
    })
}

package org.ncssar.rid2caltopo.data;

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.ui.UIEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilterChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import org.ncssar.rid2caltopo.ui.MapBrowserProfileOption


fun formatCaltopoDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Date Unknown"
    val date = Date(timestamp)
    val sdf = SimpleDateFormat("EEE MMM d HH:mm:ss z yyyy", Locale.US)
    return sdf.format(date)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CaltopoHybridBrowser(
    rootNodes: List<CaltopoNode>,
    profileOptions: List<MapBrowserProfileOption>,
    selectedProfileId: String,
    onUIEvent: (UIEvent) -> Unit,
    loading: Boolean = false
) {
    var credentialsExpanded by remember { mutableStateOf(false) }
    val editPersonal = org.ncssar.rid2caltopo.ui.rememberPersonalAccountEditor()
    val (personalBusy, selectPersonal) = org.ncssar.rid2caltopo.ui.rememberPersonalCredentialsAction {
        onUIEvent(UIEvent.BrowseProfileSelected("personal"))
    }
    val navigationStack = remember(rootNodes) { mutableStateListOf(rootNodes) }
    val currentItems = navigationStack.lastOrNull() ?: rootNodes
    val tag = "CaltopoHybridBrowser"

    // Force the Surface to take up the space it's given
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Browser Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onUIEvent(UIEvent.DismissRequested) }) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
                Text(
                    text = "Select Map",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            // Filter the current list based on the query
            var searchQuery by remember { mutableStateOf("") }

            fun allMaps(nodes: List<CaltopoNode>): List<CaltopoNode.MapNode> = nodes.flatMap {
                when (it) { is CaltopoNode.Directory -> allMaps(it.children); is CaltopoNode.MapNode -> listOf(it) }
            }
            val filteredItems = remember(rootNodes, currentItems, searchQuery) {
                if (searchQuery.isEmpty()) currentItems
                else allMaps(rootNodes).distinctBy { it.id }.filter { it.title.contains(searchQuery, ignoreCase = true) }
            }
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = LocalFocusManager.current

            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                placeholder = { Text("Search maps...") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), // Change 'Enter' to 'Done'
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        // This ensures the "Done" button actually dismisses the keyboard
                    }
                )
            )

            androidx.compose.foundation.layout.Box {
                androidx.compose.material3.TextButton(onClick = { credentialsExpanded = true }, enabled = !personalBusy) {
                    Text(if (personalBusy) "Loading personal maps…" else "Credentials: " +
                        if (CaltopoPersonalSession.browsingPersonal) "Personal: ${CaltopoPersonalSession.username.ifBlank { "Sign in" }}" else
                        (profileOptions.firstOrNull { it.profileId == selectedProfileId }?.label ?: "Org"))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Select credentials")
                }
                androidx.compose.material3.DropdownMenu(expanded = credentialsExpanded, onDismissRequest = { credentialsExpanded = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Personal: ${CaltopoPersonalSession.username.ifBlank { "Sign in" }}") },
                        onClick = { credentialsExpanded = false; selectPersonal() }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Edit personal account") },
                        onClick = { credentialsExpanded = false; editPersonal() }
                    )
                    profileOptions.forEach { option ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            credentialsExpanded = false
                            onUIEvent(UIEvent.BrowseProfileSelected(option.profileId))
                        })
                    }
                }
            }

            HorizontalDivider()
            if (loading) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Loading available maps…")
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize().weight(1f)) {
                // 1. ADD THE BACK BUTTON AS THE FIRST ITEM
                if (navigationStack.size > 1) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navigationStack.removeAt(navigationStack.size - 1) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Back",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        HorizontalDivider()
                    }
                }
                items(filteredItems) { node ->
                    // We MUST wrap the row in a clickable modifier
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when (node) {
                                    is CaltopoNode.Directory -> {
                                        CTDebug(tag, "Directory Clicked: ${node.title}")
                                        navigationStack.add(node.children)
                                    }

                                    is CaltopoNode.MapNode -> {
                                        CTDebug(tag, "Map Clicked: ${node.title}")
                                        onUIEvent(UIEvent.MapSelected(node))
                                    }
                                }
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (node) {
                            is CaltopoNode.Directory -> {
                                // Should call a DirectoryRow or similar
                                DirectoryRow(node) {
                                    searchQuery = ""
                                    navigationStack.add(node.children)
                                }
                            }
                            is CaltopoNode.MapNode -> {
                                MapRow(node = node)
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}




@Composable
fun MapRow(
    node: CaltopoNode.MapNode,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(text = "${node.title}", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = formatCaltopoDate(node.updated),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 36.dp)
        )
    }
}


@Composable
fun DirectoryRow(dir: CaltopoNode.Directory, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFFFFD700)) // Gold
        Spacer(Modifier.width(12.dp))
        Text(dir.title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.Gray)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 44.dp))
}

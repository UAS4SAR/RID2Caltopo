package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val AlertBellOrange = Color(0xFFF57C00)
private val AlertBellWhite = Color(0xFFFFFFFF)
private val AlertBellRed = Color(0xFFE53935)
private val AlertBellIdleBackdrop = Color(0xFF424242)

@Composable
fun AlertStatusBell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Larger circled control for the status-chip row (iOS proximity-bell location). */
    chipRow: Boolean = false,
) {
    val state by AlertBellCenter.uiState.collectAsStateWithLifecycle()
    if (!state.showBell) return
    val tone = state.aggregateColor.name.lowercase()
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = "Alert panel, $tone status"
        },
    ) {
        val size = if (chipRow) 48.dp else 28.dp
        val icon = if (chipRow) 22.dp else 18.dp
        val stroke = state.aggregateColor.toBellTint(panel = false)
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    if (chipRow) MaterialTheme.colorScheme.surfaceVariant else AlertBellIdleBackdrop,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = if (state.aggregateColor == AlertBellColor.White) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    stroke
                },
                modifier = Modifier.size(icon),
            )
        }
    }
}

@Composable
fun AlertStatusPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val state by AlertBellCenter.uiState.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alerts") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Tap a bell to mute or unmute speech for that alert. Dismissing this panel only hides it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AlertBellKind.entries.forEach { kind ->
                    val muted = state.isMuted(kind)
                    val color = state.colors[kind] ?: AlertBellColor.White
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { AlertBellCenter.toggleMuted(kind) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(kind.displayName, modifier = Modifier.weight(1f))
                        IconButton(onClick = { AlertBellCenter.toggleMuted(kind) }) {
                            Icon(
                                imageVector = if (muted) {
                                    Icons.Default.NotificationsOff
                                } else {
                                    Icons.Default.Notifications
                                },
                                contentDescription = if (muted) {
                                    "Unmute ${kind.displayName}"
                                } else {
                                    "Mute ${kind.displayName}"
                                },
                                tint = if (muted) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    color.toBellTint(panel = true)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun AlertBellColor.toBellTint(panel: Boolean = false): Color = when (this) {
    // Idle “white”: literal white on the dark top-bar chip; onSurface in the panel.
    AlertBellColor.White -> if (panel) MaterialTheme.colorScheme.onSurface else AlertBellWhite
    AlertBellColor.Orange -> AlertBellOrange
    AlertBellColor.Red -> AlertBellRed
}

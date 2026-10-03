package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.ncssar.rid2caltopo.ui.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.R
import java.util.Locale

@Composable
fun SignalLossAlertButton(
    flights: List<DroneSignalLossFlightUiState>,
    allMuted: Boolean,
    onClick: () -> Unit
) {
    if (flights.isEmpty()) return
    IconButton(onClick = onClick) {
        SignalLossIcon(
            muted = allMuted,
            tint = if (allMuted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
}

@Composable
fun SignalLossAlertDialog(
    visible: Boolean,
    flights: List<DroneSignalLossFlightUiState>,
    onDismiss: () -> Unit,
    onToggleMuted: (flightKey: String, muted: Boolean) -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signal Loss Alerts") },
        text = {
            if (flights.isEmpty()) {
                Text("No drones currently need signal-loss attention.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    flights.forEach { flight ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(flight.mappedId)
                                Text(
                                    text = signalLossAlertSummary(flight),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    onToggleMuted(flight.flightKey, !flight.muted)
                                }
                            ) {
                                SignalLossIcon(
                                    muted = flight.muted,
                                    tint = if (flight.muted) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun SignalLossIcon(
    muted: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val slashColor = MaterialTheme.colorScheme.error
    Box(modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_ekg),
            contentDescription = null,
            tint = tint
        )
        if (muted) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawLine(
                    color = slashColor,
                    start = Offset(size.width * 0.18f, size.height * 0.82f),
                    end = Offset(size.width * 0.82f, size.height * 0.18f),
                    strokeWidth = size.minDimension * 0.12f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun signalLossAlertSummary(flight: DroneSignalLossFlightUiState): String {
    if (flight.alerting) {
        val idleSeconds = (flight.signalIdleMs ?: 0L) / 1000.0
        val distanceFeet = flight.distanceFromTabletFt ?: 0.0
        return String.format(
            Locale.US,
            if (flight.bridgeRecentlySeen) {
                "Bridge active; drone location stale for %.1f s at %.0f ft from tablet"
            } else {
                "Drone signal lost for %.1f s at %.0f ft from tablet"
            },
            idleSeconds,
            distanceFeet
        )
    }
    return "Muted for this flight"
}

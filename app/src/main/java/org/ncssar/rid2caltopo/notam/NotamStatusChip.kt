package org.ncssar.rid2caltopo.notam

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.airspace.AirspaceChipSeverity
import org.ncssar.rid2caltopo.airspace.AirspaceUiState

@Composable
fun NotamStatusChip(
    state: NotamUiState,
    airspaceState: AirspaceUiState? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
) {
    if (!state.visible && airspaceState?.visible != true) return
    val useAirspaceLabel = shouldUseAirspaceStatus(state.visible, airspaceState)
    val displaySeverity = when {
        !useAirspaceLabel -> state.chipSeverity
        airspaceState?.chipSeverity == AirspaceChipSeverity.Danger -> NotamChipSeverity.Danger
        airspaceState?.chipSeverity == AirspaceChipSeverity.Caution -> NotamChipSeverity.Caution
        airspaceState?.chipSeverity == AirspaceChipSeverity.Normal -> NotamChipSeverity.Normal
        else -> NotamChipSeverity.Neutral
    }
    val displayLabel = conciseSafetyStatusLabel(
        useAirspaceLabel = useAirspaceLabel,
        severity = displaySeverity,
        detailedLabel = if (useAirspaceLabel) {
            airspaceState?.chipLabel.orEmpty()
        } else {
            state.chipLabel
        }
    )
    val colors = when (displaySeverity) {
        NotamChipSeverity.Danger -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0xFFD32F2F),
            labelColor = Color.White
        )
        NotamChipSeverity.Caution -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0xFFF57C00),
            labelColor = Color.White
        )
        NotamChipSeverity.Normal -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NotamChipSeverity.Neutral -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Row(
        modifier = modifier.padding(outerPadding),
        horizontalArrangement = Arrangement.Start
    ) {
        AssistChip(
            onClick = onClick,
            label = {
                Text(
                    text = displayLabel,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            },
            colors = colors
        )
    }
}

internal fun shouldUseAirspaceStatus(
    notamVisible: Boolean,
    airspaceState: AirspaceUiState?
): Boolean = airspaceState != null &&
    airspaceState.visible &&
    (!notamVisible || airspaceState.chipSeverity != AirspaceChipSeverity.Normal)

internal fun conciseSafetyStatusLabel(
    useAirspaceLabel: Boolean,
    severity: NotamChipSeverity,
    detailedLabel: String
): String = when {
    detailedLabel.contains("updating", true) || detailedLabel.contains("unavailable", true) ||
        detailedLabel.contains("stale", true) || detailedLabel.contains("pending", true) -> detailedLabel
    useAirspaceLabel && severity == NotamChipSeverity.Danger -> "Authorization required"
    useAirspaceLabel && severity == NotamChipSeverity.Caution -> "Airspace nearby"
    useAirspaceLabel && severity == NotamChipSeverity.Normal -> detailedLabel
    useAirspaceLabel -> conciseNeutralLabel(detailedLabel, "Airspace status")
    severity == NotamChipSeverity.Danger -> "NOTAM warning"
    severity == NotamChipSeverity.Caution -> "NOTAMs nearby"
    severity == NotamChipSeverity.Normal -> detailedLabel
    else -> conciseNeutralLabel(detailedLabel, "NOTAM status")
}

private fun conciseNeutralLabel(detailedLabel: String, fallback: String): String =
    detailedLabel.trim().takeIf { it.length <= 24 } ?: fallback

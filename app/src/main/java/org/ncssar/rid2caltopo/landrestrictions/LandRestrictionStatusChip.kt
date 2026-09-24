package org.ncssar.rid2caltopo.landrestrictions

import androidx.compose.foundation.layout.PaddingValues
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

@Composable
fun LandRestrictionStatusChip(
    state: LandRestrictionUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
) {
    if (!state.visible) return
    val colors = when (state.severity) {
        LandRestrictionSeverity.Danger -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0xFFD32F2F), labelColor = Color.White
        )
        LandRestrictionSeverity.Caution -> AssistChipDefaults.assistChipColors(
            containerColor = Color(0xFFF57C00), labelColor = Color.White
        )
        LandRestrictionSeverity.Normal -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LandRestrictionSeverity.Neutral -> AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = conciseLandStatusLabel(state),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        },
        colors = colors,
        modifier = modifier.padding(outerPadding)
    )
}

internal fun conciseLandStatusLabel(state: LandRestrictionUiState): String =
    if (listOf("updating", "unavailable", "stale", "pending").any { state.chipLabel.contains(it, ignoreCase = true) }) state.chipLabel
    else when (state.severity) {
        LandRestrictionSeverity.Danger -> "Land restricted"
        LandRestrictionSeverity.Caution -> "Land rules nearby"
        LandRestrictionSeverity.Normal -> "No mapped restrictions"
        LandRestrictionSeverity.Neutral ->
            state.chipLabel.trim().takeIf { it.length <= 24 } ?: "Land status"
    }

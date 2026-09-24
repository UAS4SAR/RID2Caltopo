package org.ncssar.rid2caltopo.landrestrictions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun LandRestrictionPanel(
    state: LandRestrictionUiState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") }
        },
        title = { Text("Land / Agency Rules") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Mapped sources may be incomplete or outdated. No mapped restrictions does not mean permission to launch, land, or fly. Confirm current rules and permissions with the landowner or responsible agency.", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text(state.chipLabel, fontWeight = FontWeight.SemiBold)
                Text(state.statusLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.lastUpdatedEpochMs?.let {
                    Text(
                        "Updated: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(it))}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.stale) {
                    Text("Cached results are more than 24 hours old.", color = MaterialTheme.colorScheme.error)
                }

                LandAgency.entries.forEach { agency ->
                    val agencyAreas = state.areas.filter { it.agency == agency }
                    if (agencyAreas.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(agency.displayName, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { uriHandler.openUri(agency.rulesUrl) }) {
                            Text("Agency UAS rules and contact information")
                        }
                        agencyAreas.forEach { area ->
                            Column(Modifier.padding(bottom = 10.dp)) {
                                Text(area.name, fontWeight = FontWeight.SemiBold)
                                Text(area.rule.label, color = MaterialTheme.colorScheme.tertiary)
                                Text(
                                    if (area.containsOperator) "Operator is inside this mapped boundary."
                                    else "Boundary ${"%.1f".format(area.distanceNm)} NM away.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                area.detailsUrl?.let { detailsUrl ->
                                    TextButton(onClick = { uriHandler.openUri(detailsUrl) }) {
                                        Text("Open property details")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("How to interpret this check", fontWeight = FontWeight.Bold)
                Text(
                    "These agency boundaries describe land-management rules. They do not replace FAA airspace, NOTAM, TFR, LAANC, or agency authorization checks."
                )
                Text(
                    "A boundary warning does not automatically mean overflight is prohibited. Confirm the displayed rule with the responsible agency before operating.",
                    modifier = Modifier.padding(top = 6.dp)
                )

                if (state.sourceErrors.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Source diagnostics", fontWeight = FontWeight.Bold)
                    state.sourceErrors.forEach {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    )
}

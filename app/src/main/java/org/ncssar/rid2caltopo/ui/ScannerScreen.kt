
/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.util.Locale
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.notam.NotamAuthManager
import org.ncssar.rid2caltopo.notam.NotamCenter
import kotlin.math.roundToInt

@Composable
fun ScannerScreen(
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val persistedDroneSpecs = CaltopoClient.GetPersistedDroneSpecs()
    val verticalSliderMax = verticalScrollState.maxValue.toFloat()
    val horizontalSliderMax = horizontalScrollState.maxValue.toFloat()
    val statusText = buildStatusText(persistedDroneSpecs)
    val faaTrackerAccessStatus = currentFaaTrackerAccessStatus()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 960.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Status", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = 560.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(verticalScrollState)
                                .horizontalScroll(horizontalScrollState)
                        ) {
                            Column(
                                modifier = Modifier.width(IntrinsicSize.Max)
                            ) {
                                Text("BUILD_VERSION: ${currentBuildVersion()}")
                                Text("BUILD_TIME: ${currentBuildTime()}")

                                Spacer(modifier = Modifier.height(24.dp))
                                StatusSectionHeader("Scanner Status")
                                Text("Bluetooth 4: ${R2CActivity.legacyBluetoothSupported}")
                                Text(
                                    "Bluetooth 5: ${
                                        R2CActivity.codedPhySupported ||
                                            R2CActivity.extendedAdvertisingSupported
                                    }"
                                )
                                Text("WiFi: ${R2CActivity.wifiSupported}")
                                Text("NaN: ${R2CActivity.nanSupported}")

                                Spacer(modifier = Modifier.height(24.dp))
                                StatusSectionHeader("FAA / Tracker Access")
                                Text(faaTrackerAccessStatus)

                                Spacer(modifier = Modifier.height(24.dp))
                                StatusSectionHeader("Loaded Configuration Components (not access grants)")
                                Text(CaltopoClient.GetConfigFilesLoadedRecord())

                                Spacer(modifier = Modifier.height(24.dp))
                                StatusSectionHeader("Persisted CtDroneSpecs")
                                if (persistedDroneSpecs.isEmpty()) {
                                    Text("No persisted CtDroneSpecs.")
                                } else {
                                    persistedDroneSpecs.sortedBy { it.mappedId.orEmpty().lowercase(Locale.US) }.forEach { spec ->
                                        Text(
                                            "remoteId: ${spec.remoteId}    droneDesig: ${spec.mappedId}    org: ${spec.org.orEmpty()}    owner: ${spec.owner.orEmpty()}    model: ${spec.model.orEmpty()}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }

                    if (verticalSliderMax > 0f) {
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .height(220.dp)
                                .width(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Slider(
                                value = verticalScrollState.value.toFloat(),
                                onValueChange = { newValue ->
                                    coroutineScope.launch {
                                        verticalScrollState.scrollTo(newValue.roundToInt())
                                    }
                                },
                                valueRange = 0f..verticalSliderMax,
                                modifier = Modifier
                                    .width(220.dp)
                                    .rotate(270f)
                            )
                        }
                    }
                }

                if (horizontalSliderMax > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = horizontalScrollState.value.toFloat(),
                        onValueChange = { newValue ->
                            coroutineScope.launch {
                                horizontalScrollState.scrollTo(newValue.roundToInt())
                            }
                        },
                        valueRange = 0f..horizontalSliderMax,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        modifier = Modifier.padding(end = 12.dp),
                        onClick = {
                            coroutineScope.copyPlainTextToClipboard(
                                clipboard,
                                "RID2Caltopo status",
                                statusText
                            )
                            CaltopoClient.ShowToast("Status copied to clipboard.")
                        }
                    ) {
                        Text("Copy")
                    }
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
}

internal fun buildStatusText(persistedDroneSpecs: List<org.ncssar.rid2caltopo.data.CtDroneSpec>): String {
    val bluetooth5 = R2CActivity.codedPhySupported || R2CActivity.extendedAdvertisingSupported
    val builder = StringBuilder()
    builder.appendLine("BUILD_VERSION: ${currentBuildVersion()}")
    builder.appendLine("BUILD_TIME: ${currentBuildTime()}")
    builder.appendLine()
    builder.appendLine("Scanner Status")
    builder.appendLine("Bluetooth 4: ${R2CActivity.legacyBluetoothSupported}")
    builder.appendLine("Bluetooth 5: $bluetooth5")
    builder.appendLine("WiFi: ${R2CActivity.wifiSupported}")
    builder.appendLine("NaN: ${R2CActivity.nanSupported}")
    builder.appendLine()
    builder.appendLine("FAA / Tracker Access")
    builder.appendLine(currentFaaTrackerAccessStatus())
    builder.appendLine()
    builder.appendLine("Loaded Configuration Components (not access grants)")
    builder.appendLine(CaltopoClient.GetConfigFilesLoadedRecord())
    builder.appendLine()
    builder.appendLine("Persisted CtDroneSpecs")
    if (persistedDroneSpecs.isEmpty()) {
        builder.append("No persisted CtDroneSpecs.")
    } else {
        persistedDroneSpecs.sortedBy { it.mappedId.orEmpty().lowercase(Locale.US) }.forEach { spec ->
            builder.appendLine(
                "remoteId: ${spec.remoteId}    droneDesig: ${spec.mappedId}    org: ${spec.org.orEmpty()}    owner: ${spec.owner.orEmpty()}    model: ${spec.model.orEmpty()}"
            )
        }
    }
    return builder.toString().trimEnd()
}

private fun currentFaaTrackerAccessStatus(): String = buildFaaTrackerAccessStatus(
    organization = CaltopoClient.GetHomeOrgName(),
    credentialSource = NotamAuthManager.credentialSource(),
    faaRemoteConfigLoaded = CaltopoClient.GetFaaPayloadEnc().isNotBlank(),
    latestNotamResult = NotamCenter.uiState.value.errorMessage
)

internal fun buildFaaTrackerAccessStatus(
    organization: String,
    credentialSource: NotamAuthManager.CredentialSource,
    faaRemoteConfigLoaded: Boolean,
    latestNotamResult: String?
): String = buildString {
    appendLine("Teams configuration: ${organization.ifBlank { "Not loaded" }} (operational identity only)")
    appendLine(
        "FAA remote config component: " +
            if (faaRemoteConfigLoaded) {
                "Loaded (shared FAA configuration; not a tracker device access grant)"
            } else {
                "Not loaded"
            }
    )
    appendLine(
        "Tracker credential source: " + when (credentialSource) {
            NotamAuthManager.CredentialSource.MANAGED_DEVICE_ENROLLMENT -> "Managed device enrollment"
            NotamAuthManager.CredentialSource.ORGANIZATION_CONFIG_CREDENTIAL ->
                "Organization-config credential (proxy authorization must be verified)"
            NotamAuthManager.CredentialSource.NONE -> "Not configured"
        }
    )
    append(
        "Latest NOTAM proxy result: " +
            latestNotamResult?.takeIf { it.isNotBlank() }.orEmpty().ifBlank { "No authorization failure recorded" }
    )
}

private fun currentBuildVersion(): String = BuildConfig.BUILD_VERSION

private fun currentBuildTime(): String = BuildConfig.BUILD_TIME

internal fun buildConfigString(
    fieldName: String,
    buildConfigClass: Class<*> = BuildConfig::class.java
): String =
    if (buildConfigClass == BuildConfig::class.java) {
        when (fieldName) {
            "BUILD_VERSION" -> BuildConfig.BUILD_VERSION
            "BUILD_TIME" -> BuildConfig.BUILD_TIME
            else -> "unknown"
        }
    } else {
        runCatching { buildConfigClass.getField(fieldName).get(null) as String }
            .getOrDefault("unknown")
    }

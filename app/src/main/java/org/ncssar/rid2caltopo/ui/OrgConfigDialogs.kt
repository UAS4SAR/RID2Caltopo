/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.FaaConfigToken
import org.ncssar.rid2caltopo.data.MutualAidToken
import org.ncssar.rid2caltopo.data.OrgConfigToken
import org.ncssar.rid2caltopo.data.TrackerEnrollmentClient

// ── QR bitmap helper ──────────────────────────────────────────────────────────

/**
 * Generate a square QR-code [Bitmap] from [content].
 * Returns null if encoding fails (e.g. empty string).
 */
private fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
    if (content.isBlank()) return null
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { generateQrBitmap(content) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Join QR code — scan with another device running RID2Caltopo",
            modifier = modifier
        )
    } else {
        Text(
            text = "[QR generation failed]",
            modifier = modifier,
            color = MaterialTheme.colorScheme.error
        )
    }
}

// ── Export dialog (admin) ─────────────────────────────────────────────────────

private sealed class ExportStep {
    object EnterName : ExportStep()
    object Uploading : ExportStep()
    data class ShowQr(val token: String) : ExportStep()
    data class Error(val message: String) : ExportStep()
}

private sealed class MutualAidExportStep {
    object EnterDetails : MutualAidExportStep()
    object Uploading : MutualAidExportStep()
    data class ShowQr(val token: String) : MutualAidExportStep()
    data class Error(val message: String) : MutualAidExportStep()
}

private sealed class FaaExportStep {
    object Confirm : FaaExportStep()
    object Uploading : FaaExportStep()
    data class ShowQr(val token: String) : FaaExportStep()
    data class Error(val message: String) : FaaExportStep()
}

/**
 * Multi-step dialog shown to the admin for generating a join QR code.
 *
 * Step 1 — Enter an org name.
 * Step 2 — Upload in progress (credentials are encrypted before upload).
 * Step 3 — Display QR code + raw token text (both copyable / shareable).
 *
 * [onDismiss] is called when the dialog should be closed.
 * [onUploadRequested] is called with (orgName, resultCallback) when the user
 * confirms; the caller is responsible for obtaining Drive auth and invoking
 * [OrgConfigManager.uploadOrgConfig].  The callback receives
 * (success, message, tokenOrNull) on the main thread.
 */
@Composable
fun OrgConfigExportDialog(
    onDismiss: () -> Unit,
    sourceOrgName: String,
    onUploadRequested: (callback: (Boolean, String, String?) -> Unit) -> Unit
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    var step by remember { mutableStateOf<ExportStep>(ExportStep.EnterName) }

    AlertDialog(
        onDismissRequest = {
            // Don't dismiss while uploading.
            if (step !is ExportStep.Uploading) onDismiss()
        },
        title = {
            Text(
                when (step) {
                    is ExportStep.EnterName -> "Export Org Config"
                    is ExportStep.Uploading -> "Uploading…"
                    is ExportStep.ShowQr   -> "Org Config QR"
                    is ExportStep.Error    -> "Upload Failed"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (val s = step) {

                    is ExportStep.EnterName -> {
                        Text(
                            "The app will upload your current " +
                                "drone mappings and Teams configuration to Google Drive and " +
                                "generate an R2C2 QR. Each receiving device redeems the saved " +
                                "r2c-tracker enrollment locator for its own credential.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Source org: ${sourceOrgName.ifBlank { "Not configured in Settings" }}")
                    }

                    is ExportStep.Uploading -> {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Uploading org config to Google Drive…")
                    }

                    is ExportStep.ShowQr -> {
                        Text(
                            "Share this R2C2 QR with your team. The tracker enrollment campaign " +
                                "must have one remaining redemption for each receiving device.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        QrCodeImage(
                            content = "${OrgConfigToken.QR_SCHEME}://" +
                                s.token.removePrefix(OrgConfigToken.MAGIC_PREFIX),
                            modifier = Modifier.size(240.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Or share as text:",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = s.token,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            coroutineScope.copyPlainTextToClipboard(
                                clipboard,
                                "RID2Caltopo org config token",
                                s.token
                            )
                        }) {
                            Text("Copy Token")
                        }
                    }

                    is ExportStep.Error -> {
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (val s = step) {
                is ExportStep.EnterName -> {
                    TextButton(
                        enabled = sourceOrgName.isNotBlank(),
                        onClick = {
                            step = ExportStep.Uploading
                            onUploadRequested { success, message, token ->
                                step = if (success && token != null) {
                                    ExportStep.ShowQr(token)
                                } else {
                                    ExportStep.Error(message)
                                }
                            }
                        }
                    ) { Text("Export Org Config") }
                }
                is ExportStep.ShowQr, is ExportStep.Error -> {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                else -> { /* no confirm button while uploading */ }
            }
        },
        dismissButton = {
            if (step !is ExportStep.Uploading) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun FaaConfigExportDialog(
    onDismiss: () -> Unit,
    onUploadRequested: (callback: (Boolean, String, String?) -> Unit) -> Unit
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var step by remember { mutableStateOf<FaaExportStep>(FaaExportStep.Confirm) }

    AlertDialog(
        onDismissRequest = {
            if (step !is FaaExportStep.Uploading) onDismiss()
        },
        title = {
            Text(
                when (step) {
                    is FaaExportStep.Confirm -> "Publish FAA Config"
                    is FaaExportStep.Uploading -> "Uploading…"
                    is FaaExportStep.ShowQr -> "FAA Config QR"
                    is FaaExportStep.Error -> "Upload Failed"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (val s = step) {
                    is FaaExportStep.Confirm -> {
                        Text(
                            "The app will upload the loaded FAA NOTAM credentials to Google Drive as an obfuscated shared config and generate a QR token for R2C administrators.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    is FaaExportStep.Uploading -> {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Uploading FAA config to Google Drive…")
                    }
                    is FaaExportStep.ShowQr -> {
                        Text(
                            "Share this QR with R2C administrators. They scan it once to cache FAA NOTAM access.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        QrCodeImage(
                            content = FaaConfigToken.toQrUri(s.token),
                            modifier = Modifier.size(240.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Or share as text:", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = s.token,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            coroutineScope.copyPlainTextToClipboard(
                                clipboard,
                                "RID2Caltopo FAA config token",
                                s.token
                            )
                        }) {
                            Text("Copy Token")
                        }
                    }
                    is FaaExportStep.Error -> {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                is FaaExportStep.Confirm -> {
                    TextButton(
                        onClick = {
                            step = FaaExportStep.Uploading
                            onUploadRequested { success, message, token ->
                                step = if (success && token != null) {
                                    FaaExportStep.ShowQr(token)
                                } else {
                                    FaaExportStep.Error(message)
                                }
                            }
                        }
                    ) { Text("Publish FAA Config") }
                }
                is FaaExportStep.ShowQr, is FaaExportStep.Error -> {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                else -> { }
            }
        },
        dismissButton = {
            if (step !is FaaExportStep.Uploading) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun MutualAidExportDialog(
    defaultIncident: String,
    defaultOpPeriod: String,
    defaultMapId: String,
    defaultMapTitle: String,
    defaultExpiryAtEpochMs: Long,
    sourceOrgName: String,
    onDismiss: () -> Unit,
    onUploadRequested: (
        displayName: String,
        incident: String,
        opPeriod: String,
        mapId: String,
        mapTitle: String,
        expiresAtEpochMs: Long,
        callback: (Boolean, String, String?) -> Unit
    ) -> Unit
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val zoneId = remember { ZoneId.systemDefault() }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var step by remember { mutableStateOf<MutualAidExportStep>(MutualAidExportStep.EnterDetails) }
    var displayName by remember { mutableStateOf("") }
    var incident by remember { mutableStateOf(defaultIncident) }
    var opPeriod by remember { mutableStateOf(defaultOpPeriod) }
    var mapId by remember { mutableStateOf(defaultMapId) }
    var mapTitle by remember { mutableStateOf(defaultMapTitle) }
    val defaultExpiry = remember(defaultExpiryAtEpochMs) {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(defaultExpiryAtEpochMs), zoneId)
    }
    var expiryDateText by remember { mutableStateOf(defaultExpiry.format(dateFormatter)) }
    var expiryTimeText by remember { mutableStateOf(defaultExpiry.format(timeFormatter)) }

    AlertDialog(
        onDismissRequest = {
            if (step !is MutualAidExportStep.Uploading) onDismiss()
        },
        title = {
            Text(
                when (step) {
                    is MutualAidExportStep.EnterDetails -> "Export MA Config"
                    is MutualAidExportStep.Uploading -> "Uploading…"
                    is MutualAidExportStep.ShowQr -> "MA Config QR"
                    is MutualAidExportStep.Error -> "Upload Failed"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (val s = step) {
                    is MutualAidExportStep.EnterDetails -> {
                        Text(
                            "Create a temporary mutual-aid config from the stored MA credentials and the current incident details.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Source org: ${sourceOrgName.ifBlank { "Not configured in Settings" }}")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("Display name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = incident,
                            onValueChange = { incident = it },
                            label = { Text("Incident") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = opPeriod,
                            onValueChange = { opPeriod = it },
                            label = { Text("Op period") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = mapId,
                            onValueChange = { mapId = it },
                            label = { Text("Map ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = mapTitle,
                            onValueChange = { mapTitle = it },
                            label = { Text("Map title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = expiryDateText,
                                onValueChange = { expiryDateText = it },
                                label = { Text("Expiry date") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = expiryTimeText,
                                onValueChange = { expiryTimeText = it },
                                label = { Text("Expiry time") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is MutualAidExportStep.Uploading -> {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Uploading MA config to Google Drive…")
                    }
                    is MutualAidExportStep.ShowQr -> {
                        Text(
                            "Share this QR with the assisting agency. They can import it once using Menu -> Import Config.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        QrCodeImage(
                            content = "r2cma1://" + s.token.removePrefix(MutualAidToken.MAGIC_PREFIX),
                            modifier = Modifier.size(240.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        SelectionContainer {
                            Text(
                                text = s.token,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                coroutineScope.copyPlainTextToClipboard(
                                    clipboard,
                                    "RID2Caltopo mutual aid config token",
                                    s.token
                                )
                            }
                        ) {
                            Text("Copy Token")
                        }
                    }
                    is MutualAidExportStep.Error -> {
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                is MutualAidExportStep.EnterDetails -> {
                    val expiresAt = runCatching {
                        val date = LocalDate.parse(expiryDateText.trim(), dateFormatter)
                        val time = LocalTime.parse(expiryTimeText.trim(), timeFormatter)
                        LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                    TextButton(
                        enabled = sourceOrgName.isNotBlank() &&
                            incident.isNotBlank() &&
                            opPeriod.isNotBlank() &&
                            mapId.isNotBlank() &&
                            expiresAt > System.currentTimeMillis(),
                        onClick = {
                            step = MutualAidExportStep.Uploading
                            onUploadRequested(
                                displayName.trim(),
                                incident.trim(),
                                opPeriod.trim(),
                                mapId.trim(),
                                mapTitle.trim(),
                                expiresAt
                            ) { success, message, token ->
                                step = if (success && token != null) {
                                    MutualAidExportStep.ShowQr(token)
                                } else {
                                    MutualAidExportStep.Error(message)
                                }
                            }
                        }
                    ) { Text("Export MA Config") }
                }
                is MutualAidExportStep.ShowQr, is MutualAidExportStep.Error -> {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (step !is MutualAidExportStep.Uploading) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// ── Import dialog ─────────────────────────────────────────────────────────────

/**
 * Single-step dialog shown for importing shared configuration.
 *
 * The user scans or pastes an org, FAA, or MA token, or chooses a QR image,
 * JSON config, or packaged MA config file. Tokens are validated in real time
 * and routed to the matching config manager when confirmed.
 *
 * [onDismiss] is called when the dialog should close.
 * [onJoin] is called with the normalized org token when confirmed.
 * [onFaaJoin] is called with the normalized FAA token when confirmed.
 * [onMutualAidJoin] is called with the normalized MA token when confirmed.
 * [onScannerStarted] begins the protected external scanner flow.
 * [onScannerFinished] completes that flow when the scanner returns or cannot open.
 * [onPickFile] is called when the user chooses a QR image, JSON config, or MA package file.
 */
@Composable
fun ImportConfigDialog(
    initialToken: String = "",
    onDismiss: () -> Unit,
    onJoin: (token: String) -> Unit,
    onFaaJoin: (token: String) -> Unit,
    onMutualAidJoin: (token: String) -> Unit,
    onPackageJoin: (token: String) -> Unit,
    onTrackerJoin: (url: String) -> Unit,
    onScannerStarted: () -> Boolean,
    onScannerFinished: () -> Unit,
    onPickFile: () -> Unit
) {
    val context = LocalContext.current
    var tokenText by remember(initialToken) { mutableStateOf(initialToken) }
    val normalizedToken = remember(tokenText) { normalizeImportToken(tokenText.trim()) }
    val orgDecoded = remember(normalizedToken) { OrgConfigToken.decode(normalizedToken) }
    val faaDecoded = remember(normalizedToken) { FaaConfigToken.decode(normalizedToken) }
    val mutualAidDecoded = remember(normalizedToken) { MutualAidToken.decode(normalizedToken) }
    val trackerEnrollmentUrl = remember(normalizedToken) {
        normalizedTrackerEnrollmentImport(normalizedToken)
    }
    val trackerEnrollment = trackerEnrollmentUrl != null
    val packageDecoded = remember(normalizedToken) { org.ncssar.rid2caltopo.data.MutualAidPackageTransferToken.decode(normalizedToken) }
    val isValid = packageDecoded != null || orgDecoded != null || faaDecoded != null || mutualAidDecoded != null || trackerEnrollment

    var scannerOpening by remember { mutableStateOf(false) }
    val scannerOptions = remember {
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan an RID2Caltopo configuration QR code")
            .setBeepEnabled(false)
            .setBarcodeImageEnabled(false)
            .setOrientationLocked(false)
    }
    val scannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        onScannerFinished()
        scannerOpening = false
        val raw = result.contents
        if (raw.isNullOrBlank()) {
            CaltopoClient.CTDebug("ImportConfig", "Bundled QR scanner cancelled by operator.")
        } else {
            CaltopoClient.CTDebug("ImportConfig", "Bundled QR scanner returned an import payload.")
            tokenText = normalizeImportToken(raw)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Config") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Scan an MA package, R2C2 organization, or r2c-tracker enrollment QR. R2C1 organization tokens are no longer accepted.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text("Import token") },
                    placeholder = { Text("${OrgConfigToken.MAGIC_PREFIX}…, ${FaaConfigToken.MAGIC_PREFIX}…, or ${MutualAidToken.MAGIC_PREFIX}…") },
                    singleLine = false,
                    isError = tokenText.isNotBlank() && !isValid,
                    trailingIcon = {
                        IconButton(
                            enabled = !scannerOpening,
                            onClick = {
                                if (!onScannerStarted()) {
                                    CaltopoClient.CTWarn(
                                        "ImportConfig",
                                        "Bundled QR scanner could not start because organization access is locked."
                                    )
                                    CaltopoClient.ShowToast(
                                        "Unlock organization access before opening the QR scanner."
                                    )
                                    return@IconButton
                                }
                                CaltopoClient.CTDebug(
                                    "ImportConfig",
                                    "Bundled QR scanner requested from Import Config dialog."
                                )
                                scannerOpening = true
                                try {
                                    scannerLauncher.launch(scannerOptions)
                                } catch (error: Exception) {
                                    onScannerFinished()
                                    scannerOpening = false
                                    CaltopoClient.CTWarn(
                                        "ImportConfig",
                                        "Bundled QR scanner could not open.",
                                        error
                                    )
                                    CaltopoClient.ShowToast(
                                        "QR scanner could not open. You can paste the import token instead."
                                    )
                                }
                            }
                        ) {
                            if (scannerOpening) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.QrCodeScanner,
                                    contentDescription = "Scan QR code"
                                )
                            }
                        }
                    },
                    supportingText = {
                        when {
                            tokenText.isBlank() -> Text("Scan QR, paste token, or choose a QR image/config file")
                            orgDecoded != null -> Text(
                                "Organization Teams config: ${orgDecoded.orgName} (tracker access verified after import)"
                            )
                            faaDecoded != null -> Text(
                                "FAA: ${faaDecoded.label.ifBlank { "Shared NOTAM credentials" }}"
                            )
                            packageDecoded != null -> Text("MA package: ${packageDecoded.packageName}")
                            mutualAidDecoded != null -> Text("MA: ${mutualAidDecoded.sourceOrg}")
                            trackerEnrollment -> Text("Managed r2c-tracker enrollment")
                            else -> Text(
                                "Token not recognised",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    when {
                        packageDecoded != null -> onPackageJoin(normalizedToken)
                        trackerEnrollmentUrl != null -> onTrackerJoin(trackerEnrollmentUrl)
                        faaDecoded != null -> onFaaJoin(normalizedToken)
                        mutualAidDecoded != null -> onMutualAidJoin(normalizedToken)
                        else -> onJoin(normalizedToken)
                    }
                }
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPickFile) { Text("Choose File") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

internal fun normalizedTrackerEnrollmentImport(raw: String): String? =
    TrackerEnrollmentClient.normalizedEnrollmentUrl(normalizeImportToken(raw.trim()))

private fun normalizeImportToken(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("${OrgConfigToken.QR_SCHEME}://") ->
            OrgConfigToken.MAGIC_PREFIX + trimmed.removePrefix("${OrgConfigToken.QR_SCHEME}://")
        trimmed.startsWith("${FaaConfigToken.QR_SCHEME}://") ->
            FaaConfigToken.fromQrUri(trimmed) ?: trimmed
        trimmed.startsWith("r2cma1://") ->
            MutualAidToken.MAGIC_PREFIX + trimmed.removePrefix("r2cma1://")
        else -> trimmed
    }
}

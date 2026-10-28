/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import org.ncssar.rid2caltopo.ui.AlertDialog

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
 * [onPickFile] is called when the user chooses a QR image, JSON config, or map package file.
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
    onCaltopoLink: (url: String) -> Unit,
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
    val caltopoLinkUrl = org.ncssar.rid2caltopo.data.CaltopoPersonalProbe.caltopoLinkURL(normalizedToken)
    val isValid = caltopoLinkUrl != null || packageDecoded != null || trackerEnrollment

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
                    "Scan a map-package, tracker enrollment, or CalTopo link QR. Legacy credential-transfer codes are no longer accepted.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text("Import token") },
                    placeholder = { Text("Tracker enrollment link or map-package QR") },
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
                            packageDecoded != null -> Text("map package: ${packageDecoded.packageName}")
                            mutualAidDecoded != null -> Text("Organization: ${mutualAidDecoded.sourceOrg}")
                            caltopoLinkUrl != null -> Text("CalTopo link — opens in the embedded browser")
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
                        caltopoLinkUrl != null -> onCaltopoLink(caltopoLinkUrl)
                        packageDecoded != null -> onPackageJoin(normalizedToken)
                        trackerEnrollmentUrl != null -> onTrackerJoin(trackerEnrollmentUrl)
                        else -> Unit
                    }
                }
            ) {
                Text(if (caltopoLinkUrl != null) "Open CalTopo" else "Import")
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

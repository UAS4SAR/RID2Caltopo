package org.ncssar.rid2caltopo.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.ncssar.rid2caltopo.data.MutualAidPackageImportState
import org.ncssar.rid2caltopo.data.MutualAidPackageShareSession

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
private fun PackageQrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { generateQrBitmap(content) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "MA package QR code",
            modifier = modifier
        )
    } else {
        Text("[QR generation failed]", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun MutualAidPackageShareDialog(
    session: MutualAidPackageShareSession,
    onDone: () -> Unit
) {
    val expiresText = remember(session.expiresAtEpochMs) {
        DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(session.expiresAtEpochMs))
    }
    val progressFraction = if (session.progress.totalBytes > 0L) {
        session.progress.bytesSent.toFloat() / session.progress.totalBytes.toFloat()
    } else 0f
    AlertDialog(
        onDismissRequest = {},
        title = { Text("MA Package QR") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Connect both devices to the same Wi-Fi or hotspot. Keep this panel open while receivers scan and download. Cellular alone cannot reach this share.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                PackageQrCodeImage(session.qrContent, modifier = Modifier.size(240.dp))
                Spacer(Modifier.height(12.dp))
                Text("Package: ${session.packageName.ifBlank { session.packageFileName }}")
                Text("Size: ${"%.1f".format(session.fileSizeBytes / (1024.0 * 1024.0))} MB")
                Text("Share expires: $expiresText")
                Spacer(Modifier.height(8.dp))
                Text(session.statusMessage, fontSize = 12.sp)
                if (session.progress.phase == "Sending" && session.progress.totalBytes > 0L) {
                    Spacer(Modifier.height(8.dp))
                    Text("Sending to ${session.progress.receiverName}")
                    LinearProgressIndicator(progress = { progressFraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${(progressFraction * 100).toInt()}%  (${formatBytes(session.progress.bytesSent)} / ${formatBytes(session.progress.totalBytes)})",
                        fontSize = 11.sp
                    )
                }
                if (session.completedReceivers.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Completed receivers:", fontSize = 12.sp)
                    session.completedReceivers.takeLast(5).forEach {
                        Text("• ${it.receiverName}", fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        session.token,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone) { Text("Done") }
        }
    )
}

@Composable
fun MutualAidPackageImportDialog(
    state: MutualAidPackageImportState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (state is MutualAidPackageImportState.Success || state is MutualAidPackageImportState.Error) {
                onDismiss()
            }
        },
        title = { Text("Import MA Package") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    is MutualAidPackageImportState.Downloading -> {
                        val fraction = if (state.totalBytes > 0L) {
                            state.bytesRead.toFloat() / state.totalBytes.toFloat()
                        } else 0f
                        Text(state.packageName)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${(fraction * 100).toInt()}%  (${formatBytes(state.bytesRead)} / ${formatBytes(state.totalBytes)})",
                            fontSize = 11.sp
                        )
                        Text(state.phase, fontSize = 11.sp)
                    }
                    is MutualAidPackageImportState.Importing -> {
                        Text(state.packageName)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(state.phase, fontSize = 11.sp)
                    }
                    is MutualAidPackageImportState.Success -> {
                        Text(state.packageName)
                        Spacer(Modifier.height(8.dp))
                        Text(state.message)
                    }
                    is MutualAidPackageImportState.Error -> {
                        Text(state.packageName)
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                    MutualAidPackageImportState.Idle -> {
                        Text("Waiting…")
                    }
                }
            }
        },
        confirmButton = {
            if (state is MutualAidPackageImportState.Success || state is MutualAidPackageImportState.Error) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state is MutualAidPackageImportState.Error) {
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Hide") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    )
}

private fun formatBytes(value: Long): String {
    val mb = value / (1024.0 * 1024.0)
    return if (mb >= 10.0) {
        "${mb.toInt()} MB"
    } else {
        "${"%.1f".format(mb)} MB"
    }
}

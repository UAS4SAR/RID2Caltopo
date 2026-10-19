package org.ncssar.rid2caltopo.mapping

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.ncssar.rid2caltopo.ui.AlertDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun MappingTransferDialog(session: MappingTransferSession, onHide: () -> Unit) {
    val context = LocalContext.current
    var showPilotCheck by remember { mutableStateOf(false) }
    DisposableEffect(session.qrUrl) {
        val window = (context as? Activity)?.window
        val alreadyAwake = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (!alreadyAwake) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val qr = remember(session.qrUrl) {
        val bits = QRCodeWriter().encode(session.qrUrl, BarcodeFormat.QR_CODE, 512, 512)
        Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(512 * 512) { index -> if (bits[index % 512, index / 512]) Color.BLACK else Color.WHITE }
            setPixels(pixels, 0, 512, 0, 0, 512, 512)
        }.asImageBitmap()
    }
    AlertDialog(onDismissRequest = onHide, title = { Text("Controller transfer · ${session.title}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Keep R2C open and connect the controller to the same Wi-Fi. Open this address in the controller browser and enter the code, or scan the QR with a browser-compatible scanner. No controller app is needed.")
            if (session.active) {
                SelectionContainer { Text(session.browserUrl, style = MaterialTheme.typography.titleMedium) }
                SelectionContainer { Text("Access code: ${session.pairingCode}", style = MaterialTheme.typography.headlineSmall) }
                TextButton(onClick = { showPilotCheck = !showPilotCheck }) { Text("Test Pilot 2 Open Platforms connection") }
                if (showPilotCheck) {
                    Text("Enter this URL in Pilot 2 → Cloud Services → Open Platforms:", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text("${session.browserUrl}/pilot2.html", style = MaterialTheme.typography.bodySmall) }
                }
                Image(qr, "Controller browser transfer QR", modifier = Modifier.size(240.dp))
                Text("HTTPS test certificate: the browser may ask you to confirm access to this tablet. Certificate SHA-256:", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(session.certificateFingerprint, style = MaterialTheme.typography.bodySmall) }
                Text("Use a trusted local network. Never enter DJI, FAA or organization credentials on this page.", style = MaterialTheme.typography.bodySmall)
                Text("Expires ${DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(session.expiresAt))}")
            }
            Text(session.status)
            Text("${session.photoCount} originals verified · ${"%.1f".format(session.storedBytes / (1024.0 * 1024.0))} MiB")
            if (session.pendingCount > 0) Text("${session.pendingCount} incomplete transfers. Select the same photos in the browser to resume.")
            Text("The page downloads a boundary KML and receives originals. Review and start the route in Pilot 2. These originals are retained locally; reconstruction is a separate step.")
        }
    }, confirmButton = { TextButton(onClick = { MappingTransferManager.stop(); onHide() }) { Text("Close transfer") } },
        dismissButton = { TextButton(onClick = onHide) { Text("Back to mission") } })
}

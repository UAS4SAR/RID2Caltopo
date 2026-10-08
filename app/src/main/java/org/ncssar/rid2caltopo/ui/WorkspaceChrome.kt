package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry

@Composable
fun WorkspaceAboutHeader(device: String, uptime: String) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val linkColors = ButtonDefaults.textButtonColors(
        containerColor = if (dark) Color(0xFF242424) else Color(0xFFF0F0F0),
        contentColor = if (dark) Color.White else Color.Black,
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("About RID2Caltopo", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            listOf(device, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "Release date: ${BuildConfig.VERSION_RELEASE_DATE}").forEach {
                Text(it, Modifier.padding(vertical = 5.dp), textAlign = TextAlign.Center)
            }
            TextButton(onClick = { uriHandler.openUri("mailto:help@uas4sar.com") }, colors = linkColors) { Text("Support: help@uas4sar.com", style = MaterialTheme.typography.bodyLarge, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) }
            TextButton(onClick = { uriHandler.openUri("https://uas4sar.com") }, colors = linkColors) { Text("Website: uas4sar.com", style = MaterialTheme.typography.bodyLarge, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) }
            Text("Up Time: $uptime", textAlign = TextAlign.Center)
            Text("Tracker: ${coordinatorStatusDisplayText(R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.coordinationStatusText)}", textAlign = TextAlign.Center)
            Text("TeamDrones: ${CaltopoClient.GetRidmapCount()}", textAlign = TextAlign.Center)
        }
}

@Composable
fun WorkspaceBluetoothStats(drones: List<CtDroneSpec>, onDismiss: () -> Unit, onDrone: (CtDroneSpec) -> Unit) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val maxWidth = (config.screenWidthDp - 24).coerceAtLeast(240).toFloat()
    val maxHeight = (config.screenHeightDp - 48).coerceAtLeast(240).toFloat()
    var width by remember { mutableFloatStateOf(minOf(900f, maxWidth)) }
    var height by remember { mutableFloatStateOf(minOf(460f, maxHeight)) }
    var invalid by remember { mutableLongStateOf(CtDroneSpec.GetInvalidWaypointCount().toLong()) }
    LaunchedEffect(Unit) { while (true) { invalid = CtDroneSpec.GetInvalidWaypointCount().toLong(); delay(1000) } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(minOf(width, maxWidth).dp).height(minOf(height, maxHeight).dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Aircraft and Reception", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text("InvalidRID Msgs: $invalid")
                Box(Modifier.weight(1f).horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                    Column { RidmapHeader(); drones.forEach { drone -> key(drone.remoteId) { DroneItem(drone = drone, totalCount = drone.totalCount) { onDrone(drone) } } } }
                }
                Text("Resize ↘", Modifier.align(Alignment.End).padding(10.dp).pointerInput(maxWidth, maxHeight) {
                    detectDragGestures { change, drag -> change.consume(); width = (width + drag.x / density.density).coerceIn(240f, maxWidth); height = (height + drag.y / density.density).coerceIn(240f, maxHeight) }
                })
            }
        }
    }
}

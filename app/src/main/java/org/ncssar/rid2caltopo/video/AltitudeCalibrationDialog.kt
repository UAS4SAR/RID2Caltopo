package org.ncssar.rid2caltopo.video

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

internal const val TAKEOFF_CALIBRATION_MESSAGE = "Take-off coordinates not established at launch. Please hover 50' above take-off and press:"

@Composable
internal fun AltitudeCalibrationDialog(enabled: Boolean, onCalibrate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Altitude calibration") },
        text = { Text(TAKEOFF_CALIBRATION_MESSAGE) },
        confirmButton = { TextButton(enabled = enabled, onClick = onCalibrate) { Text("Calibrate 50' ATO") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Disregard") } }
    )
}

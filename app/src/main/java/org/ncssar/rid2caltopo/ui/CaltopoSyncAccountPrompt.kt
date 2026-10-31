/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.ncssar.rid2caltopo.data.CaltopoPersonalAccountConnection

/** Shown and acknowledged before the CalTopo auth surface opens. */
@Composable
fun CaltopoSyncAccountPrompt(
    visible: Boolean,
    onContinue: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CalTopo sign-in") },
        text = { Text(CaltopoPersonalAccountConnection.ACTIVATION_PROMPT) },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AppInformationDocument(val title: String, val asset: String) {
    PrivacyPolicy("Privacy policy", "information/PrivacyPolicyAndroid.txt"),
    ServiceAcknowledgments("Service & data acknowledgments", "information/ServiceAcknowledgmentsAndroid.txt")
}

@Composable
internal fun BundledInformationDialog(document: AppInformationDocument, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val text by produceState<String?>(null, document) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(document.asset).bufferedReader().use { it.readText() } }
                .getOrDefault("This document could not be loaded. Please contact help@uas4sar.com.")
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(document.title) },
        text = {
            if (text == null) CircularProgressIndicator()
            else SelectionContainer {
                Text(text.orEmpty(), modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

// Keep identical to docs/legal/IndividualUserTerms.txt and the Apple copy.
internal const val LAUNCH_DISCLAIMER_TEXT =
    "RID2Caltopo provides supplemental situational awareness. Do not use it as your sole source for navigation, flight safety, communications, or incident-command decisions. It does not provide flight authorization.\n\nObservation only — do not pilot using this video.\n\nOperational risks\n\nThe app may contain defects, produce incorrect or misleading results, fail to detect relevant conditions, or fail without warning. Information and automated analysis may be incomplete, inaccurate, delayed, incorrectly associated, or unavailable. The absence of a warning, detection, or displayed hazard does not establish that conditions are safe or that a search or other operation is complete.\n\nUse of or reliance on the app may contribute to operational errors, delayed or misdirected response, disclosure or loss of sensitive information, property damage, personal injury, or death. Exercise independent judgment, verify information appropriate to the consequences of error, and maintain suitable alternative procedures. The app does not replace qualified personnel, required observation, or applicable operating procedures.\n\nSoftware license\n\nRID2Caltopo’s original software is licensed under the Apache License, Version 2.0. Its warranty disclaimer and limitation of liability are in Sections 7 and 8 of that license. Third-party components retain their own licenses. This acknowledgement does not modify those licenses or restrict the rights they grant.\n\nConnected services\n\nCalTopo and other integrations depend on their providers’ availability, permissions, and terms. RID2Caltopo is not affiliated with or endorsed by CalTopo. Access to hosted r2c-tracker services is governed separately by the applicable service agreement. This acknowledgement does not accept service terms or bind your organization."

@Composable
fun LaunchDisclaimerScreen(
    onAgree: () -> Unit,
    onDisagree: () -> Unit,
    readOnly: Boolean = false,
    saving: Boolean = false,
    error: String? = null,
) {
    var checked by rememberSaveable(ApplicationTerms.fingerprint) { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    BackHandler(onBack = onDisagree)
    if (showLicense) {
        SoftwareLicenseDialog(onClose = { showLicense = false })
    }
    if (showPrivacy) {
        AboutPrivacyDialog(onClose = { showPrivacy = false })
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "RID2Caltopo — Safety & License",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Text("Acknowledgement version ${ApplicationTerms.version}", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = buildAnnotatedString {
                        LAUNCH_DISCLAIMER_TEXT.split("\n\n").forEachIndexed { index, paragraph ->
                            if (index > 0) append("\n\n")
                            if (index == 0 || paragraph in listOf("Operational risks", "Software license", "Connected services") || paragraph.matches(Regex("[1-9]\\. .*")) || paragraph.startsWith("Observation only")) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(paragraph) }
                            } else append(paragraph)
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = { showLicense = true }) { Text("Apache 2.0 & third-party notices") }
                TextButton(onClick = { showPrivacy = true }) { Text("Privacy & data use") }
                Spacer(modifier = Modifier.height(32.dp))
                if (readOnly) {
                    Button(onClick = onDisagree, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { checked = it }, enabled = !saving)
                        Text("I have read and understand this operational acknowledgement. I acknowledge it for myself, not on behalf of my organization.")
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = onAgree,
                        enabled = checked && !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (saving) "Saving…" else "Acknowledge and Continue") }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onDisagree,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Decline and Exit") }
                }
            }
        }
    }
}

@Composable
private fun SoftwareLicenseDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val content = remember {
        listOf("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md").joinToString("\n\n") { name ->
            context.assets.open("legal/$name").bufferedReader().use { it.readText() }
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp)) {
                TextButton(onClick = onClose) { Text("Close license & notices") }
                Text(content, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
            }
        }
    }
}

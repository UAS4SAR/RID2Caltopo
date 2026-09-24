/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoNode
import org.ncssar.rid2caltopo.data.TabletPilotCallsignPrefs

@Composable
fun OpPeriodField(modifier: Modifier = Modifier) {
    var opPeriodState by remember { mutableStateOf(CaltopoClient.GetOpPeriod()) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    DisposableEffect(Unit) {
        val listener = object : CaltopoMap.MapStatusListener {
            override fun mapStatusUpdate(
                status: CaltopoMap.MapStatusListener.mapStatus,
                mapNode: CaltopoNode.MapNode?,
                optErrmsg: String?,
            ) {
                opPeriodState = CaltopoClient.GetOpPeriod()
            }
        }
        CaltopoMap.AddMapStatusListener(listener)
        onDispose { CaltopoMap.RemoveMapStatusListener(listener) }
    }

    OutlinedTextField(
        value = opPeriodState,
        onValueChange = { opPeriodState = it },
        modifier = modifier.onFocusChanged { focusState ->
            if (!focusState.isFocused) {
                CaltopoClient.SetOpPeriod(opPeriodState)
            }
        },
        label = { Text("Op Period") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                CaltopoClient.SetOpPeriod(opPeriodState)
                focusManager.clearFocus()
                keyboardController?.hide()
            },
        ),
    )
}

@Composable
fun PilotCallsignField(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var pilotCallsign by remember { mutableStateOf(TabletPilotCallsignPrefs.load(context)) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun persist() {
        TabletPilotCallsignPrefs.save(context, pilotCallsign)
        pilotCallsign = TabletPilotCallsignPrefs.load(context)
    }

    OutlinedTextField(
        value = pilotCallsign,
        onValueChange = {
            pilotCallsign = it
            TabletPilotCallsignPrefs.save(context, it)
        },
        modifier = modifier.onFocusChanged { focusState ->
            if (!focusState.isFocused) persist()
        },
        label = { Text("Pilot Callsign/Name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                persist()
                focusManager.clearFocus()
                keyboardController?.hide()
            },
        ),
    )
}

/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */


package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.data.CtDroneSpec


/**
 * Draws a row of 4 signal-strength bars (classic staircase style) based on an RSSI value in dBm.
 *
 * Bar thresholds (matches Android's WifiManager.calculateSignalLevel convention):
 *   4 bars : rssi ≥ -60 dBm  (excellent)
 *   3 bars : rssi ≥ -70 dBm  (good)
 *   2 bars : rssi ≥ -80 dBm  (fair)
 *   1 bar  : rssi ≥ -90 dBm  (poor)
 *   0 bars : rssi == 0        (no RF measurement)
 *
 * @param rssi Signal strength in dBm; 0 means "not available".
 */
@Composable
fun SignalStrengthBars(
    rssi: Int,
    modifier: Modifier = Modifier,
    colorByStrength: Boolean = false,
) {
    val filledBars = when {
        rssi == 0       -> 0
        rssi >= -60     -> 4
        rssi >= -70     -> 3
        rssi >= -80     -> 2
        rssi >= -90     -> 1
        else            -> 0
    }
    val filledColor = if (colorByStrength) {
        when (filledBars) {
            1 -> Color(0xFFF44336) // red
            2 -> Color(0xFFFFC107) // amber/yellow
            else -> Color(0xFF4CAF50) // green
        }
    } else {
        Color(0xFF4CAF50)
    }
    val emptyColor = if (colorByStrength) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    } else {
        Color(0x554CAF50)
    }

    // 4 bars, each slightly taller than the previous (staircase).
    // Heights: 25%, 45%, 65%, 85% of the available vertical space.
    val fractions = listOf(0.25f, 0.45f, 0.65f, 0.85f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        fractions.forEachIndexed { index, fraction ->
            val filled = index < filledBars
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight(fraction)
                    .background(
                        color = if (filled) filledColor else emptyColor,
                        shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                    )
            )
        }
    }
}

@Composable
fun DroneItem(drone: CtDroneSpec,
              totalCount: Int, // just used to force compose to redraw the DroneItem on chg.
              onConfirmDrone: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.secondary)
                .padding(1.dp)
                .defaultMinSize(minHeight = 32.dp)
                .height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.publishStatusColumnWidthDp.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (drone.publishingLocally()) {
                    Image(
                        painter = painterResource(id = R.drawable.earth),
                        contentDescription = "earth",
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                }
            }
            Column(
                modifier = Modifier.width(R2CViewColumnLayout.trackLabelColumnWidthDp.dp).background(MaterialTheme.colorScheme.surface).padding(1.dp)
                    .fillMaxWidth().fillMaxHeight()
            ) {
                OutlinedButton(
                    onClick = onConfirmDrone,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(2.dp)
                        .height(30.dp),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = drone.owner.trim().ifBlank { drone.mappedId.ifBlank { "Confirm Drone" } },
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(
                modifier = Modifier.width(R2CViewColumnLayout.remoteIdColumnWidthDp.dp).background(MaterialTheme.colorScheme.surface).padding(1.dp)
                    .fillMaxWidth().fillMaxHeight()
            ) {
                Text(
                    text = drone.remoteId,
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                droneToBridgeRssiText(drone.lastDroneToBridgeRssi)?.let { bridgeRssiText ->
                    Text(
                        text = bridgeRssiText,
                        textAlign = TextAlign.Center,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // BT4 count
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.transportCountColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier.align(Alignment.End),
                    text = "${drone.getTransportCount(CtDroneSpec.TransportTypeEnum.BT4)}",
                    textAlign = TextAlign.Right)
            }
            // BT4 signal bars
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.transportSignalColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom
            ) {
                SignalStrengthBars(
                    rssi = drone.getLastRssi(CtDroneSpec.TransportTypeEnum.BT4),
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
            }
            // BT5 count
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.transportCountColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier.align(Alignment.End),
                    text = "${drone.getTransportCount(CtDroneSpec.TransportTypeEnum.BT5)}",
                    textAlign = TextAlign.Right)
            }
            // BT5 signal bars
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.transportSignalColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom
            ) {
                SignalStrengthBars(
                    rssi = drone.getLastRssi(CtDroneSpec.TransportTypeEnum.BT5),
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
            }
            // WiFi count
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.transportCountColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier.align(Alignment.End),
                    text = "${drone.getTransportCount(CtDroneSpec.TransportTypeEnum.WIFI)}",
                    textAlign = TextAlign.Right)
            }
            // WiFi signal bars
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.transportSignalColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom
            ) {
                SignalStrengthBars(
                    rssi = drone.getLastRssi(CtDroneSpec.TransportTypeEnum.WIFI),
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
            }
            // NaN (WNAN) count
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.transportCountColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier.align(Alignment.End),
                    text = "${drone.getTransportCount(CtDroneSpec.TransportTypeEnum.WNAN)}",
                    textAlign = TextAlign.Right)
            }
            // NaN signal bars
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.transportSignalColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom
            ) {
                SignalStrengthBars(
                    rssi = drone.getLastRssi(CtDroneSpec.TransportTypeEnum.WNAN),
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
            }
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.r2cWaypointColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight()
            ) {}
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.totalColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier.align(Alignment.End),
                    text = "${totalCount}",
                    textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.flightDurationColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier.align(Alignment.End),
                    text = drone.getDurationInSecAsString(),
                    fontSize=14.sp,
                    textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width(R2CViewColumnLayout.r2cRttColumnWidthDp.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight()
            ) {}
        }
    }
}

internal fun droneToBridgeRssiText(droneToBridgeRssiDbm: Int): String? =
    droneToBridgeRssiDbm.takeIf { it in -127..-1 }?.let { "D→Bridge $it dBm" }

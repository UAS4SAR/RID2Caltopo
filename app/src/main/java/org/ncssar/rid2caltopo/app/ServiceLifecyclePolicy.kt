/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.app

import android.content.pm.ServiceInfo

/**
 * Lifecycle rules shared by the app's foreground services (Nov 18, 2026).
 *
 * Ken: "When the user terminates the app, I don't want it continuing to use resources."
 * A session starts when the operator opens the app and ends on Quit, Recents swipe, back-out
 * or idle timeout. Nothing restarts it in the background: if Android kills the process mid
 * session, monitoring stays off until the operator reopens the app (matches iOS, which has no
 * Bluetooth or location relaunch). Locked-screen monitoring during an open session is unchanged.
 */
object ServiceLifecyclePolicy {
    /**
     * A start with no intent is a system restart, not an operator request. Only continue when
     * the app UI is present (it would have asked for the start itself).
     */
    @JvmStatic
    fun shouldStopUnrequestedStart(intentPresent: Boolean, uiPresent: Boolean): Boolean =
        !intentPresent && !uiPresent

    /** The idle alarm only matters while a session runs; otherwise it cancels itself. */
    @JvmStatic
    fun idleAlarmShouldCheck(scanningServiceRunning: Boolean, uiPresent: Boolean): Boolean =
        scanningServiceRunning || uiPresent

    /**
     * True while the manifest declares BLUETOOTH_SCAN without `neverForLocation`. Android then
     * delivers BLE scan results only to an app with location access, and with while-in-use
     * permission a service in the background has that access only with the `location` type.
     * Dropping the type would stop Bluetooth Remote ID with the display off, which breaks
     * locked-screen monitoring during a session. ServiceLifecyclePolicyTest ties this to the
     * manifest: adding `neverForLocation` (a separate decision; Android then filters some
     * beacons) is what lets Bluetooth-only scanning run as `connectedDevice` alone.
     */
    const val BLUETOOTH_SCAN_NEEDS_LOCATION = true

    /**
     * Wi-Fi Remote ID (beacon/NAN) and, while [BLUETOOTH_SCAN_NEEDS_LOCATION], Bluetooth Remote
     * ID need location access with the display off. The location type is held only while a
     * scan that needs it is on; otherwise the scanner runs as `connectedDevice` only.
     */
    @JvmStatic
    @JvmOverloads
    fun scanningForegroundTypes(
        wifiRidScanningEnabled: Boolean,
        fineLocationGranted: Boolean,
        bluetoothScanNeedsLocation: Boolean = BLUETOOTH_SCAN_NEEDS_LOCATION,
    ): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (fineLocationGranted && (wifiRidScanningEnabled || bluetoothScanNeedsLocation)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    @JvmStatic
    fun describeForegroundTypes(types: Int): String = buildList {
        if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0) add("connectedDevice")
        if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) add("location")
    }.joinToString("|").ifEmpty { "none" }

    /** Reason recorded when Android's background time limit for dataSync ends the video relay. */
    const val MEDIA_TIME_LIMIT_REASON = "Android background time limit reached; recording stopped"
}

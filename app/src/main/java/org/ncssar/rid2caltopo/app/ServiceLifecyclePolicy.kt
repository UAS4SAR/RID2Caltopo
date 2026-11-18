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
     * Wi-Fi Remote ID (beacon/NAN) results need location while the display is off; Bluetooth
     * Remote ID does not. Ask for the location service type only while Wi-Fi scanning is on.
     */
    @JvmStatic
    fun scanningForegroundTypes(wifiRidScanningEnabled: Boolean, fineLocationGranted: Boolean): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (wifiRidScanningEnabled && fineLocationGranted) {
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

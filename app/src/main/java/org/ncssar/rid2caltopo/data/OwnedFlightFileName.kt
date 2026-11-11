/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import java.io.File
import java.time.ZoneId

/**
 * The archive file is named from the lease grant, the same instant as the track title.
 * A file already written under the shadow start is moved onto that name. The destination
 * is never overwritten, and a failed move leaves the source in place.
 */
object OwnedFlightFileName {
    @JvmStatic
    fun stampMs(grantMs: Long, firstStoredPointMs: Long): Long =
        if (grantMs > 0L) grantMs else firstStoredPointMs.coerceAtLeast(0L)

    @JvmStatic
    fun adoptGrantName(
        directory: File,
        aircraftId: String,
        shadowStartMs: Long,
        grantMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val grantName = OperatorArchiveFilename.track(aircraftId, grantMs, zone)
        if (shadowStartMs <= 0L || shadowStartMs == grantMs) return grantName
        val shadowName = OperatorArchiveFilename.track(aircraftId, shadowStartMs, zone)
        movePair(directory, shadowName, grantName)
        return grantName
    }

    private fun movePair(directory: File, fromJson: String, toJson: String) {
        moveOne(File(directory, fromJson), File(directory, toJson))
        moveOne(
            File(directory, FlightArchiveRebuild.kmzName(fromJson)),
            File(directory, FlightArchiveRebuild.kmzName(toJson)),
        )
    }

    private fun moveOne(from: File, to: File) {
        if (!from.isFile) return
        if (to.exists()) return
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        runCatching { from.copyTo(to, overwrite = false) }
        if (to.isFile && to.length() == from.length()) from.delete()
    }
}

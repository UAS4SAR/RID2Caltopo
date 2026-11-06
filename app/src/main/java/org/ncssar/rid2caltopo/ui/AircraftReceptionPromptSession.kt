/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

/**
 * Session-only memory of aircraft already presented in Aircraft and Reception.
 * The set lives in the process and is cleared by [reset] (a new process starts empty).
 * Nothing here is written to disk.
 *
 * Mirrors apple/Sources/R2CCore/AircraftReceptionPromptSession.swift.
 */
internal enum class AircraftReceptionPromptDecision {
    /** Panel is closed. Caller should show Aircraft and Reception. */
    Open,
    /** Panel is already showing. Do not stack another presentation. */
    AlreadyVisible,
    /** Known team aircraft, already dispositioned, or already presented. */
    Skip,
}

internal class AircraftReceptionPromptSession {
    private val presented = HashSet<String>()

    fun reset() {
        presented.clear()
    }

    /**
     * @param unidentified not on the team list (no distinct mapped id and no organization)
     * @param dispositionedThisSession Confirm, Don't Publish, or an automatic confirmation
     * dialog already owns this aircraft. Remembered so a later packet cannot open over that flow.
     */
    fun observe(
        aircraftId: String,
        unidentified: Boolean,
        dispositionedThisSession: Boolean,
        panelOpen: Boolean,
    ): AircraftReceptionPromptDecision {
        val id = aircraftId.trim()
        if (id.isEmpty()) return AircraftReceptionPromptDecision.Skip
        if (id in presented) return AircraftReceptionPromptDecision.Skip
        if (dispositionedThisSession || !unidentified) {
            if (dispositionedThisSession) presented.add(id)
            return AircraftReceptionPromptDecision.Skip
        }
        presented.add(id)
        return if (panelOpen) {
            AircraftReceptionPromptDecision.AlreadyVisible
        } else {
            AircraftReceptionPromptDecision.Open
        }
    }

    companion object {
        /** Team-list aircraft have a mapped id other than the remote id, or an organization. */
        fun isUnidentifiedAircraft(remoteId: String, mappedId: String, organization: String): Boolean {
            val id = remoteId.trim()
            if (id.isEmpty()) return false
            val mapped = mappedId.trim()
            val knownMapped = mapped.isNotEmpty() && !mapped.equals(id, ignoreCase = true)
            return !knownMapped && organization.trim().isEmpty()
        }

        /** Stable short token for logs. Not the aircraft id and not a location. */
        fun logToken(aircraftId: String): String {
            var hash = 2166136261u
            for (byte in aircraftId.trim().encodeToByteArray()) {
                hash = hash xor (byte.toInt() and 0xFF).toUInt()
                hash *= 16777619u
            }
            return hash.toString(16).padStart(8, '0')
        }
    }
}

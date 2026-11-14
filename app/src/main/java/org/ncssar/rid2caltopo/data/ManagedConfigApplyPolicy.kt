/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

/** Result of one organization-configuration request to r2c-tracker. */
sealed class ManagedConfigFetchOutcome {
    /** The Tracker answered: configuration applied, already current, or none published. */
    object CheckedIn : ManagedConfigFetchOutcome()
    /** The Tracker refused this device's token (HTTP 401/403). Retrying will not help. */
    object Rejected : ManagedConfigFetchOutcome()
    /** No usable answer: offline, timeout, server error, or a configuration that would not apply. */
    data class Unreachable(val reason: String) : ManagedConfigFetchOutcome()
}

object ManagedConfigApplyPolicy {
    /**
     * Apply when the Tracker's version is new, when older readiness data or missing team
     * credentials need it, or when a different app version applied the stored version.
     * The last case lets an upgraded app read fields its predecessor ignored, such as
     * Drone Ownership, without the organization publishing again.
     */
    @JvmStatic
    fun needsApply(
        remoteVersionMs: Long,
        localVersionMs: Long,
        readinessSchemaVersion: Int,
        hasTeamCredentials: Boolean,
        appliedByAppVersion: String?,
        currentAppVersion: String
    ): Boolean = remoteVersionMs != localVersionMs ||
        readinessSchemaVersion < 1 ||
        !hasTeamCredentials ||
        appliedByAppVersion != currentAppVersion
}

/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import android.content.Context
import org.ncssar.rid2caltopo.app.R2CApplication

/**
 * Remembers the Map Browser credential profile across process restarts.
 *
 * Team/org profiles are also stored in [ClientClassState.activeCaltopoProfileId], but that
 * field cannot hold the signed personal account. This preference stores the last browser
 * selection, including [PERSONAL_PROFILE_ID].
 */
object MapBrowserProfilePreference {
    const val PERSONAL_PROFILE_ID = "personal"
    private const val PREFS = "map_browser_profile"
    private const val KEY_LAST_PROFILE_ID = "last_selected_profile_id"

    @Volatile
    private var memoryOverride: String? = null

    /** Unit tests inject an in-memory value without needing an Android Context. */
    fun installForTests(lastSelectedProfileId: String?) {
        memoryOverride = lastSelectedProfileId
    }

    fun clearTestInstall() {
        memoryOverride = null
    }

    fun lastSelectedProfileId(): String {
        memoryOverride?.let { return it }
        val prefs = preferences() ?: return ""
        return prefs.getString(KEY_LAST_PROFILE_ID, "").orEmpty()
    }

    fun remember(profileId: String) {
        val trimmed = profileId.trim()
        if (trimmed.isEmpty()) return
        memoryOverride = trimmed
        preferences()?.edit()?.putString(KEY_LAST_PROFILE_ID, trimmed)?.apply()
    }

    fun clear() {
        memoryOverride = ""
        preferences()?.edit()?.remove(KEY_LAST_PROFILE_ID)?.apply()
    }

    /**
     * Chooses which profile the Map Browser should open with after a restart.
     *
     * Personal is restored only when a stored personal credential is available; otherwise
     * the active team/org profile (or empty) is used.
     */
    fun resolveRestoredProfileId(
        lastSelectedProfileId: String,
        personalCredentialAvailable: Boolean,
        defaultTeamProfileId: String,
        knownTeamProfileIds: Collection<String> = emptyList()
    ): String {
        val last = lastSelectedProfileId.trim()
        if (last == PERSONAL_PROFILE_ID) {
            return if (personalCredentialAvailable) PERSONAL_PROFILE_ID else defaultTeamProfileId
        }
        if (last.isNotEmpty() && (knownTeamProfileIds.isEmpty() || knownTeamProfileIds.contains(last))) {
            return last
        }
        return defaultTeamProfileId
    }

    /**
     * Team credentials can open maps only when they are configured and the Tracker has not
     * refused team access for this organization device. Same rule as iOS.
     */
    fun teamUsable(teamCredentialsPresent: Boolean, teamAccessRefused: Boolean): Boolean =
        teamCredentialsPresent && !teamAccessRefused

    /** Credential selection at launch: personal whenever no Teams credentials are configured. Same as iOS. */
    fun initialUsesPersonal(stored: Boolean, teamCredentialsPresent: Boolean): Boolean =
        stored || !teamCredentialsPresent

    /**
     * Profile the Map Browser opens with. Personal whenever team credentials cannot work, even
     * without a stored personal credential: the caller then offers personal sign-in instead of
     * selecting "Team: No Teams credentials". Otherwise [resolveRestoredProfileId], with an
     * empty result also becoming personal. Same as iOS `resolveOpenProfileID`.
     */
    fun resolveOpenProfileId(
        lastSelectedProfileId: String,
        personalCredentialAvailable: Boolean,
        defaultTeamProfileId: String,
        knownTeamProfileIds: Collection<String> = emptyList(),
        teamUsable: Boolean
    ): String {
        if (!teamUsable) return PERSONAL_PROFILE_ID
        return resolveRestoredProfileId(lastSelectedProfileId, personalCredentialAvailable, defaultTeamProfileId, knownTeamProfileIds)
            .ifEmpty { PERSONAL_PROFILE_ID }
    }

    private fun preferences() =
        R2CApplication.getAppCtxt()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

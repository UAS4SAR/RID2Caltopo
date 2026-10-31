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

    private fun preferences() =
        R2CApplication.getAppCtxt()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

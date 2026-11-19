package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ken, 2026-11-19: App Store 2.5.7 on the Mac mini selected "Team: No Teams credentials" with no
 * Teams credentials configured. Personal is the selection whenever team credentials cannot work.
 * Same fixture as the iPad's CaltopoCredentialSelectionTests.
 */
class CaltopoCredentialSelectionTest {
    private fun opt(v: String) = if (v == "-") "" else v

    @Test
    fun sharedFixtureMatchesIos() {
        val fixture = sequenceOf(File("test-fixtures/caltopo/credential-selection.tsv"), File("../test-fixtures/caltopo/credential-selection.tsv")).first { it.isFile }
        val rows = fixture.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(10, rows.size)
        for (r in rows) {
            val teamUsable = MapBrowserProfilePreference.teamUsable(r[4].toBoolean(), r[5].toBoolean())
            val got = MapBrowserProfilePreference.resolveOpenProfileId(
                lastSelectedProfileId = opt(r[0]),
                personalCredentialAvailable = r[1].toBoolean(),
                defaultTeamProfileId = opt(r[2]),
                knownTeamProfileIds = opt(r[3]).split(',').filter { it.isNotEmpty() },
                teamUsable = teamUsable)
            assertEquals(r[7], r[6], got)
        }
    }

    @Test
    fun teamUsableNeedsCredentialsAndTrackerConsent() {
        assertFalse(MapBrowserProfilePreference.teamUsable(teamCredentialsPresent = false, teamAccessRefused = false))
        assertFalse(MapBrowserProfilePreference.teamUsable(teamCredentialsPresent = true, teamAccessRefused = true))
        assertTrue(MapBrowserProfilePreference.teamUsable(teamCredentialsPresent = true, teamAccessRefused = false))
    }

    @Test
    fun personalIsTheInitialSelectionWithoutTeamsCredentials() {
        assertTrue(MapBrowserProfilePreference.initialUsesPersonal(stored = false, teamCredentialsPresent = false))
        assertTrue(MapBrowserProfilePreference.initialUsesPersonal(stored = true, teamCredentialsPresent = true))
        assertFalse(MapBrowserProfilePreference.initialUsesPersonal(stored = false, teamCredentialsPresent = true))
    }

    @Test
    fun openProfileIsNeverEmpty() {
        assertEquals(MapBrowserProfilePreference.PERSONAL_PROFILE_ID,
            MapBrowserProfilePreference.resolveOpenProfileId("", false, "", emptyList(), teamUsable = true))
    }
}

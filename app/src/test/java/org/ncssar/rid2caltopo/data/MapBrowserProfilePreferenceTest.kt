package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MapBrowserProfilePreferenceTest {
    @After
    fun tearDown() {
        MapBrowserProfilePreference.clearTestInstall()
    }

    @Test
    fun rememberedPersonalSurvivesRestartWhenCredentialIsAvailable() {
        MapBrowserProfilePreference.installForTests(MapBrowserProfilePreference.PERSONAL_PROFILE_ID)
        assertEquals(
            MapBrowserProfilePreference.PERSONAL_PROFILE_ID,
            MapBrowserProfilePreference.resolveRestoredProfileId(
                lastSelectedProfileId = MapBrowserProfilePreference.lastSelectedProfileId(),
                personalCredentialAvailable = true,
                defaultTeamProfileId = "home-default",
                knownTeamProfileIds = listOf("home-default", "ma")
            )
        )
    }

    @Test
    fun missingPersonalCredentialFallsBackToActiveTeamProfile() {
        MapBrowserProfilePreference.installForTests(MapBrowserProfilePreference.PERSONAL_PROFILE_ID)
        assertEquals(
            "home-default",
            MapBrowserProfilePreference.resolveRestoredProfileId(
                lastSelectedProfileId = MapBrowserProfilePreference.lastSelectedProfileId(),
                personalCredentialAvailable = false,
                defaultTeamProfileId = "home-default",
                knownTeamProfileIds = listOf("home-default")
            )
        )
    }

    @Test
    fun rememberedTeamProfileIsRestoredAndUnknownIdsFallBack() {
        MapBrowserProfilePreference.installForTests("ma")
        assertEquals(
            "ma",
            MapBrowserProfilePreference.resolveRestoredProfileId(
                lastSelectedProfileId = MapBrowserProfilePreference.lastSelectedProfileId(),
                personalCredentialAvailable = false,
                defaultTeamProfileId = "home-default",
                knownTeamProfileIds = listOf("home-default", "ma")
            )
        )
        MapBrowserProfilePreference.installForTests("retired")
        assertEquals(
            "home-default",
            MapBrowserProfilePreference.resolveRestoredProfileId(
                lastSelectedProfileId = MapBrowserProfilePreference.lastSelectedProfileId(),
                personalCredentialAvailable = true,
                defaultTeamProfileId = "home-default",
                knownTeamProfileIds = listOf("home-default", "ma")
            )
        )
    }

    @Test
    fun rememberUpdatesTheInMemorySelectionUsedAcrossRestarts() {
        MapBrowserProfilePreference.installForTests(null)
        MapBrowserProfilePreference.remember(MapBrowserProfilePreference.PERSONAL_PROFILE_ID)
        assertEquals(
            MapBrowserProfilePreference.PERSONAL_PROFILE_ID,
            MapBrowserProfilePreference.lastSelectedProfileId()
        )
        MapBrowserProfilePreference.remember("home-default")
        assertEquals("home-default", MapBrowserProfilePreference.lastSelectedProfileId())
    }
}

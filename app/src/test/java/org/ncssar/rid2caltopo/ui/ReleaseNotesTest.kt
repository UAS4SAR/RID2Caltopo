package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun parseReleaseNotes_formatsPublicLatestChanges() {
        val entries = parseReleaseNotes(
            "Latest changes:\n" +
                "- Improved live video viewing.\n" +
                "- Clarified map status messages.\n"
        )

        assertEquals(listOf("Latest changes"), entries.map { it.title })
        assertEquals(
            listOf("Improved live video viewing.", "Clarified map status messages."),
            entries.single().changeLines
        )
    }

    @Test
    fun parseReleaseNotes_formatsGitLogRecordWithTimestampForDisplay() {
        val entries = parseReleaseNotes(
            "\nabc1234\t2026-06-04 09:47:11 -0700\tv1.5.9(83)rc2:\n" +
                "- Added pinch/spread zoom for video playback.\n" +
                "- Added zoom-aware clue snapshots.\u001E\n"
        )

        assertEquals(
            listOf(
                ReleaseNoteEntry(
                    hash = "abc1234",
                    date = "2026-06-04 09:47:11 -0700",
                    title = "v1.5.9(83)rc2",
                    detail = "Added pinch/spread zoom for video playback.\nAdded zoom-aware clue snapshots."
                )
            ),
            entries
        )
        assertEquals(
            listOf(
                "Added pinch/spread zoom for video playback.",
                "Added zoom-aware clue snapshots."
            ),
            entries.single().changeLines
        )
    }

    @Test
    fun parseReleaseNotes_acceptsLegacySpaceSeparatedGitLog() {
        val entries = parseReleaseNotes(
            "fedcba9 2026-06-03 v1.5.8(82)rc3: - Non-team Remote IDs stay visible."
        )

        assertEquals(
            listOf(
                ReleaseNoteEntry(
                    hash = "fedcba9",
                    date = "2026-06-03",
                    title = "v1.5.8(82)rc3",
                    detail = "Non-team Remote IDs stay visible."
                )
            ),
            entries
        )
    }

    @Test
    fun parseReleaseNotes_usesFallbackForBlankContent() {
        assertEquals(listOf(RELEASE_NOTES_FALLBACK_ENTRY), parseReleaseNotes(" \n\t "))
    }
}

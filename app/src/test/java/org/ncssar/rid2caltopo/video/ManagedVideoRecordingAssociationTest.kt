package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.Instant

class ManagedVideoRecordingAssociationTest {
    @Test fun recordingAdvertisementIncludesActualSizeAndMetadata() {
        val file = File.createTempFile("recording-source", ".mp4")
        try {
            file.writeBytes(ByteArray(12345))
            val recording = ManagedVideoSessionRecording("id", "Matrice", file, Instant.EPOCH,
                6100, 1920, 1080, 29.97, "h264", 4_000_000)
            val advertised = ManagedVideoSessionRecordingCatalog.advertisement(recording)
            assertEquals(12345L, advertised.sourceSizeBytes)
            assertEquals(1920, advertised.sourceWidth)
            assertEquals(1080, advertised.sourceHeight)
            assertEquals(29.97, advertised.sourceFps, 0.001)
            assertEquals(4_000_000L, advertised.sourceBitrateBps)
            assertEquals("h264", advertised.sourceCodec)
            assertEquals(6100L, advertised.durationMs)
        } finally { file.delete() }
    }

    @Test
    fun recordingFingerprintChangesOnlyWhenMetadataChanges() {
        val original = ManagedVideoSessionRecordingCatalog.RecordingFingerprint(
            length = 123L,
            lastModified = 456L,
        )

        assertEquals(original, original.copy())
        assertNotEquals(original, original.copy(length = 124L))
        assertNotEquals(original, original.copy(lastModified = 457L))
    }

    @Test
    fun archiveRecoverySelectsOnlyDesignatorsForCurrentMap() {
        val matching = ManagedVideoSessionRecordingCatalog.matchingArchiveDesignators(
            metadataDocuments = listOf(
                """{"features":[{"properties":{"r2c_prop":{"map_id":"MAP1","mid":"1sar7DjMtrc4td"}}}]}""",
                """{"features":[{"properties":{"r2c_prop":{"map_id":"OTHER","mid":"OtherDrone"}}}]}""",
                "not-json",
            ),
            mapId = "MAP1",
        )

        assertEquals(setOf("1sar7djmtrc4td"), matching)
    }

    @Test
    fun downloadNameOmitsPrivateCatalogIdentityAndRemuxMarker() {
        assertEquals(
            "1sar7djmtrc4td_05Sep2026_155607_PDT.mp4",
            ManagedVideoSessionRecordingCatalog.downloadFileName(
                "1sar7djmtrc4td__0681f35f-2258-4bdb-a2e2-76dd54b0a8a2__" +
                    "1sar7djmtrc4td_05Sep2026_155607_PDT.tmp.mp4",
            ),
        )
        assertEquals(
            "1sar7djmtrc4td_04Sep2026_180249_PDT.mp4",
            ManagedVideoSessionRecordingCatalog.downloadFileName(
                "1sar7djmtrc4td_04Sep2026_180249_PDT.mp4",
            ),
        )
    }

    @Test
    fun selectsRecordingThatOverlapsExactTrackInsteadOfLatestForDesignator() {
        val first = recording("first", startMs = 1_000L, endMs = 11_000L)
        val second = recording("second", startMs = 101_000L, endMs = 111_000L)

        assertEquals(
            first,
            ManagedVideoSessionRecordingCatalog.selectForTrack(
                recordings = listOf(second, first),
                trackStartedAtMs = 2_000L,
                trackEndedAtMs = 10_000L,
                candidates = listOf("1SAR7"),
            ),
        )
        assertEquals(
            second,
            ManagedVideoSessionRecordingCatalog.selectForTrack(
                recordings = listOf(second, first),
                trackStartedAtMs = 102_000L,
                trackEndedAtMs = 110_000L,
                candidates = listOf("1sar7"),
            ),
        )
    }

    @Test
    fun doesNotReuseStaleRecordingForLaterTrack() {
        val stale = recording("stale", startMs = 1_000L, endMs = 11_000L)

        assertNull(
            ManagedVideoSessionRecordingCatalog.selectForTrack(
                recordings = listOf(stale),
                trackStartedAtMs = 101_000L,
                trackEndedAtMs = 111_000L,
                candidates = listOf("1SAR7"),
            ),
        )
    }

    private fun recording(
        sessionID: String,
        startMs: Long,
        endMs: Long,
    ) = ManagedVideoSessionRecording(
        sessionId = sessionID,
        droneDesignator = "1SAR7",
        file = File("/$sessionID.mp4"),
        recordedAt = Instant.ofEpochMilli(endMs),
        durationMs = endMs - startMs,
        width = 1920,
        height = 1080,
        fps = 30.0,
        codec = "h264",
    )
}

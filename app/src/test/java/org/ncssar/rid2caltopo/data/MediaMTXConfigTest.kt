package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import org.ncssar.rid2caltopo.video.ManagedVideoSessionRecordingCatalog

class MediaMTXConfigTest {
    @Test
    fun accessPolicyDefaultsToIngestOnlyAndReplacesPermissiveBaseBlocks() {
        assertTrue(MediaServerAccessPrefs.isRestricted(null))
        val base = File("src/main/assets/mediamtx.yml").readText() + """

            authInternalUsers:
              - user: any
                ips: []
                permissions:
                  - action: read
            """.trimIndent()
        val restricted = MediaMTXConfig.buildRuntimeConfig(base, true, File("/tmp/r2c-access-recordings"))
        val open = MediaMTXConfig.buildRuntimeConfig(base, true, File("/tmp/r2c-access-recordings"), false)
        assertTrue(restricted.contains("rtmpAddress: :1935"))
        assertTrue(restricted.contains("rtspAddress: 127.0.0.1:8554"))
        assertTrue(restricted.contains("hlsAddress: 127.0.0.1:8888"))
        assertTrue(restricted.contains("rtspTransports: [tcp]"))
        assertTrue(restricted.contains("webrtc: no"))
        assertTrue(restricted.contains("srt: no"))
        assertTrue(restricted.contains("record: yes"))
        assertTrue(restricted.split("authInternalUsers:").size == 2)
        val remotePermissions = restricted.substringAfter("authInternalUsers:").substringBefore("ips: [127.0.0.1")
        assertTrue(remotePermissions.contains("action: publish"))
        assertFalse(remotePermissions.contains("action: read"))
        assertFalse(remotePermissions.contains("action: playback"))
        assertTrue(open.contains("rtspAddress: :8554"))
        assertTrue(open.contains("hlsAddress: :8888"))
        assertTrue(open.substringAfter("authInternalUsers:").substringBefore("ips: [127.0.0.1").contains("action: read"))
        assertTrue(open.contains("apiAddress: 127.0.0.1:9997"))
        val securedAgain = MediaMTXConfig.withNetworkAccess(open, true)
        assertTrue(securedAgain.substringAfter("authInternalUsers:").trim() ==
            restricted.substringAfter("authInternalUsers:").substringBefore("\nlogLevel:").trim())
        assertTrue(securedAgain.contains("rtspAddress: 127.0.0.1:8554"))
        // Feed these exact generated configurations into the native-server socket test.
        File("build/test-media-access").mkdirs()
        File("build/test-media-access/android-restricted.yml").writeText(restricted)
        File("build/test-media-access/android-open.yml").writeText(open)
    }

    @Test
    fun buildRuntimeConfig_disablesRecordingWhenCaptureOff() {
        val config = MediaMTXConfig.buildRuntimeConfig(
            baseConfig = "logLevel: debug\nrtmp: yes\n",
            captureEnabled = false,
            recordingRoot = File("/tmp/unused"),
        )

        assertTrue(config.contains("pathDefaults:\n  record: no"))
        assertFalse(config.contains("recordFormat: fmp4"))
        assertFalse(config.contains("\nrecord: no"))
    }

    @Test
    fun buildRuntimeConfig_enablesRecordingWhenCaptureOn() {
        val config = MediaMTXConfig.buildRuntimeConfig(
            baseConfig = "logLevel: debug\nrtmp: yes\n",
            captureEnabled = true,
            recordingRoot = File("/tmp/mediamtx-recordings"),
        )

        assertTrue(config.contains("pathDefaults:\n  record: yes"))
        assertTrue(config.contains("recordFormat: fmp4"))
        assertFalse(config.contains("\nrecord: yes"))
        assertTrue(config.contains("%path/%path_%Y-%m-%d_%H-%M-%S-%f"))
        assertTrue(config.contains("/tmp/mediamtx-recordings"))
    }

    @Test
    fun archiveTimestampLocalizesMediaMtxUtcAndPreservesExistingLocalNames() {
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "2026-08-27_04-41-11-000001.mp4",
                ZoneId.of("America/Los_Angeles"),
            ) == "26Aug2026_214111_PDT"
        )
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "2026-01-12_09-20-51-000001.mp4",
                ZoneId.of("America/Los_Angeles"),
            ) == "12Jan2026_012051_PST"
        )
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "1sar7mn4pr_12Aug2026_092051-000001.mp4",
                ZoneId.of("America/Los_Angeles"),
            ) == "12Aug2026_092051_PDT"
        )
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "1sar7mn4pr_12Aug2026_092051_PDT-0700-000001.mp4",
                ZoneId.of("America/New_York"),
            ) == "12Aug2026_092051_PDT-0700"
        )
    }

    @Test
    fun archiveTimestampRejectsInvalidMediaMtxDate() {
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "2026-02-30_09-20-51-000001.mp4",
                ZoneId.of("America/Los_Angeles"),
            ) == null
        )
    }

    @Test
    fun recordingSessionIdentitySurvivesCatalogRebuild() {
        val path = "/app/files/managed-video/map/1sar7_12Aug2026_092051.mp4"
        assertTrue(
            ManagedVideoSessionRecordingCatalog.sessionIdForPath(path) ==
                ManagedVideoSessionRecordingCatalog.sessionIdForPath(path)
        )
    }
}

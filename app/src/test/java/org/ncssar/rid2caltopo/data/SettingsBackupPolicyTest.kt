package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class SettingsBackupPolicyTest {
    private val secret = "SYNTHETIC_CREDENTIAL_MUST_NOT_LEAVE_DEVICE"
    private fun withSecrets() = AppConfig.newBuilder()
        .setTrackerApiKey(secret).setTrackerEnrollmentUrl(secret).setTrackerUrlPrefix(secret)
        .setTrackerFaaProxyUrl(secret).setCaltopoConnectKey(secret).setIncident(secret)
        .setCaltopoCredentials(AppConfig.CaltopoCredentialsConfig.newBuilder().setCredentialSecret(secret).setCredentialId(secret))
        .addCaltopoProfiles(AppConfig.CaltopoProfile.newBuilder().setCredentialSecret(secret).setTrackerApiKey(secret))
        .setMutualAidTemplate(AppConfig.MutualAidTemplate.newBuilder().setCredentialSecret(secret))
        .setNotam(AppConfig.NotamConfig.newBuilder().setClientId(secret).setClientSecret(secret).setTokenUrl(secret).setRadiusNm(7))
        .setFaaRemoteConfig(AppConfig.FaaRemoteConfig.newBuilder().setToken(secret).setPayloadEnc(secret))
        .setArchiveLocation(AppConfig.ArchiveLocation.newBuilder().setTreeUri(secret))
        .setMinDistanceFeet(12).setCaptureVideoStreams(false)
        .addRidMappings(AppConfig.RidMapping.newBuilder().setRemoteId("test-aircraft").setMappedId("Test pilot").setReadinessJson("{\"credential_secret\":\"$secret\"}"))
        .build()

    @Test fun backupOmitsAllAccessAndUnknownFieldsButKeepsPreferences() {
        val extra = java.io.ByteArrayOutputStream()
        val coded = com.google.protobuf.CodedOutputStream.newInstance(extra)
        coded.writeString(999, secret); coded.flush()
        val input = AppConfig.parseFrom(withSecrets().toByteArray() + extra.toByteArray())
        val safe = SettingsBackupPolicy.settingsOnly(input)
        assertFalse(String(safe.toByteArray(), Charsets.ISO_8859_1).contains(secret))
        assertEquals(12, safe.minDistanceFeet)
        assertEquals(7, safe.notam.radiusNm)
        assertEquals("test-aircraft", safe.ridMappingsList.single().remoteId)
        assertEquals(0, safe.caltopoProfilesCount)
        assertEquals("", safe.trackerApiKey)
    }

    @Test fun oldBackupCannotReplaceExistingCredentialsOrEnrollment() {
        val current = withSecrets().toBuilder().setTrackerApiKey("current-device-key")
            .setTrackerEnrollmentUrl("current-enrollment").setMinDistanceFeet(99).build()
        val restored = SettingsBackupPolicy.restore(current, withSecrets())
        assertEquals("current-device-key", restored.trackerApiKey)
        assertEquals("current-enrollment", restored.trackerEnrollmentUrl)
        assertEquals(current.caltopoCredentials, restored.caltopoCredentials)
        assertEquals(12, restored.minDistanceFeet)
        assertFalse(restored.captureVideoStreams)
    }

    @Test fun oldBackupCannotEnrollFreshInstallation() {
        val restored = SettingsBackupPolicy.restore(AppConfig.getDefaultInstance(), withSecrets())
        assertEquals("", restored.trackerApiKey)
        assertEquals("", restored.trackerEnrollmentUrl)
        assertEquals("", restored.caltopoCredentials.credentialSecret)
        assertEquals("", restored.notam.clientSecret)
    }
}

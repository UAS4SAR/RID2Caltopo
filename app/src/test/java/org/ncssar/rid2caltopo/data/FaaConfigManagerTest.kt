package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FaaConfigManagerTest {
    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun plaintextImportIsRejectedWithoutChangingDeviceAccess() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetCaltopoCredentials(CaltopoCredentials("team", "cred", "current-secret"))
        CaltopoClient.SetTrackerApiKey("current-device-key")
        assertThrows(IllegalArgumentException::class.java) { FaaConfigManager.importPlaintextConfig(samplePlaintext()) }
        assertEquals("current-secret", CaltopoClient.GetCaltopoCredentials().credentialSecret)
        assertEquals("current-device-key", CaltopoClient.GetTrackerApiKey())
        assertEquals("", CaltopoClient.GetNotamClientSecret())
        assertFalse(FaaConfigManager.readConfigObject(samplePlaintext()))
    }

    @Test
    fun r2C2OrgBundleExcludesLegacyFaaCredentialsAndBootstrap() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetNotamClientId("client-id")
        CaltopoClient.SetNotamClientSecret("client-secret")
        CaltopoClient.StoreFaaRemoteConfig(
            FaaConfigToken.encode(FaaConfigToken.FaaConfig("drive-id", "FAA label")),
            "FAA label",
            CaltopoClient.GetFaaPayloadEnc(),
            1234L,
            false,
            ""
        )

        val bundle = CaltopoClient.BuildOrgConfigBundle("Test Org")
        val json = JSONObject(bundle)
        val configs = json.getJSONArray("configs")
        val serialized = configs.toString()

        assertFalse(serialized.contains("client-secret"))
        assertFalse(serialized.contains("notam_client_secret"))
        assertFalse(serialized.contains(FaaConfigManager.TYPE_REMOTE))
        assertFalse(serialized.contains("R2CFAA1:"))
    }

    @Test
    fun authorizationFailure_marksCachedFaaConfigStale() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetNotamClientId("client-id")
        CaltopoClient.SetNotamClientSecret("client-secret")

        CaltopoClient.StoreFaaRemoteConfig("synthetic-old-locator", "legacy", "", 1L, false, "")

        FaaConfigManager.markAuthorizationFailure("NOTAM authentication failed (HTTP 401).")

        assertTrue(CaltopoClient.GetFaaConfigStale())
        assertEquals("NOTAM authentication failed (HTTP 401).", CaltopoClient.GetFaaLastFailureReason())
    }

    private fun samplePlaintext(): JSONObject =
        JSONObject()
            .put("type", FaaConfigManager.TYPE_PLAINTEXT)
            .put("file_version", "1.0")
            .put("source_label", "FAA label")
            .put("notam_api_base_url", "https://example.test/nmsapi")
            .put("notam_token_url", "https://example.test/token")
            .put("notam_client_id", "client-id")
            .put("notam_client_secret", "client-secret")
            .put("notam_scope", "")
}

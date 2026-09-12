package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrgConfigBundleTest {
    @Test
    fun aircraftReadinessSurvivesSharedAndManagedConfigurationRoundTrips() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetCaltopoCredentials(CaltopoCredentials("team", "cred", "secret"))
        val readiness = AircraftReadiness(serialNumber = "SERIAL", registrationNumber = "FA123",
            baseWeightGrams = 1200.0, monitoringEquipment = "Controller ADS-B",
            accessories = listOf(AircraftAccessory("battery", "Battery", 400.0, group = "battery")))
        CaltopoClient.ReplacePersistedDroneSpecs("NCSSAR", listOf(
            EditableRidMapping("1581F8HGX1234567890", "Pilot Example", "1SAR7", "DJI Test", readiness)))
        val shared = findConfig(JSONObject(CaltopoClient.BuildOrgConfigBundle("NCSSAR")), "ct_ridmap")
        val managed = OrgConfigManager.buildManagedSnapshot()
        assertEquals(readiness, AircraftReadiness.fromJSON(managed.getJSONArray("droneSpecs").getJSONObject(0).getJSONObject("readiness")))
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.readRidmapFileContent(shared)
        assertEquals(readiness, CaltopoClient.GetPersistedDroneSpecs().single().readiness)
    }

    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun buildR2C2BundleIncludesEnrollmentLocatorButNeverIssuedDeviceCredential() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetHomeOrgName("NCSSAR")
        CaltopoClient.SetCaltopoCredentials(CaltopoCredentials("team", "cred", "secret"))
        CaltopoClient.SetConnectKey("NCSSAR-UAS")
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetTrackerFaaProxyUrl("https://tracker.example.org/faa/notams")
        CaltopoClient.SetTrackerEnrollmentUrl(
            "https://r2c-tracker.com/ncssar/enroll?token=campaign-token"
        )
        CaltopoClient.SetMutualAidTemplate(
            MutualAidTemplateRecord(
                "ma-team",
                "ma-cred",
                "ma-secret",
                "caltopo.com",
                "Neighbor SAR",
                "MAI"
            ).also { it.connectKey = "SHARED-UAS" }
        )

        val bundle = JSONObject(CaltopoClient.BuildOrgConfigBundle("NCSSAR"))
        val credentials = findConfig(bundle, "ct_credentials")
        val mutualAidCredentials = findConfig(bundle, "ct_mutual_aid_credentials")

        assertEquals("NCSSAR", bundle.getString("org_name"))
        assertEquals("NCSSAR", credentials.getString("org_name"))
        assertEquals("NCSSAR-UAS", credentials.getString("connect_key"))
        assertEquals("SHARED-UAS", mutualAidCredentials.getString("connect_key"))
        assertEquals(2, bundle.getInt("version"))
        assertEquals(
            "https://r2c-tracker.com/ncssar/enroll?token=campaign-token",
            credentials.getString("tracker_enrollment_url")
        )
        assertTrue(!credentials.has("tracker_api_key"))
        assertTrue(!credentials.has("tracker_faa_proxy_url"))
        assertTrue(!credentials.has("notam_client_secret"))
    }

    @Test
    fun managedTrackerUploadIncludesPrimaryAndMutualAidConnectKeys() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetHomeOrgName("NCSSAR")
        CaltopoClient.SetCaltopoCredentials(CaltopoCredentials("team", "cred", "secret"))
        CaltopoClient.SetConnectKey("NCSSAR-UAS")
        CaltopoClient.SetMutualAidTemplate(
            MutualAidTemplateRecord(
                "ma-team",
                "ma-cred",
                "ma-secret",
                "caltopo.com",
                "Neighbor SAR",
                "MAI"
            ).also { it.connectKey = "SHARED-UAS" }
        )

        val snapshot = OrgConfigManager.buildManagedSnapshot()
        val credentials = JSONObject(
            OrgConfigToken.decryptPayload(snapshot.getString("organizationCaltopoEnc"))
        )
        val mutualAidCredentials = JSONObject(
            OrgConfigToken.decryptPayload(snapshot.getString("mutualAidCaltopoEnc"))
        )

        assertEquals("NCSSAR-UAS", credentials.getString("connect_key"))
        assertEquals("SHARED-UAS", mutualAidCredentials.getString("connect_key"))
    }

    @Test
    fun applyOrgConfigBundle_restoresHomeOrgFromQrCredentialsChild() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetCaltopoCredentials(CaltopoCredentials("team", "cred", "secret"))
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetMutualAidTemplate(
            MutualAidTemplateRecord(
                "ma-team",
                "ma-cred",
                "ma-secret",
                "caltopo.com",
                "NCSSAR",
                "MAI"
            ).also { it.connectKey = "SHARED-UAS" }
        )
        val bundle = CaltopoClient.BuildOrgConfigBundle("NCSSAR")
        checkNotNull(bundle)
        val json = JSONObject(bundle)
        findConfig(json, "ct_credentials").put("org_name", "NCSSAR")

        CaltopoClient.ResetPersistedClientState()

        assertTrue(CaltopoClient.ApplyOrgConfigBundle(json.toString()))
        assertEquals("NCSSAR", CaltopoClient.GetHomeOrgName())
        assertEquals("SHARED-UAS", CaltopoClient.GetMutualAidTemplateConnectKey())
    }

    @Test
    fun applyR2C2BundleDoesNotCloneSourceDeviceCredential() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://r2c-tracker.com/ncssar")
        CaltopoClient.SetTrackerFaaProxyUrl("https://r2c-tracker.com/faa/notams")
        CaltopoClient.SetTrackerEnrollmentUrl(
            "https://r2c-tracker.com/ncssar/enroll?token=campaign-token"
        )
        val bundle = CaltopoClient.BuildOrgConfigBundle("NCSSAR")
        checkNotNull(bundle)
        val bundleJson = JSONObject(bundle)
        val configs = bundleJson.getJSONArray("configs")
        for (index in configs.length() - 1 downTo 0) {
            if (configs.getJSONObject(index).optString("type") == "ct_mutual_aid_credentials") {
                configs.remove(index)
            }
        }

        CaltopoClient.ResetPersistedClientState()

        assertTrue(CaltopoClient.ApplyOrgConfigBundle(bundleJson.toString()))
        assertEquals("", CaltopoClient.GetHomeTrackerApiKey())
        assertEquals("", CaltopoClient.GetTrackerFaaProxyUrl())
    }

    private fun findConfig(bundle: JSONObject, type: String): JSONObject {
        val configs = bundle.getJSONArray("configs")
        for (i in 0 until configs.length()) {
            val config = configs.getJSONObject(i)
            if (config.optString("type") == type) return config
        }
        throw AssertionError("Missing config type $type")
    }
}

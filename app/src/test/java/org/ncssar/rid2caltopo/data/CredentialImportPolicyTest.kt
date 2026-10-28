package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CredentialImportPolicyTest {
    @Test fun credentialFileTypesAreRejectedButAircraftMappingsRemainSupported() {
        for (type in listOf("ct_credentials", "ct_credentials_enc", "ct_mutual_aid_credentials", "ct_faa_credentials", "ct_faa_remote_config", "rid2caltopo_org_config")) {
            assertFalse(type, CredentialImportPolicy.allowsLocalConfig(type))
        }
        assertTrue(CredentialImportPolicy.allowsLocalConfig("ct_ridmap"))
    }
    @Test fun mapOnlyPackagesWorkAndLegacyProfilesFailClosed() {
        CredentialImportPolicy.requireMapOnlyPackage(JSONObject().put("profile_enc", ""))
        CredentialImportPolicy.requireMapOnlyPackage(JSONObject())
        for (value in listOf("synthetic-credential", JSONObject().put("credential_secret", "synthetic"), false)) {
            assertThrows(IllegalArgumentException::class.java) {
                CredentialImportPolicy.requireMapOnlyPackage(JSONObject().put("profile_enc", value))
            }
        }
    }
}

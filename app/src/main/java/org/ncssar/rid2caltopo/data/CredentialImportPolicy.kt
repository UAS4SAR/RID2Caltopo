package org.ncssar.rid2caltopo.data

import org.json.JSONObject

object CredentialImportPolicy {
    const val RETIRED_MESSAGE = "Legacy credential transfers are no longer supported. Scan a tracker enrollment QR."
    @JvmStatic fun allowsLocalConfig(type: String): Boolean = type == "ct_ridmap"
    @JvmStatic fun requireMapOnlyPackage(manifest: JSONObject) {
        require(!manifest.has("profile_enc") || manifest.opt("profile_enc") == "") {
            "This legacy package contains credentials. Ask for a map-only package and enroll through the tracker."
        }
    }
}

/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** System-browser enrollment and catalog load for Personal Login Support: Credentials. */
object CaltopoPersonalCredentialSignIn {
    fun connect(context: Context, deviceName: String = CaltopoPersonalAccountConnection.DEFAULT_DEVICE_NAME): CredentialCatalog {
        val existing = CaltopoPersonalAccountConnection.reconnect("caltopo.com")
        val status = existing ?: CaltopoPersonalAccountConnection.enroll(
            deviceName = deviceName,
            browser = CaltopoSystemBrowser { url ->
                context.startActivity(CaltopoPersonalAccountBrowser.systemBrowserIntent(url))
            },
            http = CaltopoPersonalAccountHttps
        )
        val body = CaltopoPersonalAccountConnection.fetchAccountCatalog(CaltopoPersonalAccountHttps)
            ?: throw CaltopoPersonalAccountException("CalTopo account catalog could not be loaded.")
        val normalized = CaltopoPersonalCatalog.normalize(body, status.accountId)
        return CredentialCatalog(status, signedGrants(normalized))
    }

    fun publishCatalog(catalog: JSONObject) {
        CaltopoPersonalSession.acceptCatalog(CaltopoPersonalSession.prepareCatalog(catalog))
    }

    /** Keystore delete. Call from a worker, then [CaltopoPersonalSession.suspendPersonalSelection] on the UI thread. */
    fun logOut() {
        CaltopoPersonalAccountConnection.disconnect()
    }

    fun switchMode(context: Context, mode: CaltopoPersonalLoginMode) {
        context.getSharedPreferences(CaltopoPersonalAccountConnection.FEATURE_PREFERENCE, Context.MODE_PRIVATE)
            .edit()
            .putString(CaltopoPersonalAccountConnection.LOGIN_MODE_KEY, mode.wire())
            .apply()
        CaltopoPersonalAccountConnection.applyLoginMode(mode)
        // Keep the unused login's stored secret. Drop the active personal map so the two are not mixed.
        CaltopoPersonalSession.suspendPersonalSelection()
    }

    private fun signedGrants(normalized: JSONObject): JSONObject {
        val grants = JSONObject()
        val features = normalized.optJSONArray("features") ?: JSONArray()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val id = feature.optString("id")
            if (feature.optJSONObject("properties")?.optString("class") == "CollaborativeMap" && id.isNotEmpty()) {
                grants.put(id, "signed-account")
            }
        }
        return JSONObject(normalized.toString()).put("grants", grants)
    }
}

data class CredentialCatalog(val status: CaltopoPersonalAccountStatus, val catalog: JSONObject)

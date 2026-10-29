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
        CaltopoPersonalAccountConnection.logAuth("connect process=${android.app.Application.getProcessName()}")
        try {
            return connectStoredOrEnroll(context, deviceName)
        } catch (error: Exception) {
            CaltopoPersonalAccountConnection.logAuth("connect failed")
            throw error
        }
    }

    private fun connectStoredOrEnroll(context: Context, deviceName: String): CredentialCatalog {
        val existing = CaltopoPersonalAccountConnection.reconnect("caltopo.com")
        if (existing != null) CaltopoPersonalAccountConnection.logAuth("reconnect account=${existing.accountId}")
        val status = existing ?: CaltopoPersonalAccountConnection.enroll(
            deviceName = deviceName,
            browser = CaltopoSystemBrowser { url ->
                CaltopoPersonalAccountBrowser.open(context, url)
            },
            http = CaltopoPersonalAccountHttps
        )
        val body = try {
            CaltopoPersonalAccountConnection.fetchAccountCatalog(CaltopoPersonalAccountHttps)
                ?: throw CaltopoPersonalAccountException("CalTopo account catalog could not be loaded.")
        } catch (error: CaltopoPersonalAccountException) {
            if (error.rejectsStoredCredential()) {
                CaltopoPersonalAccountConnection.logAuth("stored credential rejected HTTP ${error.httpStatus}; cleared")
                CaltopoPersonalAccountConnection.disconnect()
            }
            throw error
        }
        CaltopoPersonalAccountConnection.deleteSpareActivationCredential(body)
        val normalized = CaltopoPersonalCatalog.normalize(body, status.accountId)
        CaltopoPersonalAccountConnection.logAuth(
            "catalog normalized accounts=${normalized.optJSONArray("accounts")?.length() ?: 0} features=${normalized.optJSONArray("features")?.length() ?: 0}"
        )
        return CredentialCatalog(status, CaltopoPersonalSession.prepareCatalog(signedGrants(normalized)))
    }

    /** Loads maps for a stored credential. Does not open CalTopo activation. */
    fun openStored(): StoredPersonalLoad {
        val existing = CaltopoPersonalAccountConnection.reconnect("caltopo.com")
            ?: return StoredPersonalLoad.Missing
        return try {
            val body = CaltopoPersonalAccountConnection.fetchAccountCatalog(CaltopoPersonalAccountHttps)
                ?: return StoredPersonalLoad.Failed("CalTopo account catalog could not be loaded.")
            CaltopoPersonalAccountConnection.deleteSpareActivationCredential(body)
            val normalized = CaltopoPersonalCatalog.normalize(body, existing.accountId)
            StoredPersonalLoad.Ready(CredentialCatalog(existing, CaltopoPersonalSession.prepareCatalog(signedGrants(normalized))))
        } catch (error: CaltopoPersonalAccountException) {
            if (error.rejectsStoredCredential()) {
                CaltopoPersonalAccountConnection.logAuth("stored credential rejected HTTP ${error.httpStatus}; cleared")
                CaltopoPersonalAccountConnection.disconnect()
                StoredPersonalLoad.Rejected
            } else {
                StoredPersonalLoad.Failed(error.message ?: "CalTopo account catalog could not be loaded.")
            }
        } catch (error: Exception) {
            StoredPersonalLoad.Failed(error.message ?: "CalTopo account catalog could not be loaded.")
        }
    }

    fun publishCatalog(catalog: CaltopoPersonalSession.PreparedCatalog) {
        CaltopoPersonalSession.acceptCatalog(catalog)
    }

    /** Keystore delete. Call from a worker, then clear the personal selection on the UI thread. */
    fun logOut() {
        CaltopoPersonalAccountConnection.logOut()
    }

    fun switchMode(context: Context, mode: CaltopoPersonalLoginMode) {
        context.getSharedPreferences(CaltopoPersonalAccountConnection.FEATURE_PREFERENCE, Context.MODE_PRIVATE)
            .edit()
            .putString(CaltopoPersonalAccountConnection.LOGIN_MODE_KEY, mode.wire())
            .apply()
        CaltopoPersonalAccountConnection.applyLoginMode(mode)
        // Keep the unused login's stored secret. Drop the active personal map so the two are not mixed.
    }

    private fun signedGrants(normalized: JSONObject): JSONObject {
        val grants = JSONObject()
        val features = normalized.optJSONArray("features") ?: JSONArray()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val id = feature.optString("id")
            if (feature.optJSONObject("properties")?.optString("class") == "CollaborativeMap" && id.isNotEmpty()) {
                grants.put(id, CaltopoPersonalAccountConnection.SIGNED_ACCOUNT_GRANT)
            }
        }
        return JSONObject(normalized.toString()).put("grants", grants)
    }
}

data class CredentialCatalog(val status: CaltopoPersonalAccountStatus, val catalog: CaltopoPersonalSession.PreparedCatalog)

sealed class StoredPersonalLoad {
    data class Ready(val catalog: CredentialCatalog) : StoredPersonalLoad()
    data object Rejected : StoredPersonalLoad()
    data object Missing : StoredPersonalLoad()
    data class Failed(val message: String) : StoredPersonalLoad()
}

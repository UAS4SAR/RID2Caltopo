package org.ncssar.rid2caltopo.data

import okhttp3.Request
import org.json.JSONObject
import android.content.Context
import org.ncssar.rid2caltopo.app.R2CApplication

/** Short-lived edit authority is never included in exports or supplied by an import. */
object AircraftOrganizationAccess {
    private val resetLock = Any()
    @Volatile private var resetGeneration = 0L
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    @Volatile private var authorizedToken = ""
    @Volatile private var validUntil = 0L
    @Volatile private var verifiedToken = ""
    @Volatile private var verifiedScope = ""
    @Volatile private var username = ""
    @Volatile private var accessMessage = "Account has not been verified. Refresh access while online."
    @Volatile private var messageToken = ""
    @Volatile private var messageScope = ""
    @JvmStatic fun resetRuntimeState() = synchronized(resetLock) {
        resetGeneration++
        authorizedToken = ""; validUntil = 0L
        verifiedToken = ""; verifiedScope = ""; username = ""
        messageToken = ""; messageScope = ""
        accessMessage = "Account has not been verified. Refresh access while online."
        R2CApplication.getAppCtxt()?.getSharedPreferences("aircraft-readiness", Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        changes.value += 1
    }

    fun accessStatus(): String = when {
        !belongsToOrganization() -> "Local aircraft entries. No organization account is signed in."
        messageToken != CaltopoClient.GetTrackerCoordinationApiKey() || messageScope != scope() ->
            "Account has not been verified. Refresh access while online."
        else -> accessMessage
    }
    fun organizationUser(): String? = username.takeIf {
        it.isNotBlank() && belongsToOrganization() && verifiedToken == CaltopoClient.GetTrackerCoordinationApiKey() && verifiedScope == scope()
    }
    private val managedDownload = ThreadLocal.withInitial { false }
    private fun preferences() = requireNotNull(R2CApplication.getAppCtxt()).getSharedPreferences("aircraft-readiness", Context.MODE_PRIVATE)
    private fun scope() = CaltopoClient.GetTrackerCoordinationUrlPfx().trimEnd('/')
    fun cachedState(): JSONObject = runCatching {
        JSONObject(preferences().getString(scope(), "{}").orEmpty())
    }.getOrDefault(JSONObject())

    fun eligiblePilot(pilotJson: String): Boolean {
        val selected = JSONObject(pilotJson)
        val pilots = cachedState().optJSONArray("pilots") ?: return false
        return (0 until pilots.length()).map { pilots.getJSONObject(it) }.any {
            it.optString("memberId") == selected.optString("memberId") &&
                it.optString("callsign") == selected.optString("callsign") && it.optBoolean("eligible") &&
                it.optString("validUntil") >= java.time.LocalDate.now().toString()
        }
    }

    fun rememberedProfileId(): String? = preferences().getString(OperatingProfiles.preferenceKey(scope()), null)
    fun rememberProfile(profile: JSONObject) {
        preferences().edit().putString(OperatingProfiles.preferenceKey(scope()),
            profile.optString("missingProfileId", profile.optString("id"))).apply()
    }

    fun rememberedEquipment(remoteId: String): JSONObject =
        preferences().getString("${scope()}:$remoteId:equipment", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject().put("selectedAccessories", org.json.JSONArray(preferences().getStringSet("${scope()}:$remoteId:accessories", emptySet()).orEmpty().toList()))

    fun rememberEquipment(remoteId: String, value: FlightReadiness) {
        preferences().edit().putString("${scope()}:$remoteId:equipment", value.equipmentJSON().toString()).apply()
    }

    @JvmStatic fun belongsToOrganization(): Boolean =
        CaltopoClient.GetTrackerCoordinationApiKey().startsWith("r2c_dev_")

    @JvmStatic fun canEdit(): Boolean = !belongsToOrganization() ||
        (authorizedToken == CaltopoClient.GetTrackerCoordinationApiKey() && verifiedScope == scope() && System.currentTimeMillis() < validUntil)

    @JvmStatic fun requireEdit() {
        check(managedDownload.get() == true || canEdit()) {
            "Organization aircraft changes require current config_admin authorization. Refresh RID Map Entries while online."
        }
    }

    fun <T> applyingManagedDownload(action: () -> T): T {
        val previous = managedDownload.get()
        managedDownload.set(true)
        try { return action() } finally { managedDownload.set(previous) }
    }

    /** Run on the I/O dispatcher. A failed refresh removes edit authority. */
    @Synchronized
    fun refresh(): Boolean {
        val generation = resetGeneration
        authorizedToken = ""; validUntil = 0; username = ""
        if (!belongsToOrganization()) {
            changes.value += 1
            return true
        }
        val token = CaltopoClient.GetTrackerCoordinationApiKey()
        val requestedScope = scope()
        val endpoint = requestedScope + "/api/v1/aircraft-readiness"
        messageToken = token; messageScope = requestedScope
        accessMessage = "Checking organization account and RID editing access…"
        changes.value = changes.value + 1
        return runCatching {
            val request = Request.Builder().url(endpoint).header("X-SAR-Token", token).build()
            CaltopoSession.MyOkHttpClient.newCall(request).execute().use { response ->
                val responseBody = if (response.isSuccessful) JSONObject(response.body?.string().orEmpty()) else null
                synchronized(resetLock) {
                if (generation != resetGeneration) return@use false
                check(token == CaltopoClient.GetTrackerCoordinationApiKey() && requestedScope == scope())
                if (response.code == 401 || response.code == 403) {
                    preferences().edit().remove(requestedScope).apply()
                    changes.value = changes.value + 1
                }
                CaltopoClient.CTInfo("OrganizationAccess", "Tracker account verification HTTP ${response.code}")
                if (!response.isSuccessful) {
                    accessMessage = when (response.code) {
                        401, 403 -> "Tracker could not verify this tablet's enrollment (HTTP ${response.code}). Re-enroll using the current organization QR and sign in."
                        else -> "Tracker could not verify your account (HTTP ${response.code}). Try Refresh access again."
                    }
                    return@use false
                }
                val body = requireNotNull(responseBody)
                verifiedToken = token; verifiedScope = requestedScope; username = body.optString("username")
                CaltopoClient.CTInfo("OrganizationAccess", "Tracker account verification completed; identityPresent=${username.isNotEmpty()}")
                preferences().edit().putString(requestedScope, body.toString()).apply()
                changes.value = changes.value + 1
                if (body.optBoolean("canEditAircraft")) {
                    authorizedToken = token; validUntil = System.currentTimeMillis() + 300_000
                }
                accessMessage = if (body.optBoolean("canEditAircraft")) {
                    "RID editing allowed · config_admin verified."
                } else {
                    "Read-only · this account does not have config_admin. Contact an organization administrator."
                }
                canEdit()
                }
            }
        }.getOrElse { failure ->
            CaltopoClient.CTWarn("OrganizationAccess", "Tracker account verification failed; type=${failure.javaClass.simpleName}")
            if (generation == resetGeneration && token == CaltopoClient.GetTrackerCoordinationApiKey() && requestedScope == scope()) {
                accessMessage = when (failure) {
                    is java.io.IOException -> "Unable to reach Tracker to verify your account. Check your connection and try Refresh access again."
                    else -> "Tracker account verification could not be completed. Try Refresh access again."
                }
            }
            false
        }.also { changes.value = changes.value + 1 }
    }
}

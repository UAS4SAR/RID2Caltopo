package org.ncssar.rid2caltopo.data

import okhttp3.Request
import org.json.JSONObject
import android.content.Context
import org.ncssar.rid2caltopo.app.R2CApplication

/** Short-lived edit authority is never included in exports or supplied by an import. */
object AircraftOrganizationAccess {
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    @Volatile private var authorizedToken = ""
    @Volatile private var validUntil = 0L
    @Volatile private var verifiedToken = ""
    @Volatile private var verifiedScope = ""
    @Volatile private var username = ""
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
    fun refresh(): Boolean {
        authorizedToken = ""; validUntil = 0; username = ""
        if (!belongsToOrganization()) return true
        val token = CaltopoClient.GetTrackerCoordinationApiKey()
        val requestedScope = scope()
        val endpoint = requestedScope + "/api/v1/aircraft-readiness"
        return runCatching {
            val request = Request.Builder().url(endpoint).header("X-SAR-Token", token).build()
            CaltopoSession.MyOkHttpClient.newCall(request).execute().use { response ->
                check(token == CaltopoClient.GetTrackerCoordinationApiKey() && requestedScope == scope())
                if (response.code == 401 || response.code == 403) {
                    preferences().edit().remove(requestedScope).apply()
                    changes.value = changes.value + 1
                }
                check(response.isSuccessful)
                val body = JSONObject(response.body?.string().orEmpty())
                verifiedToken = token; verifiedScope = requestedScope; username = body.optString("username")
                preferences().edit().putString(requestedScope, body.toString()).apply()
                changes.value = changes.value + 1
                if (body.optBoolean("canEditAircraft")) {
                    authorizedToken = token; validUntil = System.currentTimeMillis() + 300_000
                }
                canEdit()
            }
        }.getOrDefault(false).also { changes.value = changes.value + 1 }
    }
}

package org.ncssar.rid2caltopo.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.app.R2CApplication

/** Local opt-in only: deliberately absent from app_config.proto and organization imports. */
data class ProximityAlertConsentState(val enabled: Boolean = false, val noticePending: Boolean = false) {
    fun requestEnable() = if (enabled) this else copy(noticePending = true)
    fun cancel() = copy(noticePending = false)
    fun confirmEnable() = if (noticePending) ProximityAlertConsentState(enabled = true) else this
    fun disable() = ProximityAlertConsentState()

    companion object {
        const val NOTICE_VERSION = 1
        fun restore(acceptedVersion: Int) = ProximityAlertConsentState(enabled = acceptedVersion == NOTICE_VERSION)
    }
}

object ProximityAlertConsent {
    const val TITLE = "Enable proximity alerts?"
    val notice = """Proximity alerts provide supplemental situational awareness. They do not detect all aircraft or guarantee safe separation.

Received telemetry may be inaccurate, delayed, intermittent, or unavailable. Altitude values may use different reference points. DJI video telemetry accuracy has not been verified; the app’s uncertainty allowances are estimates, not guarantees.

Alerts may occur late, occur unnecessarily, or fail to occur. The configured distance is an alert threshold—not a guaranteed safe separation distance. No alert does not mean the airspace is clear.

Continue visual observation, pilot coordination, and applicable operating procedures. Do not rely on these alerts to avoid a collision."""
    // Android backup and device-transfer rules include only app_config.pb, not these preferences.
    private fun preferences() = R2CApplication.getAppCtxt()?.getSharedPreferences("proximity_alert_consent", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(ProximityAlertConsentState.restore(preferences()?.getInt("accepted_version", 0) ?: 0))
    val state = mutableState.asStateFlow()

    private val mutableAlertAllAircraft = MutableStateFlow(preferences()?.getBoolean("alert_all_aircraft", false) ?: false)
    val alertAllAircraft = mutableAlertAllAircraft.asStateFlow()
    fun setAlertAllAircraft(value: Boolean) {
        preferences()?.edit()?.putBoolean("alert_all_aircraft", value)?.apply()
        mutableAlertAllAircraft.value = value
    }

    fun requestEnable() { mutableState.value = mutableState.value.requestEnable() }
    fun cancel() { mutableState.value = mutableState.value.cancel() }
    fun confirmEnable() {
        val next = mutableState.value.confirmEnable()
        if (!next.enabled) return
        preferences()?.edit()?.putInt("accepted_version", ProximityAlertConsentState.NOTICE_VERSION)
            ?.putLong("accepted_at", System.currentTimeMillis())?.apply()
        mutableState.value = next
    }
    fun disable() {
        preferences()?.edit()?.remove("accepted_version")?.remove("accepted_at")?.apply()
        mutableState.value = mutableState.value.disable()
    }
}

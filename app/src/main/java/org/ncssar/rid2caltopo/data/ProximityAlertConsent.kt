package org.ncssar.rid2caltopo.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.app.R2CApplication

/** Local opt-in only: deliberately absent from app_config.proto and organization imports. */
data class ProximityAlertConsentState(
    val enabled: Boolean = false,
    val noticePending: Boolean = false,
    /** Indices of notice paragraphs checked in the open dialog; cleared whenever it opens or closes. */
    val acknowledged: Set<Int> = emptySet()
) {
    /** Enabling requires a separate checked acknowledgment for every notice paragraph. */
    val canConfirm: Boolean get() = noticePending && (0 until NOTICE_PARAGRAPH_COUNT).all { it in acknowledged }
    /** Confirm button label; also its accessible name, so the disabled state explains itself. */
    val confirmLabel: String get() = if (canConfirm) ENABLE_LABEL else CONFIRM_EACH_LABEL
    fun requestEnable() = if (enabled) this else copy(noticePending = true, acknowledged = emptySet())
    fun toggleAcknowledgment(index: Int) = when {
        !noticePending || index !in 0 until NOTICE_PARAGRAPH_COUNT -> this
        index in acknowledged -> copy(acknowledged = acknowledged - index)
        else -> copy(acknowledged = acknowledged + index)
    }
    fun cancel() = copy(noticePending = false, acknowledged = emptySet())
    fun confirmEnable() = if (canConfirm) ProximityAlertConsentState(enabled = true) else this
    fun disable() = ProximityAlertConsentState()

    companion object {
        /** Version 2 adds per-paragraph checkboxes; bumping it asks earlier acceptors once more. */
        const val NOTICE_VERSION = 2
        const val NOTICE_PARAGRAPH_COUNT = 4
        const val ENABLE_LABEL = "Enable alerts"
        const val CONFIRM_EACH_LABEL = "Confirm each paragraph"
        fun restore(acceptedVersion: Int) = ProximityAlertConsentState(enabled = acceptedVersion == NOTICE_VERSION)
        /** An older accepted notice restores alerts off and offers the current notice once. */
        fun needsReacknowledgment(acceptedVersion: Int) = acceptedVersion in 1 until NOTICE_VERSION
    }
}

object ProximityAlertConsent {
    const val TITLE = "Enable proximity alerts?"
    val notice = """Proximity alerts provide supplemental situational awareness. They do not detect all aircraft or guarantee safe separation.

Received telemetry may be inaccurate, delayed, intermittent, or unavailable. Altitude values may use different reference points. DJI video telemetry accuracy has not been verified; the app’s uncertainty allowances are estimates, not guarantees.

Alerts may occur late, occur unnecessarily, or fail to occur. The configured distance is an alert threshold—not a guaranteed safe separation distance. No alert does not mean the airspace is clear.

Continue visual observation, pilot coordination, and applicable operating procedures. Do not rely on these alerts to avoid a collision."""
    /** One required checkbox per paragraph; the text itself is unchanged. */
    val noticeParagraphs: List<String> = notice.split("\n\n")
    // Android backup and device-transfer rules include only app_config.pb, not these preferences.
    private fun preferences() = R2CApplication.getAppCtxt()?.getSharedPreferences("proximity_alert_consent", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(ProximityAlertConsentState.restore(preferences()?.getInt("accepted_version", 0) ?: 0))
    val state = mutableState.asStateFlow()

    private val mutableReacknowledgmentPending = MutableStateFlow(
        ProximityAlertConsentState.needsReacknowledgment(preferences()?.getInt("accepted_version", 0) ?: 0)
    )
    /** True once per launch after an update changed the notice a user had accepted. */
    val reacknowledgmentPending = mutableReacknowledgmentPending.asStateFlow()
    /** Opens the current notice once; the stale acceptance is cleared first so later launches never re-prompt. */
    fun beginReacknowledgment() {
        if (!mutableReacknowledgmentPending.value) return
        mutableReacknowledgmentPending.value = false
        preferences()?.edit()?.remove("accepted_version")?.remove("accepted_at")?.apply()
        requestEnable()
    }

    private val mutableAlertAllAircraft = MutableStateFlow(preferences()?.getBoolean("alert_all_aircraft", false) ?: false)
    val alertAllAircraft = mutableAlertAllAircraft.asStateFlow()
    fun setAlertAllAircraft(value: Boolean) {
        preferences()?.edit()?.putBoolean("alert_all_aircraft", value)?.apply()
        mutableAlertAllAircraft.value = value
    }

    fun requestEnable() { mutableState.value = mutableState.value.requestEnable() }
    fun toggleAcknowledgment(index: Int) { mutableState.value = mutableState.value.toggleAcknowledgment(index) }
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

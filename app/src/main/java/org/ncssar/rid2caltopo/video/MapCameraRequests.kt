package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One-shot camera requests that must not fire from cold start or recomposition alone. */
object MapCameraRequests {
    private val deviceGpsRecenter = MutableStateFlow(0L)
    val deviceGpsRecenterToken = deviceGpsRecenter.asStateFlow()

    fun requestDeviceGpsRecenter() {
        deviceGpsRecenter.update { it + 1L }
    }

    internal fun resetForTests() {
        deviceGpsRecenter.value = 0L
    }
}

internal data class DeviceGpsRecenterPlan(
    val centerNow: Boolean,
    val pendingUntilFix: Boolean,
    val consumeRequest: Boolean,
)

internal fun planDeviceGpsRecenter(
    requestToken: Long,
    lastConsumedToken: Long,
    hasDeviceFix: Boolean,
): DeviceGpsRecenterPlan {
    if (requestToken == 0L || requestToken == lastConsumedToken) {
        return DeviceGpsRecenterPlan(centerNow = false, pendingUntilFix = false, consumeRequest = false)
    }
    return if (hasDeviceFix) {
        DeviceGpsRecenterPlan(centerNow = true, pendingUntilFix = false, consumeRequest = true)
    } else {
        DeviceGpsRecenterPlan(centerNow = false, pendingUntilFix = true, consumeRequest = true)
    }
}

internal fun shouldApplyPendingDeviceGpsRecenter(pending: Boolean, hasDeviceFix: Boolean): Boolean =
    pending && hasDeviceFix

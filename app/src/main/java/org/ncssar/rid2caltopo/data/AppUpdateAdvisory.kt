package org.ncssar.rid2caltopo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.ncssar.rid2caltopo.BuildConfig

data class AppUpdateAdvisoryState(
    val recommendedVersionCode: Int = 0,
    val updateUrl: String = "",
    val dismissedForSession: Boolean = false
) {
    val updateRequired: Boolean
        get() = recommendedVersionCode > BuildConfig.VERSION_CODE && !dismissedForSession
}

object AppUpdateAdvisory {
    private var playRecommendedVersionCode = 0
    private val _state = MutableStateFlow(AppUpdateAdvisoryState())
    val state: StateFlow<AppUpdateAdvisoryState> = _state

    @JvmStatic
    fun onTrackerRecommendation(recommendedVersionCode: Int, updateUrl: String?) {
        val normalizedCode = maxOf(recommendedVersionCode, playRecommendedVersionCode, 0)
        val normalizedUrl = if (playRecommendedVersionCode > 0 && playRecommendedVersionCode >= recommendedVersionCode) {
            "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"
        } else updateUrl?.trim().orEmpty()
        val current = _state.value
        val preserveDismissal = current.dismissedForSession &&
            current.recommendedVersionCode == normalizedCode &&
            current.updateUrl == normalizedUrl
        _state.value = current.copy(
            recommendedVersionCode = normalizedCode,
            updateUrl = normalizedUrl,
            dismissedForSession = preserveDismissal
        )
    }

    fun onPlayRecommendation(versionCode: Int) {
        playRecommendedVersionCode = maxOf(playRecommendedVersionCode, versionCode)
        onTrackerRecommendation(_state.value.recommendedVersionCode, _state.value.updateUrl)
    }

    fun dismissForSession() {
        _state.value = _state.value.copy(dismissedForSession = true)
    }

    @JvmStatic
    fun resetForTesting() {
        playRecommendedVersionCode = 0
        _state.value = AppUpdateAdvisoryState()
    }
}

package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Started, or the selection/dialog changed while checking so the caller must tell the user. */
internal enum class OfflinePrepRetryOutcome { Started, SelectionChanged }

/** Resolve missing/stale AOL metadata before checking capacity; never silently omit AOL. */
internal suspend fun <P> recoverOfflinePrep(
    includeAol: Boolean,
    matchingPlan: P?,
    resolvePlan: suspend () -> P,
    isCurrent: () -> Boolean,
    checkCapacity: suspend (P?) -> Unit,
    start: (P?) -> Unit,
): OfflinePrepRetryOutcome {
    val plan = if (includeAol) matchingPlan ?: resolvePlan() else null
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return OfflinePrepRetryOutcome.SelectionChanged
    checkCapacity(plan)
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return OfflinePrepRetryOutcome.SelectionChanged
    start(plan)
    return OfflinePrepRetryOutcome.Started
}

/** Title for the Download Map notice popup. Selection-changed notices are not failures. */
internal fun offlinePrepNoticeTitle(neutral: Boolean): String = if (neutral) "Download not started" else "Download failed"

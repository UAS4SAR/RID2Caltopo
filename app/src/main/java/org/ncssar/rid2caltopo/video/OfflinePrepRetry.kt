package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resolve missing/stale AOL metadata before checking capacity; never silently omit AOL. */
internal suspend fun <P> recoverOfflinePrep(
    includeAol: Boolean,
    matchingPlan: P?,
    resolvePlan: suspend () -> P,
    isCurrent: () -> Boolean,
    checkCapacity: suspend (P?) -> Unit,
    start: (P?) -> Unit,
) {
    val plan = if (includeAol) matchingPlan ?: resolvePlan() else null
    currentCoroutineContext().ensureActive()
    if (!isCurrent()) return
    checkCapacity(plan)
    currentCoroutineContext().ensureActive()
    if (isCurrent()) start(plan)
}

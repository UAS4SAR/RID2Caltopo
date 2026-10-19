package org.ncssar.rid2caltopo.mapping

import kotlinx.coroutines.*

/** The caller assigns ownership before starting; cancellation before dispatch still reports a stop. */
internal fun mappingJob(scope: CoroutineScope, onStopped: (Job, Throwable?) -> Unit,
    work: suspend CoroutineScope.() -> Unit): Job {
    val job = scope.launch(start = CoroutineStart.LAZY, block = work)
    job.invokeOnCompletion { onStopped(job, it) }
    return job
}

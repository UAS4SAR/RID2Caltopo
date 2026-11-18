package org.ncssar.rid2caltopo.storage

/**
 * Wraps work queued on a plain (non-scheduled) executor that touches storage. On such an
 * executor an uncaught exception reaches the thread's default handler and kills the whole app,
 * live video included. Failures are reported and the lane keeps running.
 */
object StorageJobGuard {
    /** Reporter for guarded failures; replaced in tests. Must not queue work on the failing lane. */
    @JvmStatic
    @Volatile
    var report: (tag: String, what: String, error: Throwable) -> Unit = { tag, what, error ->
        android.util.Log.e(tag, "$what failed; continuing without it", error)
    }

    @JvmStatic
    fun guard(tag: String, what: String, job: Runnable): Runnable = Runnable {
        try {
            job.run()
        } catch (error: RuntimeException) {
            runCatching { report(tag, what, error) }
        }
    }
}

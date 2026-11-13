package org.ncssar.rid2caltopo.app

/**
 * Starts one-time native/disk warm-up on its own daemon thread so the first screen does
 * not pay for it on the main thread. The caller never waits; failures are swallowed
 * (the real call site still loads lazily and reports its own error).
 */
internal object StartupPreload {
    fun start(name: String, work: Runnable): Thread =
        Thread({ runCatching { work.run() } }, name).apply {
            isDaemon = true
            start()
        }
}

package org.ncssar.rid2caltopo.storage

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.ncssar.rid2caltopo.ui.awaitingMapStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StartupWorkerQualificationTest {
    @Test fun stalledProviderDoesNotBlockMainAndErrorsReturnAsState() {
        check(Build.HARDWARE == "ranchu") { "Emulator qualification only" }
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            try {
                    val result = awaitingMapStorage<Unit> {
                        check(Looper.myLooper() != Looper.getMainLooper())
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                        throw IllegalStateException("provider unavailable during unlock")
                    }
                    assertTrue(result.isFailure)
            } catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val mainResponded = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { mainResponded.countDown() }
            assertTrue("Main waited behind the stalled provider", mainResponded.await(500, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        scope.cancel()
        failure.get()?.let { throw AssertionError("Storage worker failed", it) }
    }

    @Test fun strictModeDetectsAccidentalMainThreadDiskAccess() {
        check(Build.HARDWARE == "ranchu")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val violation = CountDownLatch(1)
        // Calibrate the detector with a deliberate fixture read; never enable death penalties
        // or install qualification policies in a user's normal app session.
        val file = java.io.File(instrumentation.targetContext.cacheDir, "strict-mode-${System.nanoTime()}")
        file.writeText("test")
        try {
            instrumentation.runOnMainSync {
                val previous = StrictMode.getThreadPolicy()
                try {
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads()
                        .penaltyListener({ it.run() }) { violation.countDown() }.build())
                    file.inputStream().use { it.read() }
                } finally {
                    // StrictMode delivers timed violations at the next Looper turn.
                    // Keep the listener installed until that delivery has run.
                    Handler(Looper.getMainLooper()).post { StrictMode.setThreadPolicy(previous) }
                }
            }
            assertTrue("StrictMode qualification detector was inactive", violation.await(2, TimeUnit.SECONDS))
        } finally { file.delete() }
    }
}

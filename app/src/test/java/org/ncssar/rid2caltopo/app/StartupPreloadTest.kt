package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StartupPreloadTest {
    @Test
    fun callerNeverWaitsForSlowPreload() {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val thread = StartupPreload.start("test-preload") { release.await(5, TimeUnit.SECONDS) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("start() returned after $elapsedMs ms", elapsedMs < 100)
        assertTrue(thread.isAlive)
        release.countDown()
        thread.join(2_000)
    }

    @Test
    fun runsOnNamedDaemonThreadNotCaller() {
        var ranOn: Thread? = null
        val thread = StartupPreload.start("ffmpeg-preload") { ranOn = Thread.currentThread() }
        thread.join(2_000)
        assertEquals("ffmpeg-preload", thread.name)
        assertTrue(thread.isDaemon)
        assertNotSame(Thread.currentThread(), ranOn)
    }

    @Test
    fun failureDoesNotEscape() {
        var uncaught: Throwable? = null
        val thread = StartupPreload.start("failing-preload") { throw UnsatisfiedLinkError("missing") }
        thread.setUncaughtExceptionHandler { _, e -> uncaught = e }
        thread.join(2_000)
        assertEquals(null, uncaught)
    }

    @Test
    fun applicationStartsFfmpegPreloadBeforeSettingsStartup() {
        val source = listOf("src/main/java/org/ncssar/rid2caltopo/app/R2CApplication.kt",
            "app/src/main/java/org/ncssar/rid2caltopo/app/R2CApplication.kt")
            .map(::File).first { it.exists() }.readText()
        val onCreate = source.substringAfter("override fun onCreate()").substringBefore("private fun initializeOnWorker")
        val preload = onCreate.indexOf("FfmpegBridge.preload()")
        assertTrue("FfmpegBridge.preload missing from onCreate", preload > 0)
        assertTrue(preload < onCreate.indexOf("SettingsStartup.start"))
        assertTrue(onCreate.substring(0, preload).contains("StartupPreload.start(\"ffmpeg-preload\")"))
    }
}

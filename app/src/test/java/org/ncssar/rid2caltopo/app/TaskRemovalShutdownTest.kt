package org.ncssar.rid2caltopo.app

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRemovalShutdownTest {
    private class Recorder(val block: CountDownLatch? = null) : TaskRemovalShutdown.Steps {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val released = CountDownLatch(1)
        val shutdownEntered = CountDownLatch(1)
        override fun shutdown() {
            events += "shutdown:" + Thread.currentThread().name
            shutdownEntered.countDown()
            block?.await(10, TimeUnit.SECONDS)
            events += "shutdown-done"
        }
        override fun stopMediaServer() { events += "media" }
        override fun release() { events += "release"; released.countDown() }
        override fun log(message: String) { }
    }

    @Test
    fun runsShutdownThenStreamServerThenReleaseOnceOffTheCallerThread() {
        val steps = Recorder()
        val removal = TaskRemovalShutdown()
        assertTrue(removal.begin("test", steps, 2_000))
        assertFalse(removal.begin("again", steps, 2_000))
        assertTrue(steps.released.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("shutdown:R2C-Shutdown", "shutdown-done", "media", "release"), steps.events.toList())
        assertTrue(removal.finishedInTime())
        assertFalse(removal.isActive())
    }

    @Test
    fun beginReturnsAtOnceAndStaysActiveWhileShutdownRuns() {
        val gate = CountDownLatch(1)
        val steps = Recorder(gate)
        val removal = TaskRemovalShutdown()
        val t0 = System.nanoTime()
        removal.begin("test", steps, 5_000)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 500)
        assertTrue(steps.shutdownEntered.await(2, TimeUnit.SECONDS))
        assertTrue(removal.isActive()) // stop requests are held off while this is true
        assertEquals(1L, steps.released.count)
        gate.countDown()
        assertTrue(steps.released.await(3, TimeUnit.SECONDS))
        assertFalse(removal.isActive())
    }

    @Test
    fun stuckShutdownStillReleasesTheServiceAtTheCap() {
        val steps = Recorder(CountDownLatch(1)) // never opened
        val removal = TaskRemovalShutdown()
        val t0 = System.nanoTime()
        removal.begin("test", steps, 200)
        assertTrue(steps.released.await(3, TimeUnit.SECONDS))
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue("released after $elapsed ms", elapsed in 150..2_000)
        assertFalse(removal.finishedInTime())
        assertEquals(listOf("media", "release"), steps.events.filter { it == "media" || it == "release" })
    }

    @Test
    fun failingShutdownStillStopsTheStreamServerAndReleases() {
        val removal = TaskRemovalShutdown()
        val released = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        removal.begin("test", object : TaskRemovalShutdown.Steps {
            override fun shutdown() { throw IllegalStateException("boom") }
            override fun stopMediaServer() { events += "media" }
            override fun release() { events += "release"; released.countDown() }
            override fun log(message: String) { }
        }, 2_000)
        assertTrue(released.await(3, TimeUnit.SECONDS))
        assertEquals(listOf("media", "release"), events.toList())
    }

    @Test
    fun onlyAFinishWithoutQuitHandsTheShutdownToTheService() {
        assertTrue(shouldServiceOwnShutdown(exitRequested = false, serviceRunning = true))
        assertFalse(shouldServiceOwnShutdown(exitRequested = true, serviceRunning = true))
        assertFalse(shouldServiceOwnShutdown(exitRequested = false, serviceRunning = false))
    }

    @Test
    fun manifestKeepsTheScanningServiceThroughASwipeAndHandlesWindowResizes() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        fun serviceBlock(name: String): String {
            val start = manifest.indexOf("android:name=\"$name\"")
            return manifest.substring(start, manifest.indexOf("/>", start))
        }
        assertTrue(serviceBlock(".app.ScanningService").contains("android:stopWithTask=\"false\""))
        assertTrue(serviceBlock(".app.MediaMTXService").contains("android:stopWithTask=\"true\""))
        val activity = serviceBlock(".app.R2CActivity")
        assertTrue(activity.contains("screenLayout"))
        val scanning = source("app/src/main/java/org/ncssar/rid2caltopo/app/ScanningService.java")
        val onTaskRemoved = scanning.substring(scanning.indexOf("public void onTaskRemoved"))
            .substringBefore("public static boolean BeginTaskRemovalShutdown")
        assertTrue(onTaskRemoved.contains("startTaskRemovalShutdown("))
        assertFalse(onTaskRemoved.contains("stopSelf()")) // released only after the shutdown
        assertFalse(source("app/src/main/java/org/ncssar/rid2caltopo/app/MediaMTXService.java")
            .contains("public void onTaskRemoved"))
    }

    private fun source(rel: String): String {
        val wd = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(wd, rel), File(wd.parentFile ?: wd, rel)).first(File::isFile).readText()
    }
}

package org.ncssar.rid2caltopo.startupbenchmark

import android.os.Build
import android.os.SystemClock
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Runs only on a disposable emulator selected by the qualification driver. */
class StartupQualification {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Before fun emulatorOnly() { check(Build.HARDWARE == "ranchu") { "Emulator qualification only" } }
    @Test fun coldStartup() = measure(StartupMode.COLD)
    @Test fun foregroundReturn() = measure(StartupMode.WARM)
    @Test fun scrollOperationalWarnings() = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(), iterations = 3,
        setupBlock = {
            pressHome(); startActivityAndWait(); device.waitForIdle()
            check(device.currentPackageName == PACKAGE)
            val output = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
            device.takeScreenshot(File(output, "warnings-start.png"))
            // Warm the touch/scroll path before measuring steady interaction.
            val x = device.displayWidth / 2
            val top = device.displayHeight / 4; val bottom = device.displayHeight * 3 / 4
            device.swipe(x, bottom, x, top, 30); device.swipe(x, top, x, bottom, 30)
            device.waitForIdle()
        },
        measureBlock = {
            val x = device.displayWidth / 2
            val top = device.displayHeight / 4; val bottom = device.displayHeight * 3 / 4
            repeat(2) { device.swipe(x, bottom, x, top, 30); device.swipe(x, top, x, bottom, 30) }
            device.waitForIdle()
        },
    )
    private fun measure(mode: StartupMode) = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = if (mode == StartupMode.COLD) listOf(StartupTimingMetric(), FrameTimingMetric()) else listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(), iterations = 3, startupMode = mode,
        setupBlock = {
            pressHome()
            // Macrobenchmark COLD force-stops only the PID snapshot it took, then
            // dropKernelPageCache can take seconds. A late :caltopo_probe start from
            // Application init (personal-session ContentProvider) can leave the package
            // "alive" for the post-kill check. Force-stop and wait for every process
            // name belonging to the package before Macrobenchmark's own kill/check.
            if (mode == StartupMode.COLD) forceStopAndAwaitGone(device)
        },
        measureBlock = { startActivityAndWait() },
    )

    companion object {
        private const val PACKAGE = "org.ncssar.rid2caltopo"
        private val PROCESS_NAMES = arrayOf(PACKAGE, "$PACKAGE:caltopo_probe")

        /** Force-stop and poll until no main or :caltopo_probe process remains. */
        internal fun forceStopAndAwaitGone(device: UiDevice, timeoutMs: Long = 8_000) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                device.executeShellCommand("am force-stop $PACKAGE")
                val alive = PROCESS_NAMES.mapNotNull { name ->
                    device.executeShellCommand("pidof $name").trim().takeIf { it.isNotEmpty() }?.let { "$name=$it" }
                }
                if (alive.isEmpty()) return
                check(SystemClock.elapsedRealtime() < deadline) {
                    "Package $PACKAGE still running after force-stop: ${alive.joinToString()}"
                }
                SystemClock.sleep(50)
            }
        }
    }
}

package org.ncssar.rid2caltopo.startupbenchmark

import android.os.Build
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
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
        packageName = "org.ncssar.rid2caltopo", metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(), iterations = 3,
        setupBlock = {
            pressHome(); startActivityAndWait(); device.waitForIdle()
            check(device.currentPackageName == "org.ncssar.rid2caltopo")
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
        packageName = "org.ncssar.rid2caltopo",
        metrics = if (mode == StartupMode.COLD) listOf(StartupTimingMetric(), FrameTimingMetric()) else listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(), iterations = 3, startupMode = mode,
        setupBlock = { pressHome() },
        measureBlock = { startActivityAndWait() },
    )
}

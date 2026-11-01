package org.ncssar.rid2caltopo.app

import android.app.ActivityManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainThreadStallMonitorTest {
    @Test
    fun foregroundUptimeStallIsReported() {
        assertTrue(
            MainThreadStallMonitor.shouldReportStall(
                18_546L,
                18_600L,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                true,
            ),
        )
    }

    @Test
    fun backgroundProcessFreezeIsSuppressed() {
        assertFalse(
            MainThreadStallMonitor.shouldReportStall(
                118_000L,
                118_050L,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
                true,
            ),
        )
        assertFalse(
            MainThreadStallMonitor.shouldReportStall(
                1_048_000L,
                1_048_100L,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND,
                true,
            ),
        )
    }

    @Test
    fun deviceSleepSkewIsSuppressed() {
        assertFalse(
            MainThreadStallMonitor.shouldReportStall(
                2_000L,
                60_000L,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                true,
            ),
        )
    }

    @Test
    fun subThresholdDelayIsIgnored() {
        assertFalse(
            MainThreadStallMonitor.shouldReportStall(
                500L,
                500L,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                true,
            ),
        )
    }
}

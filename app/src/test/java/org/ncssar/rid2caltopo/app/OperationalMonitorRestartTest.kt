package org.ncssar.rid2caltopo.app

import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.airspace.AirspaceCenter
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.notam.NotamCenter

class OperationalMonitorRestartTest {
    @Test
    fun monitorsResumeAfterShutdownWithoutRestartingTheApplicationProcess() {
        val context = ContextWrapper(null)
        val wasEnabled = CaltopoClient.GetNotamEnabled()
        CaltopoClient.SetNotamEnabled(false)
        try {
            repeat(2) {
                NotamCenter.initialize(context)
                AirspaceCenter.initialize(context)
                assertTrue(NotamCenter.isMonitoring)
                assertTrue(AirspaceCenter.isMonitoring)
                NotamCenter.shutdown()
                AirspaceCenter.shutdown()
                assertFalse(NotamCenter.isMonitoring)
                assertFalse(AirspaceCenter.isMonitoring)
            }
        } finally {
            NotamCenter.shutdown()
            AirspaceCenter.shutdown()
            CaltopoClient.SetNotamEnabled(wasEnabled)
        }
    }
}

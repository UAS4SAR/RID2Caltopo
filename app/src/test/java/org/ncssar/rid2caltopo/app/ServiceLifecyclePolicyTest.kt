package org.ncssar.rid2caltopo.app

import android.content.pm.ServiceInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ken, Nov 18 2026: when the session ends, nothing keeps running or comes back on its own. */
class ServiceLifecyclePolicyTest {
    private fun source(name: String) = File("src/main/java/org/ncssar/rid2caltopo/app/$name").readText()

    @Test
    fun systemRestartWithoutUiStaysStopped() {
        assertTrue(ServiceLifecyclePolicy.shouldStopUnrequestedStart(intentPresent = false, uiPresent = false))
        assertFalse(ServiceLifecyclePolicy.shouldStopUnrequestedStart(intentPresent = false, uiPresent = true))
        assertFalse(ServiceLifecyclePolicy.shouldStopUnrequestedStart(intentPresent = true, uiPresent = false))
        assertFalse(ServiceLifecyclePolicy.shouldStopUnrequestedStart(intentPresent = true, uiPresent = true))
    }

    @Test
    fun idleAlarmOnlyChecksDuringASession() {
        assertFalse(ServiceLifecyclePolicy.idleAlarmShouldCheck(scanningServiceRunning = false, uiPresent = false))
        assertTrue(ServiceLifecyclePolicy.idleAlarmShouldCheck(scanningServiceRunning = true, uiPresent = false))
        assertTrue(ServiceLifecyclePolicy.idleAlarmShouldCheck(scanningServiceRunning = false, uiPresent = true))
    }

    @Test
    fun locationTypeOnlyWhileWifiRidScanningIsOn() {
        val connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertEquals(connected, ServiceLifecyclePolicy.scanningForegroundTypes(false, true))
        assertEquals(connected, ServiceLifecyclePolicy.scanningForegroundTypes(false, false))
        assertEquals(connected, ServiceLifecyclePolicy.scanningForegroundTypes(true, false))
        assertEquals(connected or location, ServiceLifecyclePolicy.scanningForegroundTypes(true, true))
        assertEquals("connectedDevice", ServiceLifecyclePolicy.describeForegroundTypes(connected))
        assertEquals("connectedDevice|location", ServiceLifecyclePolicy.describeForegroundTypes(connected or location))
    }

    @Test
    fun scanningServiceNeverAsksAndroidToRestartIt() {
        val scanning = source("ScanningService.java")
        assertFalse(scanning.contains("START_STICKY"))
        assertFalse(scanning.contains("START_REDELIVER_INTENT"))
        assertTrue(scanning.contains("ServiceLifecyclePolicy.shouldStopUnrequestedStart("))
        assertTrue(scanning.contains("ServiceLifecyclePolicy.scanningForegroundTypes(") ||
            scanning.contains("desiredForegroundTypes()"))
        // The Wi-Fi toggle re-promotes the service with the new types.
        val apply = scanning.substringAfter("private void applyWifiRidScanningPreference()")
            .substringBefore("private void applyBluetoothRidTestPreference()")
        assertTrue(apply.contains("refreshForegroundTypes();"))
        for (name in listOf("MediaMTXService.java", "MapOfflineDownloadService.kt", "CaltopoPersonalAuthService.kt")) {
            assertFalse(name, source(name).contains("START_STICKY"))
        }
    }

    @Test
    fun idleAlarmCancelsItselfWithoutASession() {
        val receiver = source("AppIdleAlarmReceiver.java").substringAfter("public void onReceive(")
        val guard = receiver.indexOf("ServiceLifecyclePolicy.idleAlarmShouldCheck(")
        assertTrue(guard >= 0)
        assertTrue(guard < receiver.indexOf("CaltopoClient.CheckIdle()"))
        assertTrue(receiver.substring(guard, receiver.indexOf("CaltopoClient.CheckIdle()")).contains("cancel(context);"))
    }

    @Test
    fun everyDataSyncServiceStopsAtTheAndroidTimeLimit() {
        val media = source("MediaMTXService.java")
        val timeout = media.substringAfter("public void onTimeout(int startId, int fgsType)").substringBefore("\n    }\n")
        assertTrue(timeout.contains("stopSelf()"))
        assertTrue(timeout.contains("stoppedByTimeLimit = true"))
        // onDestroy performs the clean recording stop and staged-recording sync.
        val destroy = media.substringAfter("public void onDestroy()").substringBefore("\n    }\n")
        assertTrue(destroy.contains("MediaMTXNative.stop()"))
        assertTrue(destroy.contains("MediaMTXRecordingSync.syncAll"))
        assertTrue(source("R2CActivity.kt").contains("MediaMTXService.restartAfterTimeLimitIfNeeded(applicationContext)"))
        val auth = source("CaltopoPersonalAuthService.kt").substringAfter("override fun onTimeout(").substringBefore("\n    }\n")
        assertTrue(auth.contains("stopSelf()"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        Regex("""<service[^>]*foregroundServiceType="[^"]*dataSync[^"]*"[^>]*>""").findAll(manifest).forEach { match ->
            val name = Regex("""android:name="\.?([\w.]+)"""").find(match.value)!!.groupValues[1].substringAfterLast('.')
            val file = File("src/main/java/org/ncssar/rid2caltopo").walkTopDown()
                .firstOrNull { it.nameWithoutExtension == name && (it.extension == "kt" || it.extension == "java") }
            assertTrue("$name source", file != null)
            assertTrue("$name handles onTimeout", file!!.readText().contains("onTimeout("))
        }
    }
}

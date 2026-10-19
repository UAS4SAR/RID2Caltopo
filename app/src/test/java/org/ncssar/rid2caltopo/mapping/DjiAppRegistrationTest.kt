package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DjiAppRegistrationTest {
    private fun fixture(): ByteArray = File("../tools/mapping/tests/fixtures/dji-app-registration.json").readBytes()
    @Test fun sameAppRegistrationWorksWithoutAnyDeviceEnrollmentOrStoredCredentials() {
        repeat(2) {
            val value = DjiAppRegistration.decode(fixture())
            assertEquals("123456", value.appId)
            assertEquals("synthetic-app-key-1234567890", value.appKey)
            assertEquals("synthetic-license-not-for-flight-".repeat(3), value.appLicense)
        }
    }
    @Test fun rejectsMissingCorruptWrongTypeAndDeviceBoundRegistration() {
        for (bytes in listOf(ByteArray(0), "{}".toByteArray(), """{"type":"r2c_dji_device_provisioning","version":1}""".toByteArray())) {
            assertThrows(Exception::class.java) { DjiAppRegistration.decode(bytes) }
        }
        for ((key, value) in listOf("sha256" to "0".repeat(64), "enc" to "invalid", "version" to true)) {
            val objectValue = JSONObject(fixture().toString(Charsets.UTF_8)).put(key, value)
            assertThrows(Exception::class.java) { DjiAppRegistration.decode(objectValue.toString().toByteArray()) }
        }
    }
    private class FreshStore: DjiAppRegistration.Store {
        var value: DjiCloudCredentials? = null
        var accepted: String? = null
        var writes = 0
        var fail = false
        override fun receipt() = accepted
        override fun hasCredentials() = value != null
        override fun save(credentials: DjiCloudCredentials) { if (fail) error("Synthetic locked store"); value = credentials; writes++ }
        override fun record(hash: String) { accepted = hash }
    }
    @Test fun bootstrapsTwoFreshInstallationsAndRepairsMissingStoreWithoutEnrollment() {
        for (store in listOf(FreshStore(), FreshStore())) {
            DjiAppRegistration.install(fixture(), store)
            assertEquals("123456", store.value!!.appId)
            assertNotNull(store.accepted)
            DjiAppRegistration.install(fixture(), store)
            assertEquals(1, store.writes)
            store.value = null
            DjiAppRegistration.install(fixture(), store)
            assertEquals(2, store.writes)
        }
        val locked = FreshStore().apply { fail = true }
        assertThrows(Exception::class.java) { DjiAppRegistration.install(fixture(), locked) }
        assertNull(locked.accepted)
    }

}

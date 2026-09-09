package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class TrackerEnrollmentLinkTest {
    private val enrollment =
        "https://r2c-tracker.com/ncssar/enroll?token=signed-campaign-token"

    @Test
    fun `direct verified enrollment URL is accepted`() {
        assertEquals(enrollment, TrackerEnrollmentClient.normalizedEnrollmentUrl(enrollment))
    }

    @Test
    fun `browser fallback unwraps the same trusted enrollment URL`() {
        val encoded = URLEncoder.encode(enrollment, StandardCharsets.UTF_8.name())

        assertEquals(
            enrollment,
            TrackerEnrollmentClient.normalizedEnrollmentUrl("r2cenroll://open?url=$encoded")
        )
    }

    @Test
    fun `browser fallback rejects an untrusted nested URL`() {
        val encoded = URLEncoder.encode(
            "https://example.test/ncssar/enroll?token=stolen",
            StandardCharsets.UTF_8.name()
        )

        assertNull(
            TrackerEnrollmentClient.normalizedEnrollmentUrl("r2cenroll://open?url=$encoded")
        )
    }

    @Test
    fun `browser fallback rejects malformed percent encoding`() {
        assertNull(
            TrackerEnrollmentClient.normalizedEnrollmentUrl(
                "r2cenroll://open?url=https%3A%2F%2Fr2c-tracker.com%2Fncssar%2Fenroll%ZZ"
            )
        )
    }

    @Test
    fun `closed enrollment response opens only trusted reauthentication URL`() {
        val response = """{
          "organization":{"designator":"ncssar"},
          "tracker":{
            "base_url":"https://r2c-tracker.com/ncssar",
            "api_key":"r2c_dev_test",
            "faa_proxy_url":"https://r2c-tracker.com/faa/notams"
          },
          "credential":{
            "state":"reauth_required",
            "reauthentication_url":"https://r2c-tracker.com/ncssar/device-reauthenticate?token=test"
          }
        }""".trimIndent()

        assertEquals(
            "https://r2c-tracker.com/ncssar/device-reauthenticate?token=test",
            TrackerEnrollmentClient.parseEnrollmentResult(response, enrollment).reauthenticationUrl
        )

        val untrusted = response.replace(
            "https://r2c-tracker.com/ncssar/device-reauthenticate?token=test",
            "https://example.test/steal"
        )
        assertNull(
            TrackerEnrollmentClient.parseEnrollmentResult(untrusted, enrollment).reauthenticationUrl
        )
    }

    @Test
    fun `replacement candidates retain tracker identity name and model`() {
        val response = """{
          "schema_version":1,
          "candidates":[
            {
              "credential_id":"11111111-2222-3333-4444-555555555555",
              "device_name":"Ken T.'s Samsung SM-X930",
              "device_model":"Samsung SM-X930",
              "platform":"android"
            },
            {"credential_id":"", "device_name":"Incomplete"}
          ]
        }""".trimIndent()

        val candidates = TrackerEnrollmentClient.parseReplacementCandidates(response)

        assertEquals(1, candidates.size)
        assertEquals(
            "11111111-2222-3333-4444-555555555555",
            candidates.single().credentialId,
        )
        assertEquals("Ken T.'s Samsung SM-X930", candidates.single().deviceName)
        assertEquals("Samsung SM-X930", candidates.single().deviceModel)
    }

    @Test
    fun `Android owns replacement question and Apple remains unchanged`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val projectRoot = if (File(working, "app").isDirectory) working else working.parentFile
        val androidActivity = File(
            projectRoot,
            "app/src/main/java/org/ncssar/rid2caltopo/app/R2CActivity.kt",
        ).readText()
        val appleIdentity = File(
            projectRoot,
            "apple/App/AppleNetworkAddress.swift",
        ).readText()

        assertTrue(androidActivity.contains("Is this \${it.deviceName}?"))
        assertTrue(androidActivity.contains("Yes, same tablet"))
        assertTrue(androidActivity.contains("No, new tablet"))
        assertTrue(androidActivity.contains("replaceDeviceAuthorization"))
        assertFalse(appleIdentity.contains("replacement-candidates"))
    }

    @Test
    fun `manifest claims verified tracker enrollment links and fallback scheme`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val manifest = listOf(
            File(working, "src/main/AndroidManifest.xml"),
            File(working, "app/src/main/AndroidManifest.xml")
        ).first(File::isFile).readText()

        assertTrue(manifest.contains("android:autoVerify=\"true\""))
        assertTrue(manifest.contains("android:host=\"r2c-tracker.com\""))
        assertTrue(manifest.contains("android:pathPattern=\"/.*/enroll\""))
        assertTrue(manifest.contains("android:scheme=\"r2cenroll\""))
    }

    @Test
    fun `enrollment bootstraps managed configuration before a map exists`() {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val sourceRoot = if (File(working, "src/main").isDirectory) working else File(working, "app")
        val enrollmentClient = File(
            sourceRoot,
            "src/main/java/org/ncssar/rid2caltopo/data/TrackerEnrollmentClient.kt"
        ).readText()
        val application = File(
            sourceRoot,
            "src/main/java/org/ncssar/rid2caltopo/app/R2CApplication.kt"
        ).readText()

        assertTrue(enrollmentClient.contains("syncManagedConfigurationAfterEnrollment"))
        assertTrue(enrollmentClient.contains("installation_id"))
        assertTrue(enrollmentClient.contains("CaltopoMap.GetMyUUID()"))
        assertTrue(application.contains("retryManagedConfigurationBootstrap(this)"))
    }
}

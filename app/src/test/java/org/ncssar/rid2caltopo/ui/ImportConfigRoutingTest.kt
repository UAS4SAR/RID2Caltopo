package org.ncssar.rid2caltopo.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.TrackerEnrollmentResult

class ImportConfigRoutingTest {
    @Test
    fun `tracker enrollment wrapper is recognized and unwrapped for import`() {
        val enrollmentUrl = "https://r2c-tracker.com/ncssar/enroll?token=test-token"
        val wrapper =
            "r2cenroll://open?url=https%3A%2F%2Fr2c-tracker.com%2Fncssar%2Fenroll%3Ftoken%3Dtest-token"

        assertEquals(enrollmentUrl, normalizedTrackerEnrollmentImport(wrapper))
        assertEquals(enrollmentUrl, normalizedTrackerEnrollmentImport(enrollmentUrl))
    }

    @Test
    fun `json extension overrides generic provider mime type`() {
        assertEquals(
            ImportConfigFileKind.JSON_CONFIG,
            classifyImportConfigFile("NCSSAR_MutualAid.json", "application/octet-stream")
        )
    }

    @Test
    fun `zip extension routes to mutual aid package importer`() {
        assertEquals(
            ImportConfigFileKind.MUTUAL_AID_PACKAGE,
            classifyImportConfigFile("Incident_mutual_aid_package.zip", "application/octet-stream")
        )
    }

    @Test
    fun `mime types provide fallback when provider omits file name`() {
        assertEquals(
            ImportConfigFileKind.JSON_CONFIG,
            classifyImportConfigFile(null, "application/json; charset=utf-8")
        )
        assertEquals(
            ImportConfigFileKind.MUTUAL_AID_PACKAGE,
            classifyImportConfigFile(null, "application/zip")
        )
        assertEquals(
            ImportConfigFileKind.QR_IMAGE,
            classifyImportConfigFile(null, "image/png")
        )
    }

    @Test
    fun `saved QR image pixels decode to the original import payload`() {
        val payload =
            "r2cenroll://open?url=https%3A%2F%2Fr2c-tracker.com%2Fncssar%2Fenroll%3Ftoken%3Dimage-token"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        assertEquals(payload, decodeQrCodePixels(matrix.width, matrix.height, pixels))
    }

    @Test
    fun `unrecognized files are rejected`() {
        assertEquals(
            ImportConfigFileKind.UNSUPPORTED,
            classifyImportConfigFile("notes.pdf", "application/pdf")
        )
    }

    @Test
    fun `common saved QR image extensions route to image decoding`() {
        listOf("png", "jpg", "jpeg", "webp", "heic", "heif", "svg").forEach { extension ->
            assertEquals(
                ImportConfigFileKind.QR_IMAGE,
                classifyImportConfigFile("enrollment.$extension", "application/octet-stream")
            )
        }
    }

    @Test
    fun `tracker SVG QR rasterizes and decodes to the original import payload`() {
        val payload =
            "r2cenroll://open?url=https%3A%2F%2Fr2c-tracker.com%2Fncssar%2Fenroll%3Ftoken%3Dsvg-token"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 128, 128)
        val path = buildString {
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (matrix[x, y]) append("M${x},${y}H${x + 1}V${y + 1}H${x}z")
                }
            }
        }
        val svg =
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}"><path d="$path" fill="#000000"/></svg>"""

        val raster = requireNotNull(rasterizeTrackerQrSvg(svg))

        assertEquals(payload, decodeQrCodePixels(raster.width, raster.height, raster.pixels))
    }

    @Test
    fun `SVG QR parser rejects executable or unsupported path commands`() {
        val svg =
            """<svg viewBox="0 0 10 10"><script>alert(1)</script><path d="M1,1L9,9z"/></svg>"""

        assertEquals(null, parseTrackerQrSvg(svg))
    }

    @Test
    fun `tracker enrollment immediately refreshes both FAA safety sources`() {
        CaltopoClient.ResetPersistedClientState()
        var notamRefreshRequested = false
        var airspaceRefreshRequested = false
        var trackReplayRequested = false

        applyTrackerEnrollmentAndRefreshNotams(
            result = TrackerEnrollmentResult(
                organization = "TEST_ORG",
                trackerBaseUrl = "https://tracker.example.test/org",
                deviceToken = "test-device-token",
                faaProxyUrl = "https://tracker.example.test/faa/notams",
                enrollmentUrl = "https://r2c-tracker.com/test/enroll?token=campaign-token"
            ),
            requestNotamRefresh = { notamRefreshRequested = true },
            requestAirspaceRefresh = { airspaceRefreshRequested = true },
            requestTrackReplay = { trackReplayRequested = true }
        )

        assertEquals("https://tracker.example.test/faa/notams", CaltopoClient.GetTrackerFaaProxyUrl())
        assertEquals(
            "https://r2c-tracker.com/test/enroll?token=campaign-token",
            CaltopoClient.GetTrackerEnrollmentUrl()
        )
        assertTrue(notamRefreshRequested)
        assertTrue(airspaceRefreshRequested)
        assertTrue(trackReplayRequested)
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun `tracker enrollment defers protected refreshes until reauthentication`() {
        CaltopoClient.ResetPersistedClientState()
        var notamRefreshRequested = false
        var airspaceRefreshRequested = false
        var trackReplayRequested = false

        applyTrackerEnrollmentAndRefreshNotams(
            result = TrackerEnrollmentResult(
                organization = "TEST_ORG",
                trackerBaseUrl = "https://tracker.example.test/org",
                deviceToken = "pending-device-token",
                faaProxyUrl = "https://tracker.example.test/faa/notams",
                enrollmentUrl = "https://r2c-tracker.com/test/enroll?token=campaign-token",
                reauthenticationUrl = "https://r2c-tracker.com/test/device-reauthenticate?token=pending"
            ),
            requestNotamRefresh = { notamRefreshRequested = true },
            requestAirspaceRefresh = { airspaceRefreshRequested = true },
            requestTrackReplay = { trackReplayRequested = true }
        )

        assertFalse(notamRefreshRequested)
        assertFalse(airspaceRefreshRequested)
        assertFalse(trackReplayRequested)
        CaltopoClient.ResetPersistedClientState()
    }
}

package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import javax.net.ServerSocketFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.KeyManagerFactory
import java.security.KeyStore
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates

class MappingTransferServerTest {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun server(root: File, expires: Long = System.currentTimeMillis() + 60000) = MappingTransferServer(
        "127.0.0.1", JSONObject().put("missionId", "test-mission"), "<kml>boundary</kml>", root,
        ServerSocketFactory.getDefault(), { "transfer page".toByteArray() }, expiresAt = expires).apply { start() }

    private fun request(server: MappingTransferServer, method: String, path: String, body: ByteArray? = null,
        auth: Boolean = true, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val connection = URL(server.baseUrl + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 2000; connection.readTimeout = 2000; connection.requestMethod = method
        if (auth) connection.setRequestProperty("Authorization", "Bearer ${server.token}")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (body != null) { connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
        return try { val status = connection.responseCode; status to (if (status < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty() }
        finally { connection.disconnect() }
    }

    @Test fun browserCannotReadMissionOrDownloadWithoutSessionAndCrossOriginRequestsAreRejected() {
        val root = Files.createTempDirectory("mapping-transfer").toFile()
        server(root).use { server ->
            assertEquals(200, request(server, "GET", "/", auth = false).first)
            assertEquals(401, request(server, "GET", "/api/status", auth = false).first)
            assertEquals(401, request(server, "GET", "/download/boundary.kml", auth = false).first)
            val url = URL(server.baseUrl)
            java.net.Socket(url.host, url.port).use { socket ->
                socket.getOutputStream().write(("POST /api/photos HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\nOrigin: https://unrelated.example\r\nAuthorization: Bearer ${server.token}\r\nContent-Length: 2\r\n\r\n{}").toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("403"))
            }
            assertEquals("<kml>boundary</kml>", request(server, "GET", "/download/boundary.kml").second)
            assertFalse(request(server, "GET", "/api/status").second.contains(server.token))
        }
        root.deleteRecursively()
    }

    @Test fun interruptedTransferResumesAfterServerRestartAndDuplicatesDoNotCreateMoreOriginals() {
        val root = Files.createTempDirectory("mapping-transfer").toFile()
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + ByteArray(5000) { (it % 251).toByte() }
        val hash = digest(bytes)
        val record = JSONObject().put("name", "DJI_001_IR.JPG").put("sizeBytes", bytes.size).put("sha256", hash).toString().toByteArray()
        server(root).use { server ->
            assertEquals(200, request(server, "POST", "/api/photos", record).first)
            assertEquals(200, request(server, "PUT", "/api/photos/$hash", bytes.copyOfRange(0, 2000), headers = mapOf("X-Upload-Offset" to "0")).first)
            assertEquals(400, request(server, "PUT", "/api/photos/$hash", bytes.copyOfRange(0, 2000), headers = mapOf("X-Upload-Offset" to "0")).first)
            assertEquals(400, request(server, "POST", "/api/complete", JSONObject().put("sha256", org.json.JSONArray(listOf(hash))).toString().toByteArray()).first)
        }
        server(root).use { server ->
            assertEquals(2000, JSONObject(request(server, "POST", "/api/photos", record).second).getInt("offset"))
            assertEquals(200, request(server, "PUT", "/api/photos/$hash", bytes.copyOfRange(2000, bytes.size), headers = mapOf("X-Upload-Offset" to "2000")).first)
            assertEquals(200, request(server, "POST", "/api/photos/$hash/commit", "{}".toByteArray()).first)
            assertTrue(JSONObject(request(server, "POST", "/api/photos", record).second).getBoolean("complete"))
            assertEquals(200, request(server, "POST", "/api/complete", JSONObject().put("sha256", org.json.JSONArray(listOf(hash))).toString().toByteArray()).first)
            assertEquals(1, JSONObject(request(server, "GET", "/api/status").second).getInt("photoCount"))
            assertArrayEquals(bytes, File(root, "originals/$hash.jpg").readBytes())
        }
        root.deleteRecursively()
    }

    @Test fun corruptPhotosAndUnsafeNamesNeverBecomeOriginals() {
        val root = Files.createTempDirectory("mapping-store").toFile(); val store = MappingPhotoStore(root)
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 3, 4); val hash = "a".repeat(64)
        assertThrows(IllegalArgumentException::class.java) { store.register("../photo.jpg", bytes.size.toLong(), hash) }
        assertThrows(IllegalArgumentException::class.java) { store.register("photo.JPG", Long.MAX_VALUE, hash) }
        store.register("photo.JPG", bytes.size.toLong(), hash); store.append(hash, 0, bytes)
        assertThrows(IllegalArgumentException::class.java) { store.commit(hash) }
        assertEquals(0L, store.progress(hash).getLong("offset")); assertEquals(0, store.inventory().getInt("photoCount"))
        root.deleteRecursively()
    }

    @Test fun truncatedHttpUploadCannotAdvanceStoredOffset() {
        val root = Files.createTempDirectory("mapping-store").toFile(); val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 3, 4)
        val hash = digest(bytes)
        server(root).use { server ->
            server.store.register("original.jpg", bytes.size.toLong(), hash)
            val url = URL(server.baseUrl)
            java.net.Socket(url.host, url.port).use { socket ->
                socket.getOutputStream().write(("PUT /api/photos/$hash HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\nAuthorization: Bearer ${server.token}\r\nX-Upload-Offset: 0\r\nContent-Length: 4\r\n\r\n").toByteArray() + bytes.copyOfRange(0, 2))
                socket.shutdownOutput(); socket.getInputStream().readBytes()
            }
            assertEquals(0L, server.store.progress(hash).getLong("offset"))
        }
        root.deleteRecursively()
    }

    @Test fun httpsDownloadUsesTabletCertificateAndRequiresSession() {
        val root = Files.createTempDirectory("mapping-https").toFile()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val password = "test".toCharArray()
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setKeyEntry("test", certificate.keyPair.private, password, arrayOf(certificate.certificate))
        }
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val tls = SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, null) }
        MappingTransferServer("127.0.0.1", JSONObject().put("missionId", "test"), "<kml/>", root,
            tls.serverSocketFactory, { "page".toByteArray() }).use { server ->
            server.start(); assertTrue(server.qrUrl.startsWith("https://"))
            val connection = URL(server.baseUrl + "/download/boundary.kml").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build().sslSocketFactory()
            connection.setRequestProperty("Authorization", "Bearer ${server.token}")
            assertEquals(200, connection.responseCode)
            assertEquals("<kml/>", connection.inputStream.bufferedReader().use { it.readText() })
            assertTrue(connection.getHeaderField("Content-Disposition").contains("R2C-boundary.kml"))
            connection.disconnect()
        }
        root.deleteRecursively()
    }

    @Test fun shortCodePairsBrowserAndWrongCodeAttemptsAreRateLimited() {
        val root = Files.createTempDirectory("mapping-pair").toFile()
        server(root).use { server ->
            val body = JSONObject().put("code", server.pairingCode).toString().toByteArray()
            assertEquals(server.token, JSONObject(request(server, "POST", "/api/pair", body, auth = false).second).getString("token"))
            val wrong = JSONObject().put("code", "wrong").toString().toByteArray()
            repeat(5) { assertEquals(401, request(server, "POST", "/api/pair", wrong, auth = false).first) }
            assertEquals(429, request(server, "POST", "/api/pair", body, auth = false).first)
        }
        root.deleteRecursively()
    }

    @Test fun pilotBridgeReportRequiresSessionAndCannotPersistCredentials() {
        val root = Files.createTempDirectory("mapping-pilot-bridge").toFile()
        server(root).use { server ->
            val report = JSONObject().put("bridgeAvailable", true).put("licenseVerification", true)
                .put("workspaceSelection", true).put("platformInformation", true)
            assertEquals(401, request(server, "POST", "/api/pilot2-capabilities", report.toString().toByteArray(), auth = false).first)
            assertEquals(200, request(server, "POST", "/api/pilot2-capabilities", report.toString().toByteArray()).first)
            val saved = JSONObject(File(root, "pilot2-capabilities.json").readText())
            assertTrue(saved.getBoolean("bridgeAvailable")); assertFalse(saved.toString().contains(server.token))
            report.put("appKey", "unwanted-secret")
            assertEquals(400, request(server, "POST", "/api/pilot2-capabilities", report.toString().toByteArray()).first)
            assertFalse(File(root, "pilot2-capabilities.json").readText().contains("unwanted-secret"))
        }
        root.deleteRecursively()
    }
}

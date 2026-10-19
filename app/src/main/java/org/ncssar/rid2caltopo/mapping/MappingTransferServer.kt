package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLServerSocket

/** Small, bounded HTTP server for a browser on the same trusted LAN. No DJI or account access. */
class MappingTransferServer(
    host: String,
    val mission: JSONObject,
    boundaryKml: String,
    directory: File,
    socketFactory: ServerSocketFactory,
    private val assets: (String) -> ByteArray,
    private val changed: (String, JSONObject) -> Unit = { _, _ -> },
    val expiresAt: Long = System.currentTimeMillis() + 2 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    val store = MappingPhotoStore(directory)
    private val socket = socketFactory.createServerSocket(0, 8, InetAddress.getByName(host)).apply { soTimeout = 1000 }
    val baseUrl = "${if (socket is SSLServerSocket) "https" else "http"}://$host:${socket.localPort}"
    val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    val qrUrl = "$baseUrl/#$token"
    val pairingCode = "%06d".format(SecureRandom().nextInt(1000000))
    private var pairFailures = 0
    private var pairLockedUntil = 0L
    private val origin = baseUrl
    private val authority = "$host:${socket.localPort}"
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "R2C-mapping-client").apply { isDaemon = true } })
    @Volatile private var closed = false
    private val boundary = boundaryKml.toByteArray()
    private val manifest = mission.toString(2).toByteArray()
    private val packageBytes = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip ->
            (listOf("mission.json" to manifest, "boundary.kml" to boundary)+listOfNotNull(runCatching{"mission.kmz" to DjiMappingRoute(mission).kmz()}.getOrNull())).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }.toByteArray()

    fun start() {
        Thread({
            while (!closed) {
                if (now() >= expiresAt) { close(); changed("Transfer expired; originals retained", store.inventory()); break }
                try {
                    val client = socket.accept().apply { soTimeout = 20000 }
                    clients.add(client)
                    try { workers.execute { client.use { serve(it) }; clients.remove(client) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
                } catch (_: SocketTimeoutException) { /* expiry check */ }
                catch (_: SocketException) { if (!closed) close() }
            }
        }, "R2C-mapping-server").apply { isDaemon = true; start() }
    }

    private fun serve(client: Socket) {
        try {
            val input = BufferedInputStream(client.getInputStream())
            val line = readLine(input) ?: return
            val request = line.split(' ')
            require(request.size == 3 && request[2] == "HTTP/1.1") { "Unsupported HTTP request." }
            val method = request[0]; val path = request[1]
            val headers = mutableMapOf<String, String>()
            var headerBytes = line.length
            while (true) {
                val header = readLine(input) ?: throw IllegalArgumentException("Incomplete request.")
                headerBytes += header.length
                require(headerBytes <= 16384 && headers.size < 64) { "Request headers too large." }
                if (header.isEmpty()) break
                val colon = header.indexOf(':'); require(colon > 0) { "Invalid header." }
                val key = header.substring(0, colon).lowercase()
                require(!headers.containsKey(key)) { "Duplicate header." }
                headers[key] = header.substring(colon + 1).trim()
            }
            if (headers["host"] != authority || headers["origin"]?.let { it != origin } == true ||
                headers["sec-fetch-site"] == "cross-site") { respond(client, 403, "application/json", error("Use this tablet's transfer page.")); return }
            if (closed || now() >= expiresAt) { respond(client, 410, "application/json", error("Transfer expired. Open a new QR session in R2C.")); return }
            require(!headers.containsKey("transfer-encoding")) { "Chunked HTTP bodies are not supported." }
            val length = headers["content-length"]?.toLongOrNull() ?: 0
            require(length in 0..MappingPhotoStore.CHUNK_BYTES.toLong()) { "Request body too large." }
            if (method == "GET" && path in listOf("/", "/transfer.js", "/sha256.js", "/style.css", "/pilot2.html", "/pilot2.js")) {
                val name = if (path == "/") "index.html" else path.drop(1)
                val type = when { name.endsWith("html") -> "text/html; charset=utf-8"; name.endsWith("css") -> "text/css"; else -> "text/javascript" }
                respond(client, 200, type, assets(name)); return
            }
            if (method == "POST" && path == "/api/pair") {
                require(length in 1..1024) { "Enter the six-digit code from R2C." }
                val bytes = ByteArray(length.toInt())
                var offset = 0
                while (offset < bytes.size) { val count = input.read(bytes, offset, bytes.size - offset); require(count > 0); offset += count }
                val code = JSONObject(bytes.toString(Charsets.UTF_8)).optString("code")
                synchronized(this) {
                    if (now() < pairLockedUntil) { respond(client, 429, "application/json", error("Too many code attempts. Wait one minute, then try again.")); return }
                    if (!MessageDigest.isEqual(code.toByteArray(), pairingCode.toByteArray())) {
                        pairFailures++
                        if (pairFailures >= 5) { pairLockedUntil = now() + 60000; pairFailures = 0 }
                        respond(client, 401, "application/json", error("Code did not match. Read the code on the R2C tablet.")); return
                    }
                    respond(client, 200, "application/json", JSONObject().put("token", token).toString().toByteArray())
                }
                changed("Controller browser paired", store.inventory()); return
            }
            val provided = headers["authorization"].orEmpty().removePrefix("Bearer ")
            if (!MessageDigest.isEqual(provided.toByteArray(), token.toByteArray())) {
                respond(client, 401, "application/json", error("Open the QR link shown by R2C.")); return
            }
            val body = ByteArray(length.toInt())
            var position = 0
            while (position < body.size) { val count = input.read(body, position, body.size - position); require(count > 0) { "Upload interrupted." }; position += count }
            var result: JSONObject? = null
            when {
                method == "GET" && path == "/api/status" -> result = store.inventory().put("mission", mission).put("expiresAt", expiresAt)
                method == "GET" && path == "/download/mission.kmz" -> {
                    respond(client,200,"application/vnd.google-earth.kmz",DjiMappingRoute(mission).kmz(),"R2C-mission.kmz");return
                }
                method == "GET" && path == "/download/boundary.kml" -> {
                    respond(client, 200, "application/vnd.google-earth.kml+xml", boundary, "R2C-boundary.kml")
                    changed("Boundary downloaded; review route in Pilot 2", store.inventory()); return
                }
                method == "GET" && path == "/download/mission.zip" -> { respond(client, 200, "application/zip", packageBytes, "R2C-mission.zip"); return }
                method == "POST" && path == "/api/photos" -> {
                    val value = JSONObject(body.toString(Charsets.UTF_8))
                    result = store.register(value.getString("name"), value.getLong("sizeBytes"), value.getString("sha256"))
                }
                path.matches(Regex("/api/photos/[a-f0-9]{64}")) -> {
                    val digest = path.substringAfterLast('/')
                    result = when (method) {
                        "GET" -> store.progress(digest)
                        "PUT" -> store.append(digest, headers["x-upload-offset"]?.toLongOrNull() ?: -1L, body)
                        else -> null
                    }
                    if (method == "PUT") changed("Receiving original photos", store.inventory())
                }
                method == "POST" && path.matches(Regex("/api/photos/[a-f0-9]{64}/commit")) -> {
                    result = store.commit(path.split('/')[3]); changed("Original photo verified and stored", store.inventory())
                }
                method == "POST" && path == "/api/complete" -> {
                    val values = JSONObject(body.toString(Charsets.UTF_8)).getJSONArray("sha256")
                    result = store.finish((0 until values.length()).map { values.getString(it) })
                    changed("Selected originals received and verified", result)
                }
                method == "POST" && path == "/api/pilot2-capabilities" -> {
                    val report = JSONObject(body.toString(Charsets.UTF_8))
                    val keys = listOf("bridgeAvailable", "licenseVerification", "workspaceSelection", "platformInformation")
                    require(report.keys().asSequence().toSet() == keys.toSet() && keys.all { report.get(it) is Boolean }) { "Unsupported Pilot 2 capability report." }
                    MappingPhotoStore.writeJson(File(store.directory, "pilot2-capabilities.json"), report.put("reportedAt", java.time.Instant.now().toString()))
                    changed(if (report.getBoolean("bridgeAvailable")) "Pilot 2 native bridge detected; license qualification is next" else "Page did not expose a Pilot 2 bridge", store.inventory())
                    result = JSONObject().put("saved", true)
                }
            }
            if (result == null) respond(client, 404, "application/json", error("Unknown transfer action."))
            else respond(client, 200, "application/json", result.toString().toByteArray())
        } catch (e: IllegalArgumentException) { runCatching { respond(client, 400, "application/json", error(e.message ?: "Invalid request.")) } }
        catch (_: Exception) { runCatching { respond(client, 500, "application/json", error("Transfer stopped. Select the same files to resume; originals are retained.")) } }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 4096) {
            val value = input.read(); if (value < 0) return null
            if (value == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(value)
        }
        throw IllegalArgumentException("HTTP line too long.")
    }

    private fun error(message: String) = JSONObject().put("error", message).toString().toByteArray()
    private fun respond(client: Socket, status: Int, type: String, bytes: ByteArray, filename: String? = null) {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 410 -> "Gone"; 429 -> "Too Many Requests"; else -> "Server Error" }
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\n" +
            (filename?.let { "Content-Disposition: attachment; filename=\"$it\"\r\n" } ?: "") + "\r\n"
        client.getOutputStream().buffered().apply { write(header.toByteArray()); write(bytes); flush() }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }; clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}

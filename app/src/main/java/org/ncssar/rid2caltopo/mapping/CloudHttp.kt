package org.ncssar.rid2caltopo.mapping

import java.io.*
import java.net.URI
import java.net.URLDecoder
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

data class CloudHttpResponse(val status: Int = 200, val type: String = "application/json", val bytes: ByteArray = ByteArray(0),
    val file: File? = null, val headers: Map<String, String> = emptyMap(), val head: Boolean = false)

class CloudHttpRequest(val method: String, val uri: URI, val headers: Map<String, String>, val input: BufferedInputStream) {
    val path: String = uri.path
    val query: Map<String, String> = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }
    fun readBody(maximum: Long): ByteArray = ByteArrayOutputStream().also { copyBody(it, maximum) }.toByteArray()
    fun copyBody(output: OutputStream, maximum: Long): Long {
        val transfer = headers["transfer-encoding"]
        var written = 0L
        fun copy(count: Long) {
            require(count >= 0 && written + count <= maximum) { "Request body exceeds the local upload limit." }
            var remaining = count; val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                require(read > 0) { "Upload interrupted; incomplete bytes were retained separately." }
                output.write(buffer, 0, read); written += read; remaining -= read
            }
        }
        if (transfer != null) {
            require(transfer.lowercase() == "chunked" && !headers.containsKey("content-length")) { "Unsupported request framing." }
            while (true) {
                val line = CloudHttp.line(input) ?: throw IllegalArgumentException("Incomplete chunk header.")
                val count = line.substringBefore(';').toLongOrNull(16) ?: throw IllegalArgumentException("Invalid chunk length.")
                if (count == 0L) {
                    var trailers = 0
                    while (true) { val trailer = CloudHttp.line(input) ?: throw IllegalArgumentException("Incomplete trailers."); trailers += trailer.length; require(trailers <= 8192); if (trailer.isEmpty()) break }
                    break
                }
                copy(count); require(CloudHttp.line(input) == "") { "Invalid chunk ending." }
            }
        } else {
            val count = headers["content-length"]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("Invalid content length.") } ?: 0
            copy(count)
        }
        return written
    }
}

object CloudHttp {
    fun line(input: InputStream): String? {
        val value = ByteArrayOutputStream()
        while (value.size() <= 8192) {
            val next = input.read(); if (next < 0) return null
            if (next == 10) return value.toString("US-ASCII").removeSuffix("\r")
            value.write(next)
        }
        throw IllegalArgumentException("HTTP line too long.")
    }
    fun request(socket: Socket): CloudHttpRequest? {
        val input = BufferedInputStream(socket.getInputStream())
        val words = (line(input) ?: return null).split(' ')
        require(words.size == 3 && words[2] in listOf("HTTP/1.1", "HTTP/1.0")) { "Unsupported HTTP request." }
        val uri = URI(words[1]); require(!uri.isAbsolute && uri.fragment == null && uri.rawPath.startsWith('/'))
        val headers = mutableMapOf<String, String>(); var total = 0
        while (true) {
            val line = line(input) ?: throw IllegalArgumentException("Incomplete headers."); total += line.length
            require(total <= 32768 && headers.size < 100) { "Too many HTTP headers." }
            if (line.isEmpty()) break
            val colon = line.indexOf(':'); require(colon > 0)
            val name = line.substring(0, colon).lowercase(); require(!headers.containsKey(name)) { "Duplicate header." }
            headers[name] = line.substring(colon + 1).trim()
        }
        require(!headers.containsKey("transfer-encoding") || !headers.containsKey("content-length")) { "Ambiguous request body." }
        return CloudHttpRequest(words[0], uri, headers, input)
    }
    fun respond(socket: Socket, response: CloudHttpResponse) {
        val reason = when(response.status) { 200 -> "OK"; 204 -> "No Content"; 302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 410 -> "Gone"; 429 -> "Too Many Requests"; else -> "Error" }
        val length = response.file?.length() ?: response.bytes.size.toLong()
        val output = socket.getOutputStream().buffered()
        val header = "HTTP/1.1 ${response.status} $reason\r\nContent-Type: ${response.type}\r\nContent-Length: $length\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
        output.write(header.toByteArray())
        if (!response.head) { if (response.file != null) response.file.inputStream().use { it.copyTo(output, 64 * 1024) } else output.write(response.bytes) }
        output.flush()
    }
}

/** RFC 6455 framing; masked client frames, bounded fragmentation, ping/pong. */
class CloudWebSocket(private val socket: Socket, private val input: InputStream) {
    private val output = socket.getOutputStream()
    fun upgrade(request: CloudHttpRequest, protocol: String? = null) {
        require(request.headers["sec-websocket-version"] == "13") { "Unsupported WebSocket version." }
        val key = request.headers["sec-websocket-key"].orEmpty(); require(Base64.getDecoder().decode(key).size == 16)
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        val header = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n" +
            (protocol?.let { "Sec-WebSocket-Protocol: $it\r\n" } ?: "") + "\r\n"
        output.write(header.toByteArray()); output.flush()
    }
    @Synchronized fun send(bytes: ByteArray, opcode: Int = 1) {
        require(bytes.size <= 1024 * 1024)
        output.write(0x80 or opcode)
        when { bytes.size < 126 -> output.write(bytes.size); bytes.size <= 65535 -> { output.write(126); output.write(bytes.size shr 8); output.write(bytes.size and 255) }
            else -> { output.write(127); val value = bytes.size.toLong(); for (shift in 56 downTo 0 step 8) output.write((value shr shift).toInt() and 255) } }
        output.write(bytes); output.flush()
    }
    fun receive(): ByteArray? {
        val collected = ByteArrayOutputStream(); var started = false
        while (true) {
            val first = input.read(); if (first < 0) return null
            val second = input.read(); require(second >= 0 && second and 128 != 0 && first and 112 == 0) { "Unsupported WebSocket frame." }
            val opcode = first and 15; val final = first and 128 != 0
            var length = (second and 127).toLong()
            if (length == 126L) length = readNumber(2) else if (length == 127L) length = readNumber(8)
            require(length in 0..1024 * 1024 && collected.size() + length <= 1024 * 1024) { "WebSocket message too large." }
            val mask = readExact(4); val data = readExact(length.toInt())
            data.indices.forEach { data[it] = (data[it].toInt() xor mask[it % 4].toInt()).toByte() }
            if (opcode >= 8) {
                require(final && length <= 125)
                when(opcode) { 8 -> { send(data, 8); return null }; 9 -> send(data, 10); 10 -> {}; else -> throw IllegalArgumentException("Unknown WebSocket control frame.") }
                continue
            }
            require((!started && opcode in listOf(1,2)) || (started && opcode == 0)) { "Invalid fragmented WebSocket message." }
            started = true; collected.write(data)
            if (final) return collected.toByteArray()
        }
    }
    private fun readNumber(count: Int): Long { var result = 0L; repeat(count) { val value = input.read(); require(value >= 0); result = (result shl 8) or value.toLong() }; return result }
    private fun readExact(count: Int) = ByteArray(count).also { bytes -> var offset = 0; while(offset < count) { val read = input.read(bytes, offset, count-offset); require(read > 0); offset += read } }
    fun mqttInput(): InputStream = object : InputStream() {
        private var buffer = ByteArray(0); private var offset = 0
        override fun read(): Int {
            while (offset == buffer.size) { buffer = receive() ?: return -1; offset = 0 }
            return buffer[offset++].toInt() and 255
        }
    }
}

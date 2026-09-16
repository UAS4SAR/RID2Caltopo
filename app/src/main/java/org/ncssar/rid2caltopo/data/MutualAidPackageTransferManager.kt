package org.ncssar.rid2caltopo.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import org.ncssar.rid2caltopo.app.R2CActivity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class MutualAidPackageShareProgress(
    val receiverName: String = "",
    val bytesSent: Long = 0L,
    val totalBytes: Long = 0L,
    val phase: String = "Ready"
)

data class MutualAidPackageCompletedReceiver(
    val receiverName: String,
    val completedAtEpochMs: Long
)

data class MutualAidPackageShareSession(
    val token: String,
    val qrContent: String,
    val packageName: String,
    val packageFileName: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val expiresAtEpochMs: Long,
    val host: String,
    val port: Int,
    val progress: MutualAidPackageShareProgress = MutualAidPackageShareProgress(),
    val completedReceivers: List<MutualAidPackageCompletedReceiver> = emptyList(),
    val statusMessage: String = "Ready for receiver"
)

sealed class MutualAidPackageImportState {
    object Idle : MutualAidPackageImportState()
    data class Downloading(
        val packageName: String,
        val bytesRead: Long,
        val totalBytes: Long,
        val phase: String
    ) : MutualAidPackageImportState()
    data class Importing(
        val packageName: String,
        val phase: String
    ) : MutualAidPackageImportState()
    data class Success(
        val packageName: String,
        val message: String
    ) : MutualAidPackageImportState()
    data class Error(
        val packageName: String,
        val message: String
    ) : MutualAidPackageImportState()
}

object MutualAidPackageTransferManager {
    private const val TAG = "MaPackageTransfer"
    private const val HTTP_PATH = "/ma-package"
    private const val HEADER_RECEIVER = "X-R2C-Receiver"
    private const val MAX_SESSION_MINUTES = 30L
    private const val TLS_KEY_ALIAS = "ma-transfer"
    private const val TLS_KEY_PASSWORD = "rid2caltopo-transfer"
    private const val PROGRESS_UPDATE_BYTES = 256 * 1024L
    private const val PROGRESS_UPDATE_MS = 250L
    private const val TRANSFER_LOG_BYTES = 5L * 1024L * 1024L
    private const val TRANSFER_LOG_MS = 5_000L
    private const val TRANSFER_BUFFER_SIZE = 256 * 1024
    private const val RECEIVER_TELEMETRY_BYTES = 1L * 1024L * 1024L
    private const val RECEIVER_TELEMETRY_MS = 5_000L
    private const val FLUSH_INTERVAL_BYTES = 1L * 1024L * 1024L
    private const val READ_STALL_LOG_MS = 1_500L
    private const val WRITE_STALL_LOG_MS = 1_500L
    private const val IMPORT_IDLE_TIMEOUT_MS = 30_000L
    private const val IMPORT_MAX_ATTEMPTS = 3
    private const val IMPORT_RETRY_BASE_DELAY_MS = 1_500L
    private val serverExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val _shareSession = MutableStateFlow<MutualAidPackageShareSession?>(null)
    val shareSession: StateFlow<MutualAidPackageShareSession?> = _shareSession.asStateFlow()
    private val _importState = MutableStateFlow<MutualAidPackageImportState>(MutualAidPackageImportState.Idle)
    val importState: StateFlow<MutualAidPackageImportState> = _importState.asStateFlow()
    private val currentServerSocket = AtomicReference<ServerSocket?>()
    private val currentServedFile = AtomicReference<File?>()
    private val currentSessionId = AtomicReference<String?>()
    private val currentImportSessionId = AtomicReference<String?>()
    private val currentImportCall = AtomicReference<Call?>()
    private val currentImportTempFile = AtomicReference<File?>()
    fun startShareSession(
        context: Context,
        packageFile: File,
        packageName: String
    ): Pair<Boolean, String> {
        stopShareSession()
        val host = R2CMqttManager.GetMyIpAddress().trim()
        if (host.isBlank()) {
            return false to "Could not determine local IP address for MA package sharing."
        }
        return try {
            val serverTlsConfig = createServerTlsConfig(host)
            val serverSocket = serverTlsConfig.socketFactory.createServerSocket(0).apply {
                reuseAddress = true
                soTimeout = 1000
            }
            val port = serverSocket.localPort
            val sessionId = UUID.randomUUID().toString()
            val sha256 = sha256Hex(packageFile)
            val expiresAt = System.currentTimeMillis() + MAX_SESSION_MINUTES * 60_000L
            val token = MutualAidPackageTransferToken.encode(
                MutualAidPackageTransferToken.Config(
                    host = host,
                    port = port,
                    sessionId = sessionId,
                    packageName = packageName,
                    sizeBytes = packageFile.length(),
                    sha256 = sha256,
                    tlsPublicKeySha256 = serverTlsConfig.publicKeySha256,
                    expiresAtEpochMs = expiresAt
                )
            )
            currentServerSocket.set(serverSocket)
            currentServedFile.set(packageFile)
            currentSessionId.set(sessionId)
            _shareSession.value = MutualAidPackageShareSession(
                token = token,
                qrContent = "r2cmapkg1://" + token.removePrefix(MutualAidPackageTransferToken.MAGIC_PREFIX),
                packageName = packageName,
                packageFileName = packageFile.name,
                fileSizeBytes = packageFile.length(),
                sha256 = sha256,
                expiresAtEpochMs = expiresAt,
                host = host,
                port = port
            )
            CaltopoClient.CTInfo(
                TAG,
                "Share session ready host=$host port=$port file='${packageFile.name}' size=${packageFile.length()} sha256=$sha256"
            )
            serverExecutor.execute { runServerLoop(serverSocket, sessionId, packageFile) }
            true to "MA package ready to share."
        } catch (e: Exception) {
            CaltopoClient.CTWarn(TAG, "startShareSession() failed.", e)
            stopShareSession()
            false to (e.message ?: "Failed to start MA package share session.")
        }
    }

    fun stopShareSession() {
        currentSessionId.set(null)
        currentServedFile.getAndSet(null)?.delete()
        currentServerSocket.getAndSet(null)?.runCatching { close() }
        _shareSession.value = null
    }

    private var lastImportToken: String? = null

    fun retryImport(context: Context) {
        lastImportToken?.let { importFromToken(context, it) }
    }

    fun dismissImportState() {
        _importState.value = MutualAidPackageImportState.Idle
    }

    fun cancelImport() {
        val sessionId = currentImportSessionId.getAndSet(null)
        currentImportCall.getAndSet(null)?.cancel()
        currentImportTempFile.getAndSet(null)?.runCatching { delete() }
        if (sessionId != null) {
            CaltopoClient.CTDebug(TAG, "cancelImport(): canceled sid=$sessionId")
        }
        _importState.value = MutualAidPackageImportState.Idle
    }

    fun importFromToken(context: Context, token: String) {
        val config = MutualAidPackageTransferToken.decode(token.trim())
        if (config == null) {
            _importState.value = MutualAidPackageImportState.Error("MA package", "Invalid MA package token.")
            return
        }
        cancelImport()
        lastImportToken = token
        val appContext = context.applicationContext
        currentImportSessionId.set(config.sessionId)
        ioExecutor.execute {
            val packageName = config.packageName.ifBlank { "MA package" }
            val tempFile = File(appContext.cacheDir.resolve("ma-transfer"), "${config.sessionId}.zip").apply {
                parentFile?.mkdirs()
                if (exists()) delete()
            }
            currentImportTempFile.set(tempFile)
            try {
                downloadPackageWithRetries(appContext, config, packageName, tempFile)
                if (currentImportSessionId.get() != config.sessionId) return@execute
                val actualSha = sha256Hex(tempFile)
                if (!actualSha.equals(config.sha256, ignoreCase = true)) {
                    throw IllegalStateException("Downloaded MA package checksum did not match QR token.")
                }
                CaltopoClient.CTDebug(TAG, "Download complete sid=${config.sessionId} bytes=${tempFile.length()} sha256=$actualSha")
                _importState.value = MutualAidPackageImportState.Importing(packageName, "Importing")
                val result = MutualAidPackageManager.importPackage(appContext, androidx.core.content.FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    tempFile
                ))
                tempFile.delete()
                _importState.value = if (result.first) {
                    MutualAidPackageImportState.Success(packageName, result.second)
                } else {
                    MutualAidPackageImportState.Error(packageName, result.second)
                }
            } catch (e: Exception) {
                tempFile.delete()
                if (currentImportSessionId.get() == config.sessionId) {
                    CaltopoClient.CTWarn(TAG, "importFromToken() failed.", e)
                    _importState.value = MutualAidPackageImportState.Error(packageName, if (isRetryableImportException(e)) {
                        "Could not reach the sharing device at ${config.host}:${config.port}. Connect both devices to the same Wi-Fi or hotspot and keep the sender’s MA Package QR panel open. Then retry. If sharing was restarted or its address changed, scan the new QR.\n\n${e.message.orEmpty()}"
                    } else e.message ?: "MA package transfer failed.")
                } else {
                    CaltopoClient.CTDebug(TAG, "importFromToken(): ignoring late failure from canceled sid=${config.sessionId}")
                }
            } finally {
                currentImportCall.compareAndSet(currentImportCall.get(), null)
                currentImportTempFile.compareAndSet(tempFile, null)
                currentImportSessionId.compareAndSet(config.sessionId, null)
            }
        }
    }

    private fun downloadPackageWithRetries(
        context: Context,
        config: MutualAidPackageTransferToken.Config,
        packageName: String,
        tempFile: File
    ) {
        var attempt = 1
        while (true) {
            if (currentImportSessionId.get() != config.sessionId) throw InterruptedIOException("Transfer canceled.")
            if (System.currentTimeMillis() >= config.expiresAtEpochMs) throw IllegalStateException("This MA share has expired. Ask the sender to prepare a new share and scan its QR.")
            tempFile.delete()
            try {
                downloadPackage(context, config, packageName, tempFile, attempt)
                return
            } catch (e: Exception) {
                val retryable = isRetryableImportException(e)
                if (currentImportSessionId.get() != config.sessionId || !retryable || attempt >= IMPORT_MAX_ATTEMPTS) throw e
                val delayMs = IMPORT_RETRY_BASE_DELAY_MS * attempt
                CaltopoClient.CTWarn(
                    TAG,
                    "downloadPackageWithRetries(): attempt $attempt/$IMPORT_MAX_ATTEMPTS failed; retrying in ${delayMs}ms",
                    e
                )
                val retryReason = describeImportRetryReason(e)
                _importState.value = MutualAidPackageImportState.Downloading(
                    packageName,
                    0L,
                    config.sizeBytes,
                    "$retryReason Retrying (${attempt + 1}/$IMPORT_MAX_ATTEMPTS)"
                )
                Thread.sleep(delayMs)
                attempt++
            }
        }
    }

    private fun downloadPackage(
        context: Context,
        config: MutualAidPackageTransferToken.Config,
        packageName: String,
        tempFile: File,
        attempt: Int
    ) {
        val httpClient = createPinnedClient(context, config)
        val receiverName = localDeviceName()
        val request = Request.Builder()
            .url("https://${config.host}:${config.port}$HTTP_PATH?sid=${config.sessionId}")
            .header(HEADER_RECEIVER, receiverName)
            .get()
            .build()
        CaltopoClient.CTDebug(
            TAG,
            "Connecting to sender host=${config.host} port=${config.port} sid=${config.sessionId} receiver='$receiverName' attempt=$attempt"
        )
        _importState.value = MutualAidPackageImportState.Downloading(packageName, 0L, config.sizeBytes, "Connecting")
        val call = httpClient.newCall(request)
        currentImportCall.set(call)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Sender returned HTTP ${response.code}.")
            }
            val totalBytes = response.body?.contentLength()?.takeIf { it > 0L } ?: config.sizeBytes
            val downloadStartMs = System.currentTimeMillis()
            var bytesReadTotal = 0L
            var lastByteAtMs = downloadStartMs
            var lastTelemetryAtMs = downloadStartMs
            var lastTelemetryBytes = 0L
            CaltopoClient.CTDebug(
                TAG,
                "Download response sid=${config.sessionId} attempt=$attempt code=${response.code} totalBytes=$totalBytes"
            )
            response.handshake?.let { handshake ->
                CaltopoClient.CTDebug(
                    TAG,
                    "Download TLS sid=${config.sessionId} attempt=$attempt tls=${handshake.tlsVersion} cipher=${handshake.cipherSuite}"
                )
            }
            response.body?.byteStream()?.use { input ->
                BufferedInputStream(input).use { buffered ->
                    tempFile.outputStream().use { output ->
                        BufferedOutputStream(output).use { bufferedOut ->
                            val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                            var lastProgressBytes = 0L
                            var lastProgressAtMs = 0L
                            var lastLoggedBytes = 0L
                            var lastLoggedAtMs = downloadStartMs
                            var lastFlushedBytes = 0L
                            while (true) {
                                val readStartedMs = System.currentTimeMillis()
                                val read = buffered.read(buffer)
                                val readCompletedMs = System.currentTimeMillis()
                                val readDurationMs = readCompletedMs - readStartedMs
                                if (read < 0) break
                                bufferedOut.write(buffer, 0, read)
                                bytesReadTotal += read
                                if (bytesReadTotal - lastFlushedBytes >= FLUSH_INTERVAL_BYTES) {
                                    bufferedOut.flush()
                                    lastFlushedBytes = bytesReadTotal
                                }
                                lastByteAtMs = readCompletedMs
                                if (readDurationMs >= READ_STALL_LOG_MS) {
                                    CaltopoClient.CTWarn(
                                        TAG,
                                        "Receiver read stall sid=${config.sessionId} attempt=$attempt waitMs=$readDurationMs readBytes=$read totalBytes=$bytesReadTotal/$totalBytes"
                                    )
                                }
                                if (shouldPublishProgress(bytesReadTotal, totalBytes, lastProgressBytes, lastProgressAtMs)) {
                                    _importState.value = MutualAidPackageImportState.Downloading(
                                        packageName,
                                        bytesReadTotal,
                                        totalBytes,
                                        "Downloading"
                                    )
                                    lastProgressBytes = bytesReadTotal
                                    lastProgressAtMs = System.currentTimeMillis()
                                }
                                if (shouldLogTransferProgress(bytesReadTotal, totalBytes, lastLoggedBytes, lastLoggedAtMs)) {
                                    CaltopoClient.CTDebug(
                                        TAG,
                                        "Download progress sid=${config.sessionId} attempt=$attempt bytes=$bytesReadTotal/$totalBytes"
                                    )
                                    lastLoggedBytes = bytesReadTotal
                                    lastLoggedAtMs = readCompletedMs
                                }
                                if (shouldEmitReceiverTelemetry(bytesReadTotal, lastTelemetryBytes, lastByteAtMs, lastTelemetryAtMs)) {
                                    val elapsedMs = (lastByteAtMs - downloadStartMs).coerceAtLeast(1L)
                                    val windowMs = (lastByteAtMs - lastTelemetryAtMs).coerceAtLeast(1L)
                                    val windowBytes = (bytesReadTotal - lastTelemetryBytes).coerceAtLeast(0L)
                                    CaltopoClient.CTDebug(
                                        TAG,
                                        "Receiver telemetry sid=${config.sessionId} attempt=$attempt bytes=$bytesReadTotal/$totalBytes " +
                                                "avgMbps=${formatMbps(bytesReadTotal, elapsedMs)} " +
                                                "windowMbps=${formatMbps(windowBytes, windowMs)} " +
                                                "idleMs=${System.currentTimeMillis() - lastByteAtMs}"
                                    )
                                    lastTelemetryBytes = bytesReadTotal
                                    lastTelemetryAtMs = lastByteAtMs
                                }
                            }
                            bufferedOut.flush()
                        }
                    }
                }
            } ?: throw IllegalStateException("Sender returned an empty MA package.")
        }
    }

    private data class ServerTlsConfig(
        val socketFactory: SSLServerSocketFactory,
        val publicKeySha256: String
    )

    private fun createServerTlsConfig(host: String): ServerTlsConfig {
        val certificate = HeldCertificate.Builder()
            .commonName(host)
            .addSubjectAlternativeName(host)
            .validityInterval(
                System.currentTimeMillis() - 60_000L,
                System.currentTimeMillis() + (MAX_SESSION_MINUTES + 5L) * 60_000L
            )
            .build()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(
                TLS_KEY_ALIAS,
                certificate.keyPair.private,
                TLS_KEY_PASSWORD.toCharArray(),
                arrayOf(certificate.certificate)
            )
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, TLS_KEY_PASSWORD.toCharArray())
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, SecureRandom())
        }
        return ServerTlsConfig(
            socketFactory = sslContext.serverSocketFactory,
            publicKeySha256 = sha256Hex(certificate.certificate.publicKey.encoded)
        )
    }

    private fun createPinnedClient(context: Context, config: MutualAidPackageTransferToken.Config): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client auth not supported.")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull()
                    ?: throw CertificateException("Missing server certificate.")
                certificate.checkValidity()
                val actual = sha256Hex(certificate.publicKey.encoded)
                if (!actual.equals(config.tlsPublicKeySha256, ignoreCase = true)) {
                    throw CertificateException("Server public key does not match QR token.")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        // Use the directly connected LAN even when Android prefers cellular for internet access.
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val address = InetAddress.getByName(config.host)
        val localNetwork = manager.allNetworks.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            val localTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            localTransport && capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true &&
                manager.getLinkProperties(network)?.routes.orEmpty().any {
                !it.isDefaultRoute && it.matches(address)
            }
        }
        return OkHttpClient.Builder()
            .apply { localNetwork?.let { socketFactory(it.socketFactory) } }
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(IMPORT_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun runServerLoop(serverSocket: ServerSocket, sessionId: String, packageFile: File) {
        while (currentSessionId.get() == sessionId) {
            if (System.currentTimeMillis() > (_shareSession.value?.expiresAtEpochMs ?: 0L)) {
                _shareSession.value = _shareSession.value?.copy(statusMessage = "Share session expired")
                stopShareSession()
                return
            }
            try {
                val socket = serverSocket.accept()
                socket.use { serveSocket(it, sessionId, packageFile) }
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (currentSessionId.get() != sessionId) {
                    return
                }
                CaltopoClient.CTWarn(TAG, "runServerLoop() accept failed for sid=$sessionId", e)
            }
        }
    }

    private fun serveSocket(socket: Socket, sessionId: String, packageFile: File) {
        socket.soTimeout = 30_000
        socket.keepAlive = true
        socket.tcpNoDelay = true
        runCatching { socket.sendBufferSize = TRANSFER_BUFFER_SIZE }
        runCatching { socket.receiveBufferSize = TRANSFER_BUFFER_SIZE }
        if (socket is SSLSocket) {
            runCatching { socket.enableSessionCreation = true }
            socket.startHandshake()
            runCatching {
                CaltopoClient.CTDebug(
                    TAG,
                    "Sender TLS sid=$sessionId peer=${socket.inetAddress?.hostAddress.orEmpty()} tls=${socket.session.protocol} cipher=${socket.session.cipherSuite}"
                )
            }
        }
        val input = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        val requestLine = input.readLine() ?: return
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
                    line.substring(idx + 1).trim()
            }
        }
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            writeHttpError(socket, 400, "Bad Request")
            return
        }
        val path = parts[1]
        val sid = path.substringAfter("sid=", "")
        if (!path.startsWith(HTTP_PATH) || sid != sessionId) {
            writeHttpError(socket, 404, "Not Found")
            return
        }
        val receiverName = headers[HEADER_RECEIVER.lowercase(Locale.US)]
            ?.takeIf { it.isNotBlank() }
            ?: socket.inetAddress?.hostAddress.orEmpty().ifBlank { "Receiver" }
        CaltopoClient.CTDebug(TAG, "Accepted receiver='$receiverName' sid=$sessionId size=${packageFile.length()}")
        _shareSession.value = _shareSession.value?.copy(
            progress = MutualAidPackageShareProgress(receiverName, 0L, packageFile.length(), "Sending"),
            statusMessage = "Sending to $receiverName"
        )
        try {
            var sent = 0L
            var lastProgressBytes = 0L
            var lastProgressAtMs = 0L
            var lastLoggedBytes = 0L
            val output = BufferedOutputStream(socket.getOutputStream())
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/zip\r\n")
                append("Content-Length: ${packageFile.length()}\r\n")
                append("Content-Disposition: attachment; filename=\"${packageFile.name}\"\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            packageFile.inputStream().use { fileInput ->
                BufferedInputStream(fileInput).use { buffered ->
                    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                    var lastLoggedAtMs = System.currentTimeMillis()
                    var lastFlushedBytes = 0L
                    while (true) {
                        val read = buffered.read(buffer)
                        if (read < 0) break
                        val writeStartedMs = System.currentTimeMillis()
                        output.write(buffer, 0, read)
                        val writeCompletedMs = System.currentTimeMillis()
                        val writeDurationMs = writeCompletedMs - writeStartedMs
                        sent += read
                        if (sent - lastFlushedBytes >= FLUSH_INTERVAL_BYTES) {
                            output.flush()
                            lastFlushedBytes = sent
                        }
                        if (writeDurationMs >= WRITE_STALL_LOG_MS) {
                            CaltopoClient.CTWarn(
                                TAG,
                                "Sender write stall receiver='$receiverName' sid=$sessionId waitMs=$writeDurationMs writeBytes=$read totalBytes=$sent/${packageFile.length()}"
                            )
                        }
                        if (shouldPublishProgress(sent, packageFile.length(), lastProgressBytes, lastProgressAtMs)) {
                            _shareSession.value = _shareSession.value?.copy(
                                progress = MutualAidPackageShareProgress(receiverName, sent, packageFile.length(), "Sending"),
                                statusMessage = "Sending to $receiverName"
                            )
                            lastProgressBytes = sent
                            lastProgressAtMs = writeCompletedMs
                        }
                        if (shouldLogTransferProgress(sent, packageFile.length(), lastLoggedBytes, lastLoggedAtMs)) {
                            CaltopoClient.CTDebug(
                                TAG,
                                "Send progress receiver='$receiverName' sid=$sessionId bytes=$sent/${packageFile.length()}"
                            )
                            lastLoggedBytes = sent
                            lastLoggedAtMs = writeCompletedMs
                        }
                    }
                }
            }
            output.flush()
            CaltopoClient.CTDebug(TAG, "Completed receiver='$receiverName' sid=$sessionId bytes=$sent")
            val completed = MutualAidPackageCompletedReceiver(receiverName, System.currentTimeMillis())
            _shareSession.value = _shareSession.value?.let {
                it.copy(
                    progress = MutualAidPackageShareProgress("", 0L, packageFile.length(), "Ready"),
                    completedReceivers = it.completedReceivers + completed,
                    statusMessage = "Ready for another receiver"
                )
            }
        } catch (e: Exception) {
            CaltopoClient.CTWarn(TAG, "serveSocket() failed for receiver='$receiverName' sid=$sessionId", e)
            _shareSession.value = _shareSession.value?.copy(
                progress = MutualAidPackageShareProgress("", 0L, packageFile.length(), "Ready"),
                statusMessage = "Transfer failed for $receiverName"
            )
        }
    }

    private fun writeHttpError(socket: Socket, code: Int, message: String) {
        runCatching {
            val output = socket.getOutputStream()
            output.write(
                ("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.UTF_8)
            )
            output.flush()
        }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun localDeviceName(): String {
        val activityName = R2CActivity.MyDeviceName.trim()
        if (activityName.isNotEmpty() && activityName != "<unknown>") return activityName
        val model = Build.MODEL?.trim().orEmpty()
        if (model.isNotEmpty()) return model
        return "RID2Caltopo Receiver"
    }

    private fun shouldPublishProgress(
        bytesDone: Long,
        totalBytes: Long,
        lastBytes: Long,
        lastAtMs: Long
    ): Boolean {
        if (bytesDone <= 0L) return false
        if (bytesDone >= totalBytes && totalBytes > 0L) return true
        if (bytesDone - lastBytes >= PROGRESS_UPDATE_BYTES) return true
        val now = System.currentTimeMillis()
        return now - lastAtMs >= PROGRESS_UPDATE_MS
    }

    private fun shouldLogTransferProgress(
        bytesDone: Long,
        totalBytes: Long,
        lastLoggedBytes: Long,
        lastLoggedAtMs: Long
    ): Boolean {
        if (bytesDone <= 0L) return false
        if (totalBytes > 0L && bytesDone >= totalBytes) return true
        if (bytesDone - lastLoggedBytes >= TRANSFER_LOG_BYTES) return true
        val now = System.currentTimeMillis()
        return now - lastLoggedAtMs >= TRANSFER_LOG_MS
    }

    private fun shouldEmitReceiverTelemetry(
        bytesDone: Long,
        lastTelemetryBytes: Long,
        nowMs: Long,
        lastTelemetryAtMs: Long
    ): Boolean {
        if (bytesDone <= 0L) return false
        if (bytesDone - lastTelemetryBytes >= RECEIVER_TELEMETRY_BYTES) return true
        return nowMs - lastTelemetryAtMs >= RECEIVER_TELEMETRY_MS
    }

    private fun formatMbps(bytes: Long, durationMs: Long): String {
        if (bytes <= 0L || durationMs <= 0L) return "0.00"
        val bitsPerSecond = (bytes.toDouble() * 8_000.0) / durationMs.toDouble()
        val mbps = bitsPerSecond / 1_000_000.0
        return String.format(Locale.US, "%.2f", mbps)
    }

    private fun isRetryableImportException(error: Throwable): Boolean {
        return when (error) {
            is InterruptedIOException -> true
            is SocketException -> true
            is SocketTimeoutException -> true
            is UnknownHostException -> true
            else -> false
        }
    }

    private fun describeImportRetryReason(error: Throwable): String {
        return when (error) {
            is SocketTimeoutException -> "No transfer progress for 30s."
            is UnknownHostException -> "Could not resolve sender host."
            is InterruptedIOException -> "Transfer interrupted."
            is SocketException -> "Connection dropped."
            else -> "Transfer stalled."
        }
    }
}

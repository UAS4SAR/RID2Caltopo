package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

data class MappingTransferSession(
    val missionId: String,
    val assignmentId: String,
    val title: String,
    val qrUrl: String,
    val browserUrl: String,
    val pairingCode: String,
    val certificateFingerprint: String,
    val expiresAt: Long,
    val status: String = "Ready for the controller browser",
    val photoCount: Int = 0,
    val storedBytes: Long = 0,
    val pendingCount: Int = 0,
    val active: Boolean = true
)

data class StoredMappingMission(val id: String, val createdAt: String, val photoCount: Int, val storedBytes: Long, val pendingCount: Int,val nativeHttpDiagnostic:Boolean=false)

/** Session credentials and TLS keys never enter exported mission files or app backup. */
object MappingTransferManager {
    private val state = MutableStateFlow<MappingTransferSession?>(null)
    val session = state.asStateFlow()
    private var server: MappingTransferServer? = null
    private var awake: PowerManager.WakeLock? = null
    private var generation: String? = null
    private var workToken: String? = null

    @Synchronized fun start(context: Context, mission: MappingMission, profile: MappingResolution.Profile, gsd: Double) {
        MappingFlightDeletion.requireAvailable()
        val app = context.applicationContext
        val host = lanAddress(app)
        val value = mission.json(profile, gsd)
        val directory = MappingVolume.directory(app,"mapping-missions",mission.id)
        val boundary = mission.boundaryKml()
        val record = File(directory, "mission.json")
        if (record.exists()) {
            val previous = JSONObject(record.readText())
            val inventory = MappingPhotoStore(directory).inventory()
            if (inventory.getInt("photoCount") + inventory.getInt("pendingCount") > 0) {
                require(previous.optString("cameraProfileId") == value.optString("cameraProfileId") &&
                    previous.getJSONObject("parameters").toString() == value.getJSONObject("parameters").toString()) {
                    "This flight already has originals. Reopen the assignment to define a separate flight with new settings."
                }
            }
        }
        MappingPhotoStore.writeJson(File(directory, "mission.json"), value)
        File(directory, "boundary.kml").writeText(boundary)
        open(app, host, value, boundary, directory)
    }

    @Synchronized fun resume(context: Context, id: String) {
        MappingFlightDeletion.requireAvailable()
        require(java.util.UUID.fromString(id).toString() == id) { "Invalid saved mission." }
        val directory = File(context.filesDir, "mapping-missions/$id")
        val value = JSONObject(File(directory, "mission.json").readText())
        require(value.getString("missionId") == id) { "Saved mission identity does not match." }
        open(context.applicationContext, lanAddress(context), value, File(directory, "boundary.kml").readText(), directory)
    }

    private fun open(app: Context, host: String, value: JSONObject, boundary: String, directory: File) {
        val id = value.getString("missionId")
        val certificate = certificate(app, host)
        val nextGeneration = java.util.UUID.randomUUID().toString()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 255) }
        val next = MappingTransferServer(host, value, boundary, directory, tlsFactory(certificate),
            assets = { app.assets.open("mapping-transfer/$it").use { input -> input.readBytes() } },
            changed = { status, inventory ->
                synchronized(this) {
                    if (generation == nextGeneration) {
                        state.value = state.value?.copy(status = status, photoCount = inventory.getInt("photoCount"),
                            storedBytes = inventory.getLong("storedBytes"), pendingCount = inventory.getInt("pendingCount"),
                            active = !status.startsWith("Transfer expired"))
                        if (state.value?.active == false) { releaseAwake();releaseWork() }
                    }
                }
            })
        stop()
        generation = nextGeneration
        server = next
        val inventory = next.store.inventory()
        state.value = MappingTransferSession(id, value.getString("assignmentId"), value.getString("title"), next.qrUrl, next.baseUrl, next.pairingCode,
            fingerprint, next.expiresAt, photoCount = inventory.getInt("photoCount"), storedBytes = inventory.getLong("storedBytes"),
            pendingCount = inventory.getInt("pendingCount"))
        workToken=MappingWorkRuntime.begin(::stop)
        try {
            awake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "R2C:MappingTransfer").apply {
                setReferenceCounted(false); acquire((next.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
            }
            next.start()
        }catch(error:Exception){stop();throw error}
    }

    internal fun requireIdleForDeletion() {
        check(server == null) { "Close the photo transfer connection before deleting saved flights or results." }
    }
    @Synchronized fun stop() {
        generation = null
        server?.close(); server = null; releaseAwake();releaseWork()
        state.value = state.value?.copy(active = false, status = "Transfer closed; originals retained on this tablet")
    }

    private fun releaseAwake() { awake?.let { if (it.isHeld) it.release() }; awake = null }
    private fun releaseWork() { val token=workToken;workToken=null;token?.let(MappingWorkRuntime::finish) }

    internal fun lanAddress(context: Context): String {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = connectivity.allNetworks.filter { network ->
            connectivity.getNetworkCapabilities(network)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true
        }.flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty().map { address -> address.address } }
        val hotspot = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter {
            it.isUp && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan"))
        }.flatMap { it.inetAddresses.toList() }
        return (candidates + hotspot).filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress
            ?: throw IllegalArgumentException("Connect the tablet and controller to the same Wi-Fi or tablet hotspot first.")
    }

    private fun certificate(context: Context, host: String): HeldCertificate {
        val file = File(context.noBackupFilesDir, "mapping-transfer-tls.json")
        if (file.exists()) runCatching {
            val value = JSONObject(file.readText())
            if (value.getString("host") == host) {
                val held = HeldCertificate.decode(value.getString("pem")); held.certificate.checkValidity()
                return held
            }
        }
        return HeldCertificate.Builder().commonName("R2C local mapping").addSubjectAlternativeName(host)
            .duration(7, TimeUnit.DAYS).build().also {
                MappingPhotoStore.writeJson(file, JSONObject().put("host", host).put("pem", it.privateKeyPkcs8Pem() + it.certificatePem()))
            }
    }

    private fun tlsFactory(held: HeldCertificate): SSLServerSocketFactory {
        val password = "local-session".toCharArray()
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setKeyEntry("mapping", held.keyPair.private, password, arrayOf(held.certificate))
        }
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        return SSLContext.getInstance("TLS").apply { init(manager.keyManagers, null, null) }.serverSocketFactory
    }

    fun storedMissions(context: Context, assignmentId: String, incidentId: String): List<StoredMappingMission> =
        File(context.filesDir, "mapping-missions").listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { directory ->
            runCatching {
                val record = JSONObject(File(directory, "mission.json").readText())
                if (record.getString("assignmentId") != assignmentId || record.getString("incidentId") != incidentId) return@runCatching null
                val inventory = MappingPhotoStore(directory).inventory()
                StoredMappingMission(record.getString("missionId"), record.getString("createdAt"), inventory.getInt("photoCount"),
                    inventory.getLong("storedBytes"), inventory.getInt("pendingCount"),DjiCloudManager.savedHttpDiagnostic(directory))
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
}

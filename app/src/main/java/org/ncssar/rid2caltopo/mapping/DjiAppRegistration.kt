package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.ncssar.rid2caltopo.data.OrgConfigToken
import java.security.MessageDigest

/** App-wide DJI registration uses the existing FAA configuration transport.
 * This is client-side obfuscation; installation storage uses Android Keystore. */
object DjiAppRegistration {
    fun decode(bytes: ByteArray): DjiCloudCredentials {
        require(bytes.size <= 32768) { "The app's DJI registration is invalid." }
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.keys().asSequence().toSet() == setOf("type", "version", "encoding", "enc", "sha256") &&
            value.getString("type") == "r2c_dji_app_registration" && value.get("version") == 1 &&
            value.getString("encoding") == "r2c-config") { "The app's DJI registration is invalid." }
        val clear = OrgConfigToken.decryptPayload(value.getString("enc")).toByteArray(Charsets.UTF_8)
        try {
            require(hash(clear) == value.getString("sha256")) { "The app's DJI registration is invalid." }
            val fields = JSONObject(clear.toString(Charsets.UTF_8))
            require(fields.keys().asSequence().toSet() == setOf("appId", "appKey", "appLicense") &&
                listOf("appId", "appKey", "appLicense").all { fields.get(it) is String }) { "The app's DJI registration is invalid." }
            return DjiCloudCredentials(fields.getString("appId"), fields.getString("appKey"), fields.getString("appLicense"))
        } finally { clear.fill(0) }
    }
    interface Store {
        fun receipt(): String?
        fun hasCredentials(): Boolean
        fun save(credentials: DjiCloudCredentials)
        fun record(hash: String)
    }
    fun install(bytes: ByteArray, store: Store) {
        val credentials = decode(bytes); val hash = hash(bytes)
        if (store.receipt() != hash || !store.hasCredentials()) {
            store.save(credentials)
            store.record(hash) // Never acknowledge a failed secure-store write.
        }
    }
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}

package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DjiCloudCredentials(val appId: String, val appKey: String, val appLicense: String) {
    init {
        require(appId.matches(Regex("[0-9]{1,12}")) && appKey.length in 16..256 && appLicense.length in 32..8192) {
            "Enter the Cloud API app ID, app key and basic license from DJI Developer."
        }
    }
    fun bootstrap() = JSONObject().put("appId", appId).put("appKey", appKey).put("appLicense", appLicense)
    override fun toString() = "DjiCloudCredentials([protected])"
}

/** Device-bound encryption; no plaintext file, app backup, mission export, or diagnostic logging. */
object DjiCloudCredentialStore {
    private const val ALIAS = "r2c.mapping.dji-cloud"
    private fun file(context: Context) = File(context.noBackupFilesDir, "dji-cloud-credentials.enc")
    fun configured(context: Context):Boolean {
        runCatching{DjiCloudProvisioner.provision(context)}
        return file(context).isFile
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUnlockedDeviceRequired(true).build())
        }.generateKey()
    }
    fun save(context: Context, credentials: DjiCloudCredentials) {
        saveProtected(context,"dji-cloud-credentials.enc",credentials.bootstrap().toString().toByteArray())
    }
    internal fun saveProtected(context:Context,name:String,value:ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(name.toByteArray());val bytes = cipher.doFinal(value)
        File(context.noBackupFilesDir, "$name.tmp").also { temporary ->
            java.io.FileOutputStream(temporary).use { it.write(cipher.iv.size); it.write(cipher.iv); it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(File(context.noBackupFilesDir,name))) { "Could not save protected mapping settings." }
        }
    }
    fun load(context: Context): DjiCloudCredentials {
        require(configured(context)) { "Set up the DJI Cloud API credentials on this tablet first." }
        val value = JSONObject(loadProtected(context,"dji-cloud-credentials.enc").toString(Charsets.UTF_8))
        return DjiCloudCredentials(value.getString("appId"), value.getString("appKey"), value.getString("appLicense"))
    }
    internal fun loadProtected(context:Context,name:String):ByteArray {
        val bytes = File(context.noBackupFilesDir,name).readBytes(); val size = bytes[0].toInt() and 255
        require(size == 12 && bytes.size > 29) { "Protected DJI credentials need to be entered again." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, size + 1))) }
        cipher.updateAAD(name.toByteArray());return cipher.doFinal(bytes.copyOfRange(size + 1, bytes.size))
    }
}

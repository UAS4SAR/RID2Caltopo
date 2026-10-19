package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** A deployment APK carries ciphertext addressed to this tablet's non-exportable key. */
object DjiCloudProvisioner {
    private const val ALIAS="r2c.mapping.dji-cloud-provisioning"
    private const val ASSET="dji-provisioning/sealed.json"
    @Synchronized fun enroll(context:Context) { enrollment(context) }
    private fun enrollment(context:Context):KeyStore {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        if(!store.containsAlias(ALIAS)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply{
            initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).setUnlockedDeviceRequired(true).build())
        }.generateKeyPair()
        val publicKey=store.getCertificate(ALIAS).publicKey.encoded
        val enrollment=File(context.noBackupFilesDir,"dji-cloud-enrollment.pem")
        if(!enrollment.exists()) enrollment.writeText("-----BEGIN PUBLIC KEY-----\n"+
            Base64.encodeToString(publicKey,Base64.NO_WRAP).chunked(64).joinToString("\n")+"\n-----END PUBLIC KEY-----\n")
        return store
    }
    @Synchronized fun provision(context:Context):Boolean {
        val store=enrollment(context)
        val publicKey=store.getCertificate(ALIAS).publicKey.encoded
        val encoded=try{context.assets.open(ASSET).use{input->
            val buffer=java.io.ByteArrayOutputStream();val block=ByteArray(8192)
            while(true){val count=input.read(block);if(count<0)break;require(buffer.size()+count<=32*1024){"Protected deployment bundle is too large."};buffer.write(block,0,count)}
            buffer.toString("UTF-8")
        }}catch(_:java.io.FileNotFoundException){return false}
        val accepted=File(context.noBackupFilesDir,"dji-cloud-provisioned.sha256")
        val bundleHash=LocalS3.hex(MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()))
        if(accepted.isFile && accepted.readText()==bundleHash && File(context.noBackupFilesDir,"dji-cloud-credentials.enc").isFile)return true
        val value=JSONObject(encoded)
        require(value.getString("type")=="r2c_dji_device_provisioning" && value.getInt("version")==1){"Unsupported protected deployment bundle."}
        val recipient=LocalS3.hex(MessageDigest.getInstance("SHA-256").digest(publicKey))
        if(value.getString("recipientSha256")!=recipient)return false // A team build may be addressed to a different tablet.
        fun bytes(name:String)=Base64.decode(value.getString(name),Base64.NO_WRAP)
        val unwrap=Cipher.getInstance("RSA/ECB/OAEPPadding").apply{
            init(Cipher.DECRYPT_MODE,store.getKey(ALIAS,null) as PrivateKey,
                OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT))
        }
        val key=unwrap.doFinal(bytes("wrappedKey"));require(key.size==32)
        val clear=try{
            Cipher.getInstance("AES/GCM/NoPadding").apply{
                init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,bytes("iv")))
                updateAAD(recipient.toByteArray())
            }.doFinal(bytes("ciphertext"))
        }finally{key.fill(0)}
        try{
            val credentials=JSONObject(clear.toString(Charsets.UTF_8))
            credentials.optJSONObject("tls")?.let{tls->DjiCloudTlsStore.import(context,tls.getString("host"),tls.getString("pem"))}
            DjiCloudCredentialStore.save(context,DjiCloudCredentials(credentials.getString("appId"),credentials.getString("appKey"),credentials.getString("appLicense")))
            accepted.writeText(bundleHash)
        }finally{clear.fill(0)}
        return true
    }
}

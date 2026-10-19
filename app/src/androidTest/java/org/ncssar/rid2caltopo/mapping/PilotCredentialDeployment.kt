package org.ncssar.rid2caltopo.mapping

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/** Explicit administrator deployment operation, skipped by ordinary test runs.
 * Existing credentials are decrypted only inside the target app's UID and sealed
 * for the pinned recipient. No plaintext credential leaves the Android tablet. */
@RunWith(AndroidJUnit4::class)
class PilotCredentialDeployment {
    @Test fun sealForEnrolledTablet() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("provisionPilotCredentialsToEnrolledDevice")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val expected=arguments.getString("recipientSha256") ?: error("Recipient fingerprint required")
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val directory=File(context.noBackupFilesDir,"pilot-deployment")
        val pem=File(directory,"recipient.pem").readText()
        val encoded=Base64.decode(pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replace(Regex("\\s"),""),Base64.DEFAULT)
        val recipient=MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(""){"%02x".format(it.toInt() and 255)}
        require(recipient==expected){"Recipient enrollment mismatch"}
        val publicKey=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
        require((publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength()==2048)
        val clear=DjiCloudCredentialStore.load(context).bootstrap().toString().toByteArray()
        val random=SecureRandom();val key=ByteArray(32).also{random.nextBytes(it)};val iv=ByteArray(12).also{random.nextBytes(it)}
        try {
            val encrypt=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));updateAAD(recipient.toByteArray())}
            val ciphertext=encrypt.doFinal(clear)
            val wrap=Cipher.getInstance("RSA/ECB/OAEPPadding").apply{init(Cipher.ENCRYPT_MODE,publicKey,OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT))}
            fun b64(bytes:ByteArray)=Base64.encodeToString(bytes,Base64.NO_WRAP)
            val envelope=JSONObject().put("type","r2c_dji_device_provisioning").put("version",2).put("algorithm","RSA-OAEP-SHA256+A256GCM")
                .put("recipientSha256",recipient).put("wrappedKey",b64(wrap.doFinal(key))).put("iv",b64(iv)).put("ciphertext",b64(ciphertext))
            File(directory,"sealed.json").writeText(envelope.toString())
        } finally {clear.fill(0);key.fill(0)}
    }
}

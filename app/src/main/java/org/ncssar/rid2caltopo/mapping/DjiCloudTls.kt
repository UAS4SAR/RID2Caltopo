package org.ncssar.rid2caltopo.mapping

import android.content.Context
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

class DjiCloudTls(val host:String,val fingerprint:String,val imported:Boolean,val factory:SSLServerSocketFactory)

object DjiCloudTlsStore {
    private const val NAME="dji-cloud-endpoint.enc"
    fun importedHost(context:Context):String = runCatching {
        val value=JSONObject(DjiCloudCredentialStore.loadProtected(context,NAME).toString(Charsets.UTF_8))
        if(value.optBoolean("imported"))value.getString("host") else ""
    }.getOrDefault("")
    fun import(context:Context,host:String,pem:String) {
        require(host.matches(Regex("[A-Za-z0-9.-]{1,253}")) && !host.contains("..")) { "Enter the certificate's hostname without a scheme or port." }
        decode(host,pem,true) // validate key, certificate, SAN and expiry before retaining secrets
        DjiCloudCredentialStore.saveProtected(context,NAME,JSONObject().put("host",host).put("pem",pem).put("imported",true).toString().toByteArray())
    }
    fun load(context:Context,lanHost:String):DjiCloudTls {
        if(File(context.noBackupFilesDir,NAME).exists()) {
            val value=JSONObject(DjiCloudCredentialStore.loadProtected(context,NAME).toString(Charsets.UTF_8))
            if(value.optBoolean("imported")) return decode(value.getString("host"),value.getString("pem"),true)
            if(value.optString("host")==lanHost) runCatching{decode(lanHost,value.getString("pem"),false)}.getOrNull()?.let{return it}
        }
        val certificate=HeldCertificate.Builder().commonName("R2C Pilot 2 local platform").addSubjectAlternativeName(lanHost).duration(7,TimeUnit.DAYS).build()
        val pem=certificate.privateKeyPkcs8Pem()+certificate.certificatePem()
        DjiCloudCredentialStore.saveProtected(context,NAME,JSONObject().put("host",lanHost).put("pem",pem).put("imported",false).toString().toByteArray())
        return decode(lanHost,pem,false)
    }
    private fun decode(host:String,pem:String,imported:Boolean):DjiCloudTls {
        require(pem.length<=128*1024) { "Certificate bundle is too large." }
        val certificates=Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----").findAll(pem).map{it.value}.toList()
        val privateKey=Regex("-----BEGIN PRIVATE KEY-----[\\s\\S]+?-----END PRIVATE KEY-----").find(pem)?.value
            ?: throw IllegalArgumentException("Import a PEM certificate chain with its unencrypted PKCS8 private key. It will be encrypted on this tablet.")
        require(certificates.isNotEmpty()) { "The bundle needs the server certificate and its chain." }
        val held=HeldCertificate.decode(privateKey+"\n"+certificates.first())
        val chain=certificates.map { CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(it.toByteArray())) as X509Certificate }
        chain.forEach{it.checkValidity()}
        val names=chain.first().subjectAlternativeNames.orEmpty().filter{it[0] in listOf(2,7)}.map{it[1].toString().lowercase()}
        val normalized=host.lowercase()
        require(names.any{ it==normalized || it.startsWith("*.") && normalized.endsWith(it.drop(1)) && normalized.count{c->c=='.'}==it.count{c->c=='.'} }) { "Certificate does not cover the configured hostname/IP address." }
        // Verify the key belongs to the leaf certificate without sending anything to the network.
        val signature=java.security.Signature.getInstance(if(held.keyPair.private.algorithm=="RSA")"SHA256withRSA" else "SHA256withECDSA")
        signature.initSign(held.keyPair.private); signature.update("R2C".toByteArray());val proof=signature.sign()
        signature.initVerify(chain.first().publicKey);signature.update("R2C".toByteArray());require(signature.verify(proof)){"Certificate and private key do not match."}
        val password="local-platform".toCharArray()
        val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply{load(null);setKeyEntry("server",held.keyPair.private,password,chain.toTypedArray())}
        val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(store,password)}
        val factory=SSLContext.getInstance("TLS").apply{init(keys.keyManagers,null,null)}.serverSocketFactory
        val fingerprint=LocalS3.hex(MessageDigest.getInstance("SHA-256").digest(chain.first().encoded)).uppercase().chunked(2).joinToString(":")
        return DjiCloudTls(host,fingerprint,imported,factory)
    }
}

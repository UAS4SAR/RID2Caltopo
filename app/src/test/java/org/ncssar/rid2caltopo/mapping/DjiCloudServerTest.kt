package org.ncssar.rid2caltopo.mapping

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class DjiCloudServerTest {
    private data class Environment(val root:File,val certificate:HeldCertificate,val server:DjiCloudServer):AutoCloseable {
        override fun close(){server.close();root.deleteRecursively()}
    }
    private fun environment(mission:JSONObject=DjiCloudTestFixture.mission(true),tlsEnabled:Boolean=true,assets:(String)->ByteArray={"page".toByteArray()},changed:(JSONObject)->Unit = {}):Environment {
        val root=Files.createTempDirectory("r2c-native-pilot").toFile()
        val held=HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val password="test".toCharArray()
        val keys=KeyStore.getInstance(KeyStore.getDefaultType()).apply{load(null);setKeyEntry("test",held.keyPair.private,password,arrayOf(held.certificate))}
        val manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(keys,password)}
        val tls=SSLContext.getInstance("TLS").apply{init(manager.keyManagers,null,null)}
        val server=DjiCloudServer("127.0.0.1","127.0.0.1",if(tlsEnabled)tls.serverSocketFactory else javax.net.ServerSocketFactory.getDefault(),mission,DjiCloudTestFixture.credentials(),root,assets,changed=changed,port=0)
        server.start();return Environment(root,held,server)
    }
    private fun api(env:Environment,path:String,token:String="",value:JSONObject?=null,pageRevision:String?="2"):Pair<Int,JSONObject> {
        val connection=URL(env.server.baseUrl+path).openConnection() as java.net.HttpURLConnection
        if(connection is HttpsURLConnection)connection.sslSocketFactory=HandshakeCertificates.Builder().addTrustedCertificate(env.certificate.certificate).build().sslSocketFactory()
        connection.connectTimeout=3000;connection.readTimeout=10000
        pageRevision?.let{connection.setRequestProperty("X-R2C-Page-Revision",it)}
        if(token.isNotEmpty())connection.setRequestProperty("X-Auth-Token",token)
        if(value!=null){connection.requestMethod="POST";connection.setRequestProperty("Content-Type","application/json");connection.doOutput=true;connection.outputStream.use{it.write(value.toString().toByteArray())}}
        return try{val code=connection.responseCode;code to JSONObject((if(code<400)connection.inputStream else connection.errorStream).bufferedReader().use{it.readText()})}finally{connection.disconnect()}
    }
    private fun login(env:Environment)=api(env,"/cloud/login",value=JSONObject().put("code",env.server.code)).second.getJSONObject("data").getString("token")

    @Test fun retainedControllerPageMustReopenBeforeBootstrapOrReadinessReports() {
        environment(tlsEnabled=false).use { env ->
            val token=login(env)
            for(revision in listOf(null,"1","3")) {
                val old=api(env,"/cloud/bootstrap",token,pageRevision=revision)
                assertEquals(409,old.first)
                assertTrue(old.second.getString("message").contains("Close the Open Platforms page"))
                val report=api(env,"/cloud/report",token,JSONObject().put("automaticOriginals",true).put("downloadOwner",true),pageRevision=revision)
                assertEquals(409,report.first)
            }
            assertFalse(api(env,"/cloud/status",token).second.getJSONObject("data").getJSONObject("flags").optBoolean("downloadOwner"))
            assertEquals(200,api(env,"/cloud/bootstrap",token).first)
        }
    }

    @Test fun missionLibraryAdvertisesAndFiltersTheSelectedAircraft() {
        val feature=DjiCloudTestFixture.mission().getJSONObject("feature")
        for((cameraId,modelKey,payloadKey) in listOf(
            Triple("m4t.visible.wide","0-99-1","1-89-0"),
            Triple("m30.visible.wide","0-67-0","1-52-0"),
            Triple("m30t.visible.wide","0-67-1","1-53-0"))) {
            val camera=MappingResolution.profiles.first{it.id==cameraId}
            val mission=MappingMission(feature,"MAP").json(camera,3.0,MappingCaptureProfile.brushTrees,20.0)
            environment(mission=mission,tlsEnabled=false).use { env ->
                val token=login(env)
                env.server.platform.mqtt("sys/product/RC/status",JSONObject().put("method","update_topo")
                    .put("data",JSONObject().put("sub_devices",org.json.JSONArray().put(JSONObject().put("sn","SYNTHETIC")))).toString().toByteArray()) {}
                val topology=api(env,"/manage/api/v1/workspaces/${env.server.platform.workspaceId}/devices/topologies",token).second.getJSONObject("data").getJSONArray("list").getJSONObject(0)
                assertEquals(modelKey,topology.getJSONArray("hosts").getJSONObject(0).getJSONObject("device_model").getString("key"))
                assertEquals(if(cameraId.startsWith("m30"))"2-119-0" else "2-174-0",topology.getJSONArray("parents").getJSONObject(0).getJSONObject("device_model").getString("key"))
                val path="/wayline/api/v1/workspaces/${env.server.platform.workspaceId}/waylines"
                val list=api(env,path+"?drone_model_keys=$modelKey",token).second.getJSONObject("data").getJSONArray("list")
                assertEquals(1,list.length())
                assertEquals(modelKey,list.getJSONObject(0).getString("drone_model_key"))
                assertEquals(payloadKey,list.getJSONObject(0).getJSONArray("payload_model_keys").getString(0))
                assertEquals(0,api(env,path+"?drone_model_keys=0-100-1",token).second.getJSONObject("data").getJSONArray("list").length())
            }
        }
    }

    @Test fun deletionWaitsForHandlerEvenAfterSocketCloseAndCanRetryAfterTimeout() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        environment(tlsEnabled=false,assets={
            entered.countDown()
            var done=false
            while(!done)try{release.await();done=true}catch(_:InterruptedException){}
            "page".toByteArray()
        }).use { env ->
            val original=File(env.root,"original-sentinel.jpg").apply{writeText("retain until drained")}
            val client=Thread { runCatching { URL(env.server.baseUrl+"/cloud").openStream().use{it.readBytes()} } }.apply{start()}
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS))
                assertThrows(IllegalStateException::class.java){env.server.closeAndWait(20)}
                assertTrue(original.exists())
                // Closing again must not hide the still-running callback on a deletion retry.
                assertThrows(IllegalStateException::class.java){env.server.closeAndWait(20)}
                release.countDown()
                env.server.closeAndWait(3000)
                assertTrue(original.delete())
            } finally {release.countDown();client.join(3000)}
        }
    }

    @Test fun httpDiagnosticUsesDocumentedWebsocketMqttTransport() {
        environment(tlsEnabled=false).use{env->
            val page=URL(env.server.baseUrl+"/cloud").openConnection().apply{setRequestProperty("User-Agent","Android native-page test")}
            assertEquals("page",page.inputStream.bufferedReader().use{it.readText()})
            assertTrue(env.server.platform.snapshot().getJSONObject("flags").getBoolean("androidPageReached"))
            val token=login(env);val bootstrap=api(env,"/cloud/bootstrap",token).second.getJSONObject("data")
            assertTrue(bootstrap.getJSONObject("api").getString("host").startsWith("http://"))
            assertTrue(bootstrap.getJSONObject("ws").getString("host").startsWith("ws://"))
            val config=bootstrap.getJSONObject("thing");assertTrue(config.getString("host").startsWith("ws://"))
            val reply=CountDownLatch(1)
            val client=MqttClient(config.getString("host"),"R2C-http-diagnostic",MemoryPersistence())
            client.setCallback(object:MqttCallback{
                override fun connectionLost(cause:Throwable?){}
                override fun deliveryComplete(token:IMqttDeliveryToken?){}
                override fun messageArrived(topic:String?,message:MqttMessage?){if(topic=="sys/product/RC_HTTP/status_reply")reply.countDown()}
            })
            try{
                client.connect(MqttConnectOptions().apply{userName=config.getString("username");password=config.getString("password").toCharArray();connectionTimeout=3})
                client.subscribe("sys/product/RC_HTTP/status_reply",1)
                client.publish("thing/product/RC_HTTP/status",JSONObject().put("method","update_topo").put("data",JSONObject().put("type",174)).toString().toByteArray(),1,false)
                assertTrue(reply.await(3,TimeUnit.SECONDS))
                val list=api(env,"/wayline/api/v1/workspaces/${env.server.platform.workspaceId}/waylines",token).second.getJSONObject("data").getJSONArray("list")
                assertEquals(1,list.length())
            }finally{if(client.isConnected)client.disconnect();client.close()}
        }
    }

    @Test fun certificateRejectionIsVisibleWithoutLoggingCredentialValues() {
        val rejected=CountDownLatch(1)
        environment{snapshot->if(snapshot.optJSONObject("flags")?.optBoolean("httpsRejected")==true)rejected.countDown()}.use{env->
            val connection=URL(env.server.baseUrl+"/cloud").openConnection() as HttpsURLConnection
            connection.connectTimeout=3000;connection.readTimeout=3000
            try{connection.inputStream.close();fail("Untrusted certificate was accepted")}
            catch(_:SSLHandshakeException){}
            finally{connection.disconnect()}
            assertTrue("Certificate rejection feedback",rejected.await(3,TimeUnit.SECONDS))
            assertTrue(env.server.platform.snapshot().getString("status").contains("trusted tablet certificate"))
            assertFalse(env.server.platform.snapshot().toString().contains("synthetic-test-app-key"))
        }
    }

    @Test fun nativeLicenseBootstrapRequiresPairingAndReportsCannotPersistSecrets() {
        environment().use{env->
            assertEquals(401,api(env,"/cloud/bootstrap").first)
            assertEquals(401,api(env,"/cloud/login",value=JSONObject().put("code","wrong")).first)
            val token=login(env);val config=api(env,"/cloud/bootstrap",token).second.getJSONObject("data")
            assertEquals("123456",config.getJSONObject("license").getString("appId"))
            assertEquals(200,api(env,"/cloud/report",token,JSONObject().put("licenseVerified",true)).first)
            assertEquals(400,api(env,"/cloud/report",token,JSONObject().put("appKey","do-not-store")).first)
            api(env,"/cloud/report",token,JSONObject().put("missionLoaded",true).put("mediaLoaded",true).put("automaticOriginals",true).put("downloadOwner",true))
            assertTrue(env.server.platform.snapshot().getString("status").contains("return ready"))
            api(env,"/cloud/report",token,JSONObject().put("automaticOriginals",false).put("downloadOwner",false))
            assertTrue(env.server.platform.snapshot().getString("status").contains("needs attention"))
            assertFalse(api(env,"/cloud/status",token).second.toString().contains("synthetic-test-app-key"))
            assertEquals(404,api(env,"/wayline/api/v1/workspaces/wrong/waylines",token).first)
        }
    }

    @Test fun independentPahoClientConnectsOverSecureWebsocketAndGetsTopologyReply() {
        environment().use{env->
            val token=login(env);val config=api(env,"/cloud/bootstrap",token).second.getJSONObject("data").getJSONObject("thing")
            val received=CountDownLatch(1)
            val client=MqttClient(config.getString("host"),"R2C-native-test",MemoryPersistence())
            client.setCallback(object:MqttCallback{
                override fun connectionLost(cause:Throwable?){}
                override fun deliveryComplete(token:IMqttDeliveryToken?){}
                override fun messageArrived(topic:String?,message:MqttMessage?){
                    val value=JSONObject(message!!.payload.toString(Charsets.UTF_8))
                    if(topic=="sys/product/RC_TEST/status_reply" && value.getJSONObject("data").getInt("result")==0)received.countDown()
                }
            })
            val options=MqttConnectOptions().apply{
                userName=config.getString("username");password=config.getString("password").toCharArray();connectionTimeout=5;keepAliveInterval=20
                socketFactory=HandshakeCertificates.Builder().addTrustedCertificate(env.certificate.certificate).build().sslSocketFactory()
                isHttpsHostnameVerificationEnabled=true
            }
            try{
                client.connect(options);client.subscribe("sys/product/RC_TEST/status_reply",1)
                val topology=JSONObject().put("method","update_topo").put("tid","tid-1").put("bid","bid-1").put("data",JSONObject().put("type",174).put("sub_type",0)
                    .put("device_secret","never-retain").put("sub_devices",org.json.JSONArray().put(JSONObject().put("sn","AIRCRAFT_TEST").put("type",100).put("sub_type",1))))
                client.publish("sys/product/RC_TEST/status",topology.toString().toByteArray(),1,false)
                assertTrue("Topology reply",received.await(5,TimeUnit.SECONDS))
                val inventory=api(env,"/manage/api/v1/workspaces/${env.server.platform.workspaceId}/devices/topologies",token).second
                assertTrue(inventory.toString().contains("AIRCRAFT_TEST"));assertFalse(inventory.toString().contains("never-retain"))
            }finally{if(client.isConnected)client.disconnect();client.close()}
        }
    }

    @Test fun officialS3SdkExercisesBytePreservingSingleAndMultipartReturnFlow() {
        val python=System.getenv("R2C_CLOUD_TEST_PYTHON")
        assumeTrue("Set R2C_CLOUD_TEST_PYTHON to an environment with boto3 for the optional independent SDK integration test.",!python.isNullOrBlank() && File(python).isFile)
        environment().use{env->
            val certificate=File(env.root,"test-ca.pem").apply{writeText(env.certificate.certificatePem())}
            val script=File("../tools/mapping/tests/test_native_cloud_s3.py").canonicalFile
            val output=File(env.root,"sdk-test.log")
            val process=ProcessBuilder(python,script.path,env.server.baseUrl,env.server.code,certificate.path).redirectErrorStream(true).redirectOutput(output).start()
            assertTrue("SDK test timeout",process.waitFor(45,TimeUnit.SECONDS))
            assertEquals(output.readText(),0,process.exitValue())
            val group=JSONObject(File(env.root,"cloud/group-upload.json").readText());assertTrue(group.getBoolean("ready"))
            assertEquals(2,env.server.platform.photos.inventory().getInt("photoCount"))
            val receipt=File(env.root,"cloud/receipts").listFiles()!!.map{JSONObject(it.readText())}.first{it.optBoolean("assigned")}
            assertTrue(File(env.root,"originals/${receipt.getString("sha256")}.jpg").delete())
            val token=login(env)
            assertEquals(1,api(env,"/media/api/v1/workspaces/${env.server.platform.workspaceId}/fast-upload",token,JSONObject().put("fingerprint",receipt.getString("fingerprint"))).second.getInt("code"))
            assertEquals(1,env.server.platform.photos.inventory().getInt("photoCount"))
        }
    }
}

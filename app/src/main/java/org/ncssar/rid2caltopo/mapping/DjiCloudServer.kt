package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
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
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLServerSocket

/** All native Pilot 2 services are hosted here on the mapping device. */
class DjiCloudServer(bindHost:String, publicHost:String, factory:ServerSocketFactory,
    mission:JSONObject, credentials:DjiCloudCredentials, directory:File,
    private val assets:(String)->ByteArray, private val changed:(JSONObject)->Unit = {},
    port:Int=8443, val expiresAt:Long=System.currentTimeMillis()+2*60*60*1000,
    private val now:()->Long=System::currentTimeMillis) : AutoCloseable {
    private val plannedRoute=DjiMappingRoute(mission)
    private val listener = factory.createServerSocket().apply { reuseAddress=true; bind(InetSocketAddress(bindHost,port),16); soTimeout=1000 }
    val baseUrl="${if(listener is SSLServerSocket) "https" else "http"}://$publicHost:${listener.localPort}"
    val code=String.format(java.util.Locale.US,"%06d",SecureRandom().nextInt(1000000))
    private val token=random()
    private val lifecycle=Any()
    private val sockets=ConcurrentHashMap.newKeySet<Socket>()
    private val websocketClients=ConcurrentHashMap.newKeySet<CloudWebSocket>()
    private val workers=ThreadPoolExecutor(12,12,30,TimeUnit.SECONDS,ArrayBlockingQueue(16),{task->Thread(task,"R2C-Pilot2-client").apply{isDaemon=true}})
    private val keepalive=java.util.Timer("R2C-Pilot2-WebSocket-ping",true)
    private var failures=0; private var lockedUntil=0L
    @Volatile private var closed=false
    val platform: DjiCloudPlatform
    private val mqtt: DjiLocalMqtt
    init {
        val route=plannedRoute
        val s3=LocalS3(File(directory,"cloud/storage").apply{mkdirs()},mission.getString("missionId"),expiresAt,now)
        platform=DjiCloudPlatform(mission,route,directory,credentials,s3) { if(!closed) changed(platformSnapshot()) }
        platform.baseUrl=baseUrl; platform.token=token; platform.expiresAt=expiresAt
        platform.mqttUsername="R2C-"+mission.getString("missionId").take(8); platform.mqttPassword=random()
        mqtt=DjiLocalMqtt(platform.mqttUsername,platform.mqttPassword,{!closed && now()<expiresAt},
            {topic,bytes->platform.mqtt(topic,bytes) { value -> websocketClients.forEach { client -> runCatching{client.send(value.toString().toByteArray())} } }},
            { value->platform.flag("mqttConnected",value) })
    }
    private fun platformSnapshot() = platform.snapshot()
    fun start() {
        // Mission-library clients may otherwise stay silent for the whole flight.
        keepalive.scheduleAtFixedRate(object:java.util.TimerTask(){override fun run(){
            if(!closed) websocketClients.forEach { client -> runCatching{client.send(ByteArray(0),9)} }
        }},30000,30000)
        Thread({
            while(!closed) {
                if(now()>=expiresAt){close();changed(platform.snapshot().put("status","Native connection expired; originals retained"));break}
                try {
                    val socket=listener.accept().apply{soTimeout=30000}
                    synchronized(lifecycle) {
                        if(closed) socket.close() else {
                            sockets.add(socket)
                            try { workers.execute { try{socket.use{serve(it)}}finally{sockets.remove(socket)} } }
                            catch(_:java.util.concurrent.RejectedExecutionException){sockets.remove(socket);socket.close()}
                        }
                    }
                }catch(_:SocketTimeoutException){}catch(_:SocketException){if(!closed)close()}
            }
        },"R2C-Pilot2-server").apply{isDaemon=true;start()}
    }
    private fun respond(socket:Socket,request:CloudHttpRequest?,response:CloudHttpResponse) {
        if(request!=null)platform.recordResponse(request,response)
        CloudHttp.respond(socket,response)
    }
    private fun serve(socket:Socket) {
        var pendingRequest:CloudHttpRequest?=null
        try {
            val request=CloudHttp.request(socket) ?: return
            pendingRequest=request
            if(request.headers["host"]!=baseUrl.substringAfter("://") || request.headers["origin"]?.let{it!=baseUrl}==true || request.headers["sec-fetch-site"]=="cross-site") {
                respond(socket,request,DjiCloudPlatform.failure(403,"Use the R2C tablet's local platform address."));return
            }
            if(closed || now()>=expiresAt){respond(socket,request,DjiCloudPlatform.failure(410,"Connection expired. Reopen this mission on the R2C tablet."));return}
            if(request.path in listOf("/cloud/ws","/cloud/mqtt")) {
                require(request.headers["upgrade"]?.lowercase()=="websocket") { "WebSocket upgrade required." }
                if(!authenticated(request)){respond(socket,request,DjiCloudPlatform.failure(401,"Pair with the R2C tablet first."));return}
                val channel=CloudWebSocket(socket,request.input)
                if(request.path=="/cloud/mqtt") {
                    require(request.headers["sec-websocket-protocol"].orEmpty().split(',').map{it.trim()}.contains("mqtt")) { "MQTT WebSocket subprotocol required." }
                    channel.upgrade(request,"mqtt"); socket.soTimeout=180000
                    mqtt.serve(channel.mqttInput()){channel.send(it,2)}
                } else {
                    channel.upgrade(request); socket.soTimeout=180000; websocketClients.add(channel);platform.flag("wsConnected",true)
                    try { while(!closed && now()<expiresAt && channel.receive()!=null){} }
                    finally {websocketClients.remove(channel);platform.flag("wsConnected",websocketClients.isNotEmpty())}
                }
                return
            }
            if(request.headers["expect"]?.lowercase()=="100-continue") { socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray());socket.getOutputStream().flush() }
            val response=when {
                request.method=="GET" && request.path in listOf("/cloud","/cloud/","/cloud/index.html","/cloud/native.js","/cloud/style.css") -> {
                    val name=when(request.path){"/cloud/native.js"->"native.js";"/cloud/style.css"->"style.css";else->"index.html"}
                    if(name=="index.html" && request.headers["user-agent"].orEmpty().contains("Android",true)) {
                        platform.flag("androidPageReached",true)
                        platform.status("Android client reached the connection page; enter its access code in Pilot 2")
                    }
                    val type=if(name.endsWith("js"))"text/javascript" else if(name.endsWith("css"))"text/css" else "text/html; charset=utf-8"
                    CloudHttpResponse(type=type,bytes=assets(name),headers=mapOf("Content-Security-Policy" to "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"))
                }
                request.method=="POST" && request.path=="/cloud/login" -> login(request)
                request.path.startsWith("/r2c-media") -> platform.s3.handle(request)
                request.method=="GET" && request.path=="/cloud/route.kmz" && same(request.query["ticket"].orEmpty(),token) -> platform.downloadRoute()
                !authenticated(request) -> DjiCloudPlatform.failure(401,"Pair with the R2C tablet first.")
                else -> platform.api(request)
            }
            respond(socket,request,response)
        }catch(_:javax.net.ssl.SSLHandshakeException){
            platform.flag("httpsRejected",true)
            platform.status("A client rejected the secure connection. Pilot 2 may need a trusted tablet certificate; ask the deployment administrator.")
        }catch(error:IllegalArgumentException){runCatching{respond(socket,pendingRequest,DjiCloudPlatform.failure(400,error.message?.take(200) ?: "Request rejected."))}}
        catch(_:Exception){runCatching{respond(socket,pendingRequest,DjiCloudPlatform.failure(500,"Local operation stopped; originals already received remain on the tablet."))}}
    }
    @Synchronized private fun login(request:CloudHttpRequest):CloudHttpResponse {
        if(now()<lockedUntil)return DjiCloudPlatform.failure(429,"Wait one minute before trying another code.")
        val code=JSONObject(request.readBody(1024).toString(Charsets.UTF_8)).optString("code")
        if(!same(code,this.code)) { failures++; if(failures>=5){failures=0;lockedUntil=now()+60000}; return DjiCloudPlatform.failure(401,"Read the access code shown on the R2C tablet.") }
        return DjiCloudPlatform.success(JSONObject().put("token",token))
    }
    private fun authenticated(request:CloudHttpRequest) = same(request.headers["x-auth-token"] ?: request.query["x-auth-token"] ?: "",token)
    override fun close(){synchronized(lifecycle){closed=true;keepalive.cancel();runCatching{listener.close()};sockets.forEach{runCatching{it.close()}};workers.shutdownNow()}}
    internal val isDrained:Boolean get()=workers.isTerminated
    /** Off the UI thread, and outside manager locks: callbacks may still be finishing disk writes. */
    fun closeAndWait(timeoutMillis:Long=30000) {
        close()
        check(workers.awaitTermination(timeoutMillis,TimeUnit.MILLISECONDS)) {
            "Photo transfer is still finishing. No files were deleted; try again shortly."
        }
    }
    companion object {
        private fun random()=LocalS3.hex(ByteArray(32).also{SecureRandom().nextBytes(it)})
        private fun same(a:String,b:String)=MessageDigest.isEqual(a.toByteArray(),b.toByteArray())
    }
}

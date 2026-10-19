package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/** The credential is restricted to a single mission prefix and current platform session. */
class LocalS3(private val directory: File, val prefix: String, private val expiresAt: Long, private val now: () -> Long = System::currentTimeMillis) {
    val accessKey = "R2C" + random().take(17)
    private val secret = random()
    private val securityToken = random()
    val bucket = "r2c-media"
    val region = "us-east-1"
    private val objects = File(directory, "objects").apply { mkdirs() }
    private val pending = File(directory, "multipart").apply { mkdirs() }

    fun credentials(endpoint: String) = JSONObject().put("bucket", bucket).put("endpoint", endpoint)
        .put("object_key_prefix", "$prefix/").put("provider", "minio").put("region", region)
        .put("credentials", JSONObject().put("access_key_id", accessKey).put("access_key_secret", secret)
            .put("security_token", securityToken).put("expire", ((expiresAt-now())/1000).coerceAtLeast(1)))

    /** AWS Signature V4, including signed URLs used by compatible native S3 clients. */
    fun authorize(request: CloudHttpRequest): Boolean = runCatching {
        if (now() >= expiresAt) return false
        val query = request.query
        val date = request.headers["x-amz-date"] ?: query["X-Amz-Date"] ?: return false
        val sentAt = Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).parse(date)).toEpochMilli()
        if (kotlin.math.abs(now()-sentAt) > 15 * 60 * 1000) return false
        val suppliedToken = request.headers["x-amz-security-token"] ?: query["X-Amz-Security-Token"] ?: return false
        if (!same(suppliedToken, securityToken)) return false
        val authorization = request.headers["authorization"]
        val fields = authorization?.removePrefix("AWS4-HMAC-SHA256 ")?.split(',')?.associate { val pair=it.trim().split('=',limit=2); pair[0] to pair.getOrElse(1){""} }
        if (authorization != null && !authorization.startsWith("AWS4-HMAC-SHA256 ")) return false
        val credential = fields?.get("Credential") ?: query["X-Amz-Credential"] ?: return false
        val parts = credential.split('/'); if(parts.size != 5 || parts[0] != accessKey || parts[1] != date.take(8) || parts[2] != region || parts[3] != "s3" || parts[4] != "aws4_request") return false
        val signed = fields?.get("SignedHeaders") ?: query["X-Amz-SignedHeaders"] ?: return false
        val names = signed.split(';'); if(names != names.sorted() || names.distinct().size != names.size || "host" !in names) return false
        if(names.any{!request.headers.containsKey(it)})return false
        val canonicalHeaders = names.joinToString("") { name -> name + ":" + request.headers.getValue(name).trim().replace(Regex("\\s+"), " ") + "\n" }
        val pairs = request.uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val pair=it.split('=',limit=2)
            java.net.URLDecoder.decode(pair[0],"UTF-8") to java.net.URLDecoder.decode(pair.getOrElse(1){""},"UTF-8")
        }.filterNot { authorization == null && it.first == "X-Amz-Signature" }
        val canonicalQuery = pairs.map { encode(it.first) to encode(it.second) }.sortedWith(compareBy({it.first},{it.second})).joinToString("&") { "${it.first}=${it.second}" }
        val payloadHash = request.headers["x-amz-content-sha256"] ?: if(authorization == null) "UNSIGNED-PAYLOAD" else sha256(ByteArray(0))
        val canonical = "${request.method}\n${request.uri.rawPath}\n$canonicalQuery\n$canonicalHeaders\n$signed\n$payloadHash"
        val scope = parts.drop(1).joinToString("/")
        val toSign = "AWS4-HMAC-SHA256\n$date\n$scope\n${sha256(canonical.toByteArray())}"
        val key = hmac(hmac(hmac(hmac(("AWS4"+secret).toByteArray(),parts[1]),region),"s3"),"aws4_request")
        val signature = fields?.get("Signature") ?: query["X-Amz-Signature"] ?: return false
        if (authorization == null) {
            val lifetime = query["X-Amz-Expires"]?.toLongOrNull() ?: return false
            if(lifetime !in 1..7200 || now() > sentAt+lifetime*1000) return false
        }
        same(signature, hex(hmac(key,toSign)))
    }.getOrDefault(false)

    @Synchronized fun handle(request: CloudHttpRequest): CloudHttpResponse {
        if (!authorize(request)) return xmlError(403,"AccessDenied","The upload session is invalid or its clock/certificate settings need attention.")
        if (request.path == "/$bucket") {
            if(request.method == "HEAD") return CloudHttpResponse(type="application/xml")
            if(request.method == "GET" && request.query.containsKey("location")) return xml("<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">$region</LocationConstraint>")
            return xmlError(400,"InvalidRequest","Only mission-scoped objects are supported.")
        }
        require(request.path.startsWith("/$bucket/")) { "Unsupported storage bucket." }
        val key = request.path.removePrefix("/$bucket/")
        checkKey(key)
        val uploadId = request.query["uploadId"]
        if(uploadId != null) {
            require(uploadId.matches(Regex("[a-f0-9-]{36}"))) { "Invalid multipart upload ID." }
            val folder = File(pending,uploadId); val info=JSONObject(File(folder,"upload.json").readText())
            require(info.getString("key") == key) { "Multipart key does not match this upload." }
            when(request.method) {
                "PUT" -> {
                    val part = request.query["partNumber"]?.toIntOrNull() ?: 0; require(part in 1..1000)
                    val destination=File(folder,"$part.part"); val (size,md5,sha)=receive(request,File(folder,"$part.tmp"))
                    val other=folder.listFiles().orEmpty().filter { it.extension=="part" && it!=destination }.sumOf { it.length() }
                    require(other+size <= MappingPhotoStore.MAX_PHOTO_BYTES) { "Original exceeds 128 MiB." }
                    check(File(folder,"$part.tmp").renameTo(destination))
                    MappingPhotoStore.writeJson(File(folder,"$part.json"),JSONObject().put("size",size).put("md5",md5).put("sha256",sha))
                    return CloudHttpResponse(type="application/xml",headers=mapOf("ETag" to "\"$md5\""))
                }
                "GET" -> {
                    val parts=folder.listFiles().orEmpty().filter { it.extension=="part" }.sortedBy { it.nameWithoutExtension.toInt() }
                    return xml("<ListPartsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Bucket>$bucket</Bucket><Key>${escape(key)}</Key><UploadId>$uploadId</UploadId><IsTruncated>false</IsTruncated>"+parts.joinToString("") { file ->
                        val record=JSONObject(File(folder,"${file.nameWithoutExtension}.json").readText())
                        "<Part><PartNumber>${file.nameWithoutExtension}</PartNumber><ETag>\"${record.getString("md5")}\"</ETag><Size>${file.length()}</Size></Part>"
                    }+"</ListPartsResult>")
                }
                "POST" -> {
                    val document = parseXml(request.readBody(256*1024))
                    val nodes=document.getElementsByTagName("Part"); require(nodes.length in 1..1000)
                    val selected=mutableListOf<File>(); val digests=ByteArrayOutputStream(); var last=0
                    for(index in 0 until nodes.length) {
                        val node=nodes.item(index) as org.w3c.dom.Element
                        val part=node.getElementsByTagName("PartNumber").item(0).textContent.toInt(); require(part > last); last=part
                        val infoPart=JSONObject(File(folder,"$part.json").readText())
                        require(node.getElementsByTagName("ETag").item(0).textContent.trim('"') == infoPart.getString("md5")) { "Multipart checksum changed." }
                        selected.add(File(folder,"$part.part")); digests.write(unhex(infoPart.getString("md5")))
                    }
                    require(selected.sumOf { it.length() } <= MappingPhotoStore.MAX_PHOTO_BYTES)
                    val temporary=File(folder,"complete.tmp"); temporary.outputStream().use { output -> selected.forEach { file -> file.inputStream().use { it.copyTo(output,65536) } } }
                    val etag=hex(MessageDigest.getInstance("MD5").digest(digests.toByteArray()))+"-"+selected.size
                    retain(key,temporary,etag)
                    folder.deleteRecursively()
                    return xml("<CompleteMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Bucket>$bucket</Bucket><Key>${escape(key)}</Key><ETag>\"$etag\"</ETag></CompleteMultipartUploadResult>")
                }
                "DELETE" -> { folder.deleteRecursively(); return CloudHttpResponse(204,"application/xml") }
            }
        }
        if(request.method=="POST" && request.query.containsKey("uploads")) {
            require(pending.listFiles().orEmpty().size < 1000) { "Too many incomplete original uploads." }
            val id=UUID.randomUUID().toString(); val folder=File(pending,id).apply{mkdirs()}
            MappingPhotoStore.writeJson(File(folder,"upload.json"),JSONObject().put("key",key))
            return xml("<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Bucket>$bucket</Bucket><Key>${escape(key)}</Key><UploadId>$id</UploadId></InitiateMultipartUploadResult>")
        }
        if(request.method=="PUT") {
            val temporary=File(pending,UUID.randomUUID().toString()+".tmp")
            val (_,md5,_)=receive(request,temporary); retain(key,temporary,md5)
            return CloudHttpResponse(type="application/xml",headers=mapOf("ETag" to "\"$md5\""))
        }
        val file=objectFile(key)
        if(request.method in listOf("GET","HEAD") && file.isFile) {
            val info=JSONObject(recordFile(key).readText())
            return CloudHttpResponse(type="image/jpeg",file=file,headers=mapOf("ETag" to "\"${info.getString("etag")}\""), head=request.method=="HEAD")
        }
        return xmlError(404,"NoSuchKey","The original is not stored locally.")
    }

    fun objectFile(key: String): File { checkKey(key); return File(objects,sha256(key.toByteArray())+".jpg") }
    private fun recordFile(key: String) = File(objects,sha256(key.toByteArray())+".json")
    private fun checkKey(key: String) { require(key.startsWith("$prefix/") && key.length <= 512 && key.split('/').none { it==".." || it=="." || it.isEmpty() } && key.none { it.code<32 || it=='\\' } && key.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }) { "Upload key is outside this mission's original-photo collection." } }
    private fun receive(request: CloudHttpRequest, destination: File): Triple<Long,String,String> {
        require(request.headers["content-encoding"] != "aws-chunked") { "AWS streaming signatures are not supported by this local provider. Use the MinIO provider." }
        val hash=MessageDigest.getInstance("SHA-256"); val md5=MessageDigest.getInstance("MD5")
        var size=0L
        try {
            FileOutputStream(destination).use { output ->
                val checked=object:OutputStream() {
                    override fun write(value:Int) { write(byteArrayOf(value.toByte()),0,1) }
                    override fun write(bytes:ByteArray,offset:Int,count:Int) {
                        require(directory.usableSpace - count >= MappingPhotoStore.FREE_SPACE_RESERVE) { "Tablet storage is full; completed originals are retained." }
                        hash.update(bytes,offset,count); md5.update(bytes,offset,count); output.write(bytes,offset,count)
                    }
                }
                size=request.copyBody(checked,MappingPhotoStore.MAX_PHOTO_BYTES); output.fd.sync()
            }
            val digest=hex(hash.digest()); val checksum=hex(md5.digest())
            request.headers["x-amz-content-sha256"]?.takeIf{it!="UNSIGNED-PAYLOAD"}?.let { require(it==digest) { "Original upload checksum failed." } }
            request.headers["content-md5"]?.let { require(java.util.Base64.getDecoder().decode(it).contentEquals(unhex(checksum))) { "Original MD5 checksum failed." } }
            return Triple(size,checksum,digest)
        } catch(error:Exception) { destination.delete(); throw error }
    }
    private fun retain(key:String,temporary:File,etag:String) {
        temporary.inputStream().use { require(it.read()==255 && it.read()==216) { "Only original JPEG photos are accepted." } }
        val digest=MessageDigest.getInstance("SHA-256"); temporary.inputStream().use { input -> val buffer=ByteArray(65536); while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)} }
        val sha=hex(digest.digest()); val existing=objectFile(key)
        if(existing.exists()) require(JSONObject(recordFile(key).readText()).getString("sha256")==sha) { "An existing original cannot be overwritten with different bytes." }
        check(temporary.renameTo(existing))
        MappingPhotoStore.writeJson(recordFile(key),JSONObject().put("objectKey",key).put("sha256",sha).put("etag",etag).put("sizeBytes",existing.length()))
    }
    // Upload bytes are immutable and checksum-verified before this record is committed.
    fun hasRetainedObject(key:String,digest:String):Boolean = runCatching {
        val record=JSONObject(recordFile(key).readText());val file=objectFile(key)
        file.isFile && file.length()>0 && file.length()==record.getLong("sizeBytes") && record.getString("sha256")==digest
    }.getOrDefault(false)
    fun objectDigest(key:String) = JSONObject(recordFile(key).readText()).getString("sha256")
    companion object {
        private fun random() = hex(ByteArray(32).also { SecureRandom().nextBytes(it) })
        fun sha256(bytes:ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun hex(bytes:ByteArray) = bytes.joinToString(""){ "%02x".format(it.toInt() and 255) }
        private fun unhex(value:String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun same(a:String,b:String) = MessageDigest.isEqual(a.toByteArray(),b.toByteArray())
        private fun hmac(key:ByteArray,text:String) = Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(key,"HmacSHA256"))}.doFinal(text.toByteArray())
        private fun encode(value:String) = java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20").replace("*","%2A").replace("%7E","~")
        private fun escape(value:String) = value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        private fun xml(value:String) = CloudHttpResponse(type="application/xml",bytes=("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"+value).toByteArray())
        private fun xmlError(status:Int,code:String,message:String) = CloudHttpResponse(status,"application/xml",("<Error><Code>$code</Code><Message>${escape(message)}</Message></Error>").toByteArray())
        private fun parseXml(bytes:ByteArray): org.w3c.dom.Document {
            require(!bytes.toString(Charsets.UTF_8).contains("<!")) { "XML declarations/entities are not accepted." }
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }
    }
}

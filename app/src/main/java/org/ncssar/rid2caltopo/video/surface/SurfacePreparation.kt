package org.ncssar.rid2caltopo.video.surface

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.video.mapcache.UnifiedMapCache
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

internal data class SurfaceBounds(val west: Double, val south: Double, val east: Double, val north: Double)
internal data class SurfaceSource(val url: String, val metadataURL: String, val published: String, val bytes: Long, val survey: String)
internal data class SurfacePreparationPlan(val bounds: SurfaceBounds, val latitude: Double, val longitude: Double, val width: Int, val height: Int, val sources: List<SurfaceSource>, val reused: Boolean = false) {
    val advertisedBytes get() = sources.sumOf { it.bytes }
    val tiles get() = ((width + 999) / 1000) * ((height + 999) / 1000)
}
internal object SurfacePreparation {
    private const val R = 6371008.8
    private const val MAX_SOURCE_BYTES = 1_000_000_000L
    private val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { work ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); work.run() }, "AOL preparation").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    fun grid(bounds: SurfaceBounds): SurfacePreparationPlan {
        require(listOf(bounds.west,bounds.south,bounds.east,bounds.north).all { it.isFinite() } && bounds.west <= bounds.east && bounds.south <= bounds.north && abs(bounds.south)<70 && abs(bounds.north)<70 && abs(bounds.west)<=180 && abs(bounds.east)<=180) { "Invalid AOL region" }
        val lat=(bounds.south+bounds.north)/2;val lon=(bounds.west+bounds.east)/2
        val w=ceil(Math.toRadians(bounds.east-bounds.west)*R*cos(Math.toRadians(lat))+124).toInt()
        val h=ceil(Math.toRadians(bounds.north-bounds.south)*R+124).toInt()
        require(w in 1..4000 && h in 1..4000) { "AOL preparation supports regions up to about 4 km across; select a smaller map or assignment" }
        return SurfacePreparationPlan(bounds,lat,lon,w,h,emptyList())
    }
    fun sources(pages: List<JSONObject>): List<SurfaceSource> {
        val found=linkedMapOf<String,SurfaceSource>()
        for(page in pages) {
            val items=page.optJSONArray("items") ?: continue
            for(i in 0 until items.length()) {
                val v=items.getJSONObject(i);val url=v.optString("downloadURL")
                val u=runCatching { java.net.URI(url) }.getOrNull() ?: continue
                if(u.scheme!="https" || !(u.host=="rockyweb.usgs.gov" || u.host=="prd-tnm.s3.amazonaws.com") || !u.path.endsWith(".laz",true) || !u.path.contains("/Projects/") || !u.path.contains("/LAZ/")) continue
                val bytes=v.optLong("sizeInBytes",-1)
                if(bytes !in 1..MAX_SOURCE_BYTES) continue
                val survey=u.path.substringBefore("/LAZ/")
                found[url]=SurfaceSource(url,v.optString("metaUrl",url),v.optString("publicationDate","Unknown"),bytes,survey)
            }
        }
        require(found.isNotEmpty()) { "No supported USGS lidar source files found for this area" }
        // Never blend overlapping surveys merely because their bounds overlap.
        val chosen=found.values.groupBy { it.survey }.values.maxBy { group -> group.maxOf { it.published } + "|" + group.first().survey }
        require(chosen.size<=64) { "Too many lidar files; select a smaller region" }
        return chosen.sortedBy { it.url }
    }
    suspend fun plan(bounds: SurfaceBounds, client: OkHttpClient, onCall: (Call, Boolean)->Unit = {_,_->}, context: Context? = null): SurfacePreparationPlan = withContext(Dispatchers.IO) {
        if(context != null && SurfaceStore.reusable(context,bounds)) return@withContext grid(bounds).copy(reused=true)
        SurfaceTiming("aol-catalog").use { timing ->
        val base=grid(bounds);val dx=65/(R*cos(Math.toRadians(base.latitude)))*180/PI;val dy=65/R*180/PI
        val pages=mutableListOf<JSONObject>();var offset=0
        do {
            currentCoroutineContext().ensureActive()
            val url=HttpUrl.Builder().scheme("https").host("tnmaccess.nationalmap.gov").addPathSegments("api/v1/products")
                .addQueryParameter("datasets","Lidar Point Cloud (LPC)")
                .addQueryParameter("bbox","${bounds.west-dx},${bounds.south-dy},${bounds.east+dx},${bounds.north+dy}")
                .addQueryParameter("max","100").addQueryParameter("offset",offset.toString()).addQueryParameter("outputFormat","JSON").build()
            val data=SurfaceCatalog.data(Request.Builder().url(url).build(),client,onCall)
            val page=JSONObject(String(data,Charsets.UTF_8))
            val count=page.optJSONArray("items")?.length() ?: 0
            pages+=page;offset+=count
            check(count>0 && offset<=500) { "Lidar catalog is empty or too large; choose a smaller region" }
        } while(offset<pages.last().optInt("total",offset))
        timing.mark("catalog-ready")
        base.copy(sources=sources(pages))
        }
    }
    suspend fun prepare(context: Context, plan: SurfacePreparationPlan, client: OkHttpClient, onCall: (Call,Boolean)->Unit, onActivity: () -> Unit = {}, progress: suspend (String)->Unit): String = withContext(dispatcher) {
        if(SurfaceStore.reusable(context,plan.bounds)) return@withContext "AOL already prepared — using cached tiles"
        check(!plan.reused) { "Cached AOL coverage expired or changed. Reopen Download Map to refresh the plan." }
        SurfaceTiming("aol-prepare").use { timing ->
        val root=File(context.noBackupFilesDir,"surface_v1");root.mkdirs()
        context.cacheDir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("aol-prep-") }.forEach { it.deleteRecursively() }
        val scratch=File(context.cacheDir,"aol-prep-${UUID.randomUUID()}");scratch.mkdirs()
        val staged=File(scratch,"prepared");staged.mkdirs()
        // Temporary source files count against the same cache allowance. Reserve
        // advertised bytes plus headroom; actual downloads have a per-file ceiling.
        val allowance=(plan.advertisedBytes*3/2+plan.tiles*16_000_000L)
        check(context.cacheDir.usableSpace>=allowance+64_000_000) { "Not enough free working space for AOL; select a smaller region or free storage" }
        UnifiedMapCache.reserveWithoutEviction(context,allowance).use { reservation ->
            try {
                progress("AOL: ${plan.sources.size} lidar files, ${plan.advertisedBytes/1_000_000} MB advertised; ${plan.tiles} output tiles")
                val files=mutableListOf<File>();val hashes=JSONObject();var used=0L
                for((index,source) in plan.sources.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    timing.mark("source-${index+1}-download-start")
                    val file=File(scratch,"source-$index.laz");files+=file
                    SurfaceTransferRetry.run(onRetry={ attempt ->
                        file.delete()
                        progress("Retrying AOL lidar ${index+1}/${plan.sources.size} after interrupted transfer (retry $attempt/2)")
                    }) {
                    onActivity()
                    val call=client.newCall(Request.Builder().url(source.url).header("Accept-Encoding","identity").build());onCall(call,true)
                    val digest=MessageDigest.getInstance("SHA-256")
                    try { call.execute().use { response ->
                        check(response.isSuccessful) { "Lidar download HTTP ${response.code}" }
                        val body=response.body ?: error("Empty lidar download")
                        val expected=body.contentLength();check(expected in 1..MAX_SOURCE_BYTES) { "Lidar file has unsupported size" }
                        check(used+expected+plan.tiles*16_000_000L<=allowance) { "Lidar files exceed the reserved space; select a smaller region" }
                        var count=0L;var last=0L
                        file.outputStream().use { output -> body.byteStream().use { input ->
                            val buffer=ByteArray(65536)
                            while(true) {
                                currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                                onActivity()
                                count+=n;check(count<=expected);output.write(buffer,0,n);digest.update(buffer,0,n)
                                if(count-last>=2_000_000) { progress("AOL lidar ${index+1}/${filesCount(plan)}: ${count/1_000_000}/${expected/1_000_000} MB");last=count }
                            }
                        } }
                        check(count==expected) { "Incomplete lidar download" };used+=count
                    } } finally { onCall(call,false) }
                    hashes.put(source.url,digest.digest().hex())
                    timing.mark("source-${index+1}-download-and-checksum-complete bytes=${file.length()}")
                    }
                }
                val reference=MessageDigest.getInstance("SHA-256").digest(hashes.toString().toByteArray()).hex()
                timing.mark("assembly-start tiles=${plan.tiles}")
                val regionID="${System.currentTimeMillis()}-${UUID.randomUUID()}"
                val entries=JSONArray();var tile=0;var missing=0L;var referenceCRS:String?=null
                for(row in 0 until plan.height step 1000) for(col in 0 until plan.width step 1000) {
                    currentCoroutineContext().ensureActive();tile++
                    val cw=min(1000,plan.width-col);val ch=min(1000,plan.height-row)
                    val coreWest=-plan.width/2.0+col;val coreSouth=-plan.height/2.0+row
                    val left=min(62,col);val bottom=min(62,row)
                    val west=coreWest-left;val south=coreSouth-bottom;val w=cw+left+min(62,plan.width-col-cw);val h=ch+bottom+min(62,plan.height-row-ch)
                    val handle=SurfaceNative.create(plan.latitude,plan.longitude,west,south,w,h);check(handle!=0L) { "Unable to allocate AOL tile" }
                    val sf=File(scratch,"surface.f32");val gf=File(scratch,"ground.f32")
                    try {
                        for((index,file) in files.withIndex()) {
                            check(SurfaceNative.open(handle,file.path)==0) { SurfaceNative.error(handle) }
                            val crs=SurfaceNative.crs(handle)
                            check(referenceCRS==null || referenceCRS==crs) { "Survey reference mismatch" };referenceCRS=crs
                            var status:Int;var batches=0
                            do {
                                currentCoroutineContext().ensureActive();onActivity();status=SurfaceNative.step(handle,10000)
                                check(status>=0) { SurfaceNative.error(handle) }
                                if(++batches%20==0) progress("Assembling AOL tile $tile/${plan.tiles}; source ${index+1}/${files.size}: ${SurfaceNative.read(handle)*100/max(1,SurfaceNative.count(handle))}%")
                            } while(status>0)
                        }
                        check(SurfaceNative.write(handle,sf.path,gf.path)==0) { SurfaceNative.error(handle) };missing+=SurfaceNative.missing(handle)
                        val metadata=JSONObject().put("schema",1).put("layer","top-surface").put("id","$regionID-$row-$col").put("version",reference)
                            .put("processingVersion","r2c-native-lidar-max-1").put("sourceURL",plan.sources.first().metadataURL)
                            .put("surveyDate","Acquisition date not supplied by tile catalog; publication ${plan.sources.maxOf { it.published }}")
                            .put("verticalReference",when { referenceCRS!!.uppercase().contains("GEOID18") -> "NAVD88 / GEOID18 / metres"; referenceCRS!!.uppercase().contains("GEOID12B") -> "NAVD88 / GEOID12B / metres"; else -> "NAVD88 / same-survey paired reference / metres" })
                            .put("horizontalCRS","R2C_LOCAL_EQUIRECTANGULAR_WGS84").put("units","metres")
                            .put("quality","Accepted LAS maxima; withheld and noise classes 7/18 excluded. Surface holes remain unknown. Ground uses nearest class 2 within 3 m. Same source reference for surface and ground; geoid realization is recorded in the source CRS when supplied. No wire completeness claim.")
                            .put("originLatitude",plan.latitude).put("originLongitude",plan.longitude).put("west",west).put("south",south).put("spacing",1).put("width",w).put("height",h)
                            .put("coreWest",coreWest).put("coreSouth",coreSouth).put("coreWidth",cw).put("coreHeight",ch)
                            .put("referenceGroup",reference).put("sourceCRS",referenceCRS).put("sourceSHA256",hashes)
                        val name="tile-$row-$col.aol";writePackage(File(staged,name),metadata,sf,gf)
                        entries.put(JSONObject().put("file",name).put("metadata",metadata))
                    } finally { SurfaceNative.free(handle);sf.delete();gf.delete() }
                }
                val index=JSONObject().put("schema",1).put("referenceGroup",reference).put("originLatitude",plan.latitude).put("originLongitude",plan.longitude)
                    .put("width",plan.width).put("height",plan.height).put("entries",entries)
                File(staged,"index.json").writeText(index.toString())
                timing.mark("assembly-complete")
                currentCoroutineContext().ensureActive()
                files.forEach { check(it.delete()) { "Unable to remove temporary lidar file" } }
                SurfaceStore.installPrepared(context,staged,regionID,reservation)
                timing.mark("publication-complete")
                "Prepared ${plan.tiles} AOL tiles; $missing missing surface cells including overlapping margins. AOL stays unavailable wherever its disk has gaps."
            } finally { scratch.deleteRecursively() }
        }
    }
    }
    private fun filesCount(plan: SurfacePreparationPlan)=plan.sources.size
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it) }
    private fun writePackage(file: File, metadata: JSONObject, surface: File, ground: File) {
        for((name,f) in listOf("surface" to surface,"ground" to ground)) metadata.put(name+"SHA256",MessageDigest.getInstance("SHA-256").digest(f.readBytes()).hex())
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(metadata.toString().toByteArray());zip.closeEntry()
            for((name,f) in listOf("surface.f32" to surface,"ground.f32" to ground)) { zip.putNextEntry(ZipEntry(name));f.inputStream().use { it.copyTo(zip) };zip.closeEntry() }
        }
        SurfacePackage.decode(file.readBytes())
    }
    private fun java.io.InputStream.readNBytesBounded(limit: Int): ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) { val n=read(buffer);if(n<0)break;require(out.size()+n<=limit);out.write(buffer,0,n) };return out.toByteArray()
    }
}

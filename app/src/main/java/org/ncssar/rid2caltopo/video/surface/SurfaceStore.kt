package org.ncssar.rid2caltopo.video.surface

import android.content.Context
import org.ncssar.rid2caltopo.video.mapcache.UnifiedMapCache
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Prepared assignment is pinned within the shared budget; updates validate before atomic replacement. */
internal object SurfaceStore {
    private var regions: List<Pair<File, org.json.JSONObject>>? = null
    private var loaded:SurfacePackage?=null
    private var loadedPath:String?=null
    @Volatile var generation=0L; private set
    private fun file(context:Context)=File(context.noBackupFilesDir,"surface_v1/active.aol")
    @Synchronized fun releaseMemory() { loaded = null; loadedPath = null }
    @Synchronized fun current(context:Context):SurfacePackage? {
        val f=file(context)
        if(loadedPath!=f.path) {
            loaded=if(f.exists() && f.length()<=SurfacePackage.MAX_BYTES) runCatching { SurfacePackage.decode(f.readBytes()) }.getOrNull() else null
            loadedPath=f.path
            if(f.exists()) UnifiedMapCache.remember(f)
        }
        return loaded
    }
    private fun catalog(context: Context): List<Pair<File, org.json.JSONObject>> {
        regions?.let { return it }
        return File(context.noBackupFilesDir,"surface_v1/sets").listFiles().orEmpty()
            .filter { it.isDirectory }.sortedByDescending { it.name }.mapNotNull { dir ->
                val f=File(dir,"index.json")
                if(!f.exists() || f.length()>2_000_000) null else runCatching { dir to org.json.JSONObject(f.readText()) }.getOrNull()
            }.also { regions=it }
    }
    private fun preparedAt(dir: File, index: org.json.JSONObject): Long =
        index.optLong("preparedAtEpochMs", dir.name.substringBefore('-').toLongOrNull() ?: 0L)

    /** Whole prepared sets preserve their overlap and survey reference when shared. */
    @Synchronized fun exportSets(context: Context, bounds: SurfaceBounds): List<Pair<String,ByteArray>> {
        val result=mutableListOf<Pair<String,ByteArray>>()
        for ((dir,index) in catalog(context)) {
            val (west,south)=xy(index,bounds.south,bounds.west)
            val (east,north)=xy(index,bounds.north,bounds.east)
            if(east < -index.getInt("width")/2.0 || west > index.getInt("width")/2.0 || north < -index.getInt("height")/2.0 || south > index.getInt("height")/2.0) continue
            SurfacePreparedSet.validate(index) { name -> File(dir,name).readBytes() }
            val copy=org.json.JSONObject(index.toString()).put("preparedAtEpochMs",preparedAt(dir,index))
            val entries=copy.getJSONArray("entries")
            for(i in 0 until entries.length()) {
                val name=entries.getJSONObject(i).getString("file")
                require(name.matches(Regex("tile-[0-9]+-[0-9]+\\.aol")))
                val f=File(dir,name);require(f.length() in 1..SurfacePackage.MAX_BYTES.toLong())
                val bytes=f.readBytes();SurfacePackage.decode(bytes)
                result += "aol/${dir.name}/$name" to bytes
            }
            result += "aol/${dir.name}/index.json" to copy.toString().toByteArray()
        }
        return result
    }
    @Synchronized fun reusable(context: Context,bounds: SurfaceBounds): Boolean = catalog(context).any { (dir,index) ->
        runCatching {
            if(!SurfacePreparedSet.fresh(preparedAt(dir,index),System.currentTimeMillis(),org.ncssar.rid2caltopo.video.mapcache.MapCachePolicy.tileCacheMaxAgeMs(context))) return@runCatching false
            val (west,south)=xy(index,bounds.south,bounds.west);val (east,north)=xy(index,bounds.north,bounds.east)
            if(!SurfacePreparedSet.contains(index.getInt("width"),index.getInt("height"),west,south,east,north)) return@runCatching false
            SurfacePreparedSet.validate(index) { name ->
                val f=File(dir,name);require(f.length() in 1..SurfacePackage.MAX_BYTES.toLong());f.readBytes()
            }
            true
        }.getOrDefault(false)
    }
    @Synchronized fun importSets(context: Context, files: Map<String,ByteArray>): Int {
        val groups=files.filterKeys { it.startsWith("aol/") }.entries.groupBy { it.key.split('/').getOrNull(1) ?: "" }
        var count=0
        for((id,entries) in groups) {
            require(id.matches(Regex("[0-9a-fA-F-]+"))) { "Invalid AOL set identifier" }
            val index=org.json.JSONObject(String(files["aol/$id/index.json"] ?: error("Missing AOL index"),Charsets.UTF_8))
            SurfacePreparedSet.validate(index) { name -> files["aol/$id/$name"] ?: error("Missing AOL tile") }
            val list=index.getJSONArray("entries");require(list.length() in 1..16)
            val staged=File(context.cacheDir,"aol-import-${java.util.UUID.randomUUID()}");staged.mkdirs()
            try {
                for(i in 0 until list.length()) {
                    val entry=list.getJSONObject(i);val name=entry.getString("file")
                    require(name.matches(Regex("tile-[0-9]+-[0-9]+\\.aol")))
                    val bytes=files["aol/$id/$name"] ?: error("Missing AOL tile")
                    val decoded=SurfacePackage.decode(bytes)
                    entry.put("metadata",decoded.metadata)
                    File(staged,name).writeBytes(bytes)
                }
                require(entries.size==list.length()+1) { "Unexpected AOL set contents" }
                require(index.getInt("width") in 1..4000 && index.getInt("height") in 1..4000)
                File(staged,"index.json").writeText(index.toString())
                val target=File(context.noBackupFilesDir,"surface_v1/sets/$id")
                if(!target.exists()) UnifiedMapCache.reserveWithoutEviction(context,staged.listFiles()!!.sumOf { it.length() }).use {
                    installPrepared(context,staged,id,it)
                }
                count+=list.length()
            } finally { staged.deleteRecursively() }
        }
        return count
    }
    private fun loadTile(file: File): SurfacePackage? {
        if(loadedPath!=file.path) {
            loaded=if(file.exists() && file.length()<=SurfacePackage.MAX_BYTES) runCatching { SurfacePackage.decode(file.readBytes()) }.getOrNull() else null
            loadedPath=file.path
        }
        return loaded
    }
    private fun xy(m: org.json.JSONObject, lat: Double, lon: Double): Pair<Double,Double> =
        Math.toRadians(lon-m.getDouble("originLongitude"))*6371008.8*kotlin.math.cos(Math.toRadians(m.getDouble("originLatitude"))) to Math.toRadians(lat-m.getDouble("originLatitude"))*6371008.8
    private fun selected(context: Context,lat: Double,lon: Double,reference: String?=null): SurfacePackage? {
        for((dir,index) in catalog(context)) {
            if(reference!=null && index.optString("referenceGroup")!=reference) continue
            val entries=index.getJSONArray("entries")
            for(i in 0 until entries.length()) {
                val entry=entries.getJSONObject(i);val m=entry.getJSONObject("metadata");val (x,y)=xy(m,lat,lon)
                if(x>=m.getDouble("coreWest") && x<m.getDouble("coreWest")+m.getInt("coreWidth") && y>=m.getDouble("coreSouth") && y<m.getDouble("coreSouth")+m.getInt("coreHeight")) {
                    val name=entry.getString("file")
                    if(name.matches(Regex("tile-[0-9]+-[0-9]+\\.aol"))) return loadTile(File(dir,name))
                }
            }
        }
        return null
    }
    @Synchronized fun calculate(context: Context,lat: Double,lon: Double,takeoffLat: Double,takeoffLon: Double,height: Double?): AolState {
        val p=selected(context,lat,lon) ?: current(context) ?: return AolState()
        val group=p.metadata.optString("referenceGroup").takeIf { it.isNotBlank() }
        val ground=p.groundAt(takeoffLat,takeoffLon) ?: group?.let { selected(context,takeoffLat,takeoffLon,it)?.takeIf { other -> other.metadata.optString("sourceCRS")==p.metadata.optString("sourceCRS") }?.groundAt(takeoffLat,takeoffLon) }
        return AolState.calculate(p,lat,lon,takeoffLat,takeoffLon,height,ground)
    }
    @Synchronized fun preparedBriefing(context: Context,points: List<Pair<Double,Double>>,polygon: Boolean): Pair<SurfacePackage,SurfacePackage.Analysis>? {
        if(points.isEmpty()) return null
        for((dir,index) in catalog(context)) {
            val coords=points.map { xy(index,it.first,it.second) };val w=index.getInt("width")/2.0;val h=index.getInt("height")/2.0
            if(coords.any { it.first-61.67 < -w || it.first+61.67>=w || it.second-61.67 < -h || it.second+61.67>=h }) continue
            val entries=index.getJSONArray("entries");var first:SurfacePackage?=null;var peak:SurfacePackage.Peak?=null;var checked=0;var missing=0
            for(i in 0 until entries.length()) {
                val name=entries.getJSONObject(i).getString("file")
                if(!name.matches(Regex("tile-[0-9]+-[0-9]+\\.aol"))) return null
                val p=loadTile(File(dir,name)) ?: return null
                if(first==null) first=p
                val a=p.briefing(points,60.96,polygon,coreOnly=true);checked+=a.checked;missing+=a.missing
                if(a.peak!=null && (peak==null || a.peak.elevation>peak.elevation)) peak=a.peak
            }
            if(first!=null) return first to SurfacePackage.Analysis(peak,checked>0 && missing==0,checked,missing)
        }
        return null
    }
    @Synchronized fun installPrepared(context: Context,staged: File,id: String,reservation: UnifiedMapCache.Reservation) {
        require(id.matches(Regex("[0-9a-fA-F-]+")))
        val root=File(context.noBackupFilesDir,"surface_v1/sets");root.mkdirs()
        Files.move(staged.toPath(),File(root,id).toPath(),StandardCopyOption.ATOMIC_MOVE)
        synchronized(UnifiedMapCache.lock) {
            File(root,id).listFiles().orEmpty().forEach { UnifiedMapCache.remember(it) }
            reservation.close()
        }
        regions=null;releaseMemory();generation++
    }
    @Synchronized fun install(context:Context,bytes:ByteArray):SurfacePackage {
        val packageData=SurfacePackage.decode(bytes)
        val f=file(context); f.parentFile!!.mkdirs()
        synchronized(UnifiedMapCache.lock) {
            UnifiedMapCache.reserve(context,bytes.size.toLong()).use {
                val part=File(f.parentFile,"incoming.part")
                try {
                    part.outputStream().use { it.write(bytes); it.fd.sync() }
                    Files.move(part.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                    UnifiedMapCache.remember(f)
                } finally { part.delete() }
            }
        }
        loaded=packageData; loadedPath=if (loaded != null) f.path else null; generation++
        return packageData
    }
}

internal data class SurfaceBriefing(val text:String,val peak:SurfacePackage.Peak?)
internal object SurfaceBriefings {
    private val cache=object:LinkedHashMap<String,SurfaceBriefing>(32,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,SurfaceBriefing>?)=size>32
    }
    @Synchronized fun analyze(context:Context,points:List<Pair<Double,Double>>,polygon:Boolean):SurfaceBriefing {
        val prepared=SurfaceStore.preparedBriefing(context,points,polygon)
        val p=prepared?.first ?: SurfaceStore.current(context) ?: return SurfaceBriefing("Surface package not prepared. Import a .aol package before departure.\n${AolState.EXPLANATION}",null)
        val key="${p.id}|${p.metadata}|$points|$polygon|60.96"
        cache[key]?.let{return it}
        val a=prepared?.second ?: p.briefing(points,60.96,polygon);val peak=a.peak
        val text=buildString {
            append("${if(polygon) "Assignment" else "Route"} with 200 ft ${if(polygon) "boundary buffer" else "corridor radius"}.\n")
            append("Extent: ${points.minOf{it.first}}, ${points.minOf{it.second}} to ${points.maxOf{it.first}}, ${points.maxOf{it.second}}; ${points.size} vertices.\n")
            append(if(a.complete) "Coverage complete.\n" else "INCOMPLETE: ${a.missing} of ${a.checked} cells missing. Maximum below is only the covered portion.\n")
            if(peak!=null) {
                append("Highest mapped surface: ${"%.0f".format(peak.elevation/0.3048)} ft at ${peak.latitude}, ${peak.longitude}.\n")
                append("Ground at high point: ${peak.ground?.let{"%.0f ft".format(it/0.3048)} ?: "unavailable"}; height above local ground: ${peak.ground?.let{"%.0f ft".format((peak.elevation-it)/0.3048)} ?: "unavailable"}. This is highest absolute elevation, not the tallest object.\n")
            }
            append("${p.id}; survey ${p.metadata.getString("surveyDate")}; ${p.spacing} m cells; ${p.metadata.getString("verticalReference")}.\n")
            append("${p.metadata.getString("sourceURL")}\n${p.wireNotes()}\n${AolState.EXPLANATION}\nThis briefing does not set RTH or a permitted flight altitude.")
        }
        return SurfaceBriefing(text,peak).also{cache[key]=it}
    }
}

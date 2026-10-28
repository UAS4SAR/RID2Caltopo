package org.ncssar.rid2caltopo.mapping

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.video.mapcache.TileCacheMapProvider
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.TilesOverlay
import java.net.HttpURLConnection
import java.net.URI

data class MappingLayer(val id: String, val incidentId: String, val title: String,
    val tileUrlTemplate: String, val minZoom: Int, val maxZoom: Int,
    val bounds: List<Double> = emptyList(),val capturedAt:String="",val resolutionCmPerPixel:Double=0.0,
    val photoCount:Int=0,val profile:String="",val quality:String="",val engine:String="",val surfaceModel:String="",
    val assignmentBoundary:List<List<Double>> =emptyList(), val processedAt:String="",
    val cameraProfileId:String="", val missionId:String="") {
    val generatedInstant: java.time.Instant? get() = runCatching { java.time.Instant.parse(processedAt) }.getOrNull()
    val resultTitle: String get() = if(capturedAt.isNotBlank()) title.removeSuffix(" · $capturedAt") else title
    val generatedLabel: String get() = MappingTimestamps.generated(processedAt)

    fun json() = JSONObject().apply {
        put("type","r2c_mapping_layer");put("version",1);put("tileScheme","xyz")
        put("layerId",id);put("incidentId",incidentId);put("title",title);put("tileUrlTemplate",tileUrlTemplate)
        put("minZoom",minZoom);put("maxZoom",maxZoom)
        if (bounds.isNotEmpty()) put("bounds",JSONArray(bounds))
        put("processedAt",processedAt);put("captureTimeLocal",capturedAt);put("groundResolutionCmPerPixel",resolutionCmPerPixel)
        put("cameraProfileId",cameraProfileId);if(missionId.isNotBlank())put("missionId",missionId)
        put("photoCount",photoCount);put("profile",profile);put("quality",quality);put("engine",engine)
        if(surfaceModel.isNotBlank())put("surfaceModel",JSONObject(surfaceModel))
        if(assignmentBoundary.isNotEmpty())put("assignmentBoundary",JSONArray(assignmentBoundary))
    }
    fun overlay(context: Context,onInvalidate:()->Unit={}): Pair<TileCacheMapProvider,TilesOverlay> {
        val source = tileSource()
        val uri=URI(tileUrlTemplate.replace("{Z}","0").replace("{X}","0").replace("{Y}","0"))
        val localOnly=uri.host in listOf("127.0.0.1","localhost","::1","[::1]")
        val provider = MappingTileProvider(context,source,networkAllowed=!localOnly,bounds=bounds)
        val boundary=if(MappingBoundary.soft(context,id))MappingBoundary.resolve(context.filesDir,this) else null
        return provider to (if(boundary!=null)MappingBoundaryOverlay(provider,context,boundary,onInvalidate) else TilesOverlay(provider,context)).apply {
            setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT)
            setLoadingLineColor(android.graphics.Color.TRANSPARENT)
        }
    }
    fun tileSource(): XYTileSource = object : XYTileSource("R2C-Mapping-$id",minZoom,maxZoom,256,".png",emptyArray()) {
            override fun getTileURLString(index: Long): String = tileUrlTemplate.replace("{Z}",MapTileIndex.getZoom(index).toString())
                .replace("{X}",MapTileIndex.getX(index).toString()).replace("{Y}",MapTileIndex.getY(index).toString())
        }
    fun displayBounds(context:Context):List<Double> {
        val boundary=if(MappingBoundary.soft(context,id))MappingBoundary.resolve(context.filesDir,this) else null
        if(boundary==null)return bounds
        val xs=boundary.coordinates.map{MappingGeometry.east(it[0])};val ys=boundary.coordinates.map{MappingGeometry.north(it[1])}
        val margin=MappingBoundary.FEATHER_METERS/boundary.scale
        return listOf(MappingGeometry.longitude(xs.min()-margin),MappingGeometry.latitude(ys.min()-margin),
            MappingGeometry.longitude(xs.max()+margin),MappingGeometry.latitude(ys.max()+margin))
    }
    companion object {
        private fun savedLayers(context:Context):List<MappingLayer> = context.getSharedPreferences("mapping_layers",0).all
            .filterKeys{!it.startsWith("hidden:")&&!it.startsWith("selected")}.values.filterIsInstance<String>().flatMap{text->
                runCatching{val array=JSONArray(text);(0 until array.length()).map{parse(array.getJSONObject(it))}}.getOrDefault(emptyList())
            }.distinctBy{it.id}
        fun allLocal(context:Context):List<MappingLayer> = savedLayers(context)
            .filter{MappingLibraryFiles.contains(LocalMappingTiles.directory(context,it.id), "png")}
        fun selectLocal(context:Context,layer:MappingLayer?){
            context.getSharedPreferences("mapping_layers",0).edit().putString("selectedLocalLayer",layer?.id).apply()
            org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug("MappingLayer", "Selected local layer=${layer?.id ?: "none"}")
        }
        fun selectedLocal(context:Context):MappingLayer? {
            val id=context.getSharedPreferences("mapping_layers",0).getString("selectedLocalLayer",null)?:return null
            // Selection is durable metadata. A transiently unavailable imagery directory must
            // not hide the selected layer; tile availability is handled by the tile workers.
            return savedLayers(context).firstOrNull{it.id==id}
        }
        fun parse(value: JSONObject) = MappingLayer(value.getString("layerId"),value.getString("incidentId"),value.getString("title"),
            value.getString("tileUrlTemplate"),value.getInt("minZoom"),value.getInt("maxZoom"),
            value.optJSONArray("bounds")?.let { array -> (0 until array.length()).map { array.getDouble(it) } } ?: emptyList(),
            value.optString("captureTimeLocal"),value.optDouble("groundResolutionCmPerPixel",0.0),value.optInt("photoCount"),value.optString("profile"),value.optString("quality"),value.optString("engine"),value.optJSONObject("surfaceModel")?.toString().orEmpty(),
            value.optJSONArray("assignmentBoundary")?.let{MappingBoundary.parse(it)}?:emptyList(),value.optString("processedAt"),value.optString("cameraProfileId"),value.optString("missionId")).also {
            java.util.UUID.fromString(it.id)
            require(it.minZoom in 0..23 && it.maxZoom in it.minZoom..23) { "Invalid layer zoom range." }
            require(it.bounds.isEmpty() || (it.bounds.size == 4 && it.bounds.all { bound -> bound.isFinite() } &&
                it.bounds[0] in -180.0..180.0 && it.bounds[2] in -180.0..180.0 &&
                it.bounds[1] in -90.0..90.0 && it.bounds[3] in -90.0..90.0 &&
                it.bounds[0] < it.bounds[2] && it.bounds[1] < it.bounds[3])) { "Invalid layer bounds." }
            require(listOf("{Z}","{X}","{Y}").all(it.tileUrlTemplate::contains)) { "Layer needs an XYZ tile URL." }
            val url = URI(it.tileUrlTemplate.replace("{Z}","0").replace("{X}","0").replace("{Y}","0"))
            require(url.scheme in listOf("http","https") && url.host != null && url.userInfo == null) { "Invalid layer tile URL." }
        }
        fun fetch(url: String, incidentId: String): MappingLayer {
            val uri = URI(url.trim())
            require(uri.scheme in listOf("http","https") && uri.host != null && uri.userInfo == null) { "Enter an HTTP(S) layer link from the mapping station." }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=10000;connection.readTimeout=20000
                require(connection.responseCode==200) { "Layer link is unavailable or expired. Get a new link from the station." }
                val bytes=connection.inputStream.use { stream ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
                    while(output.size()<=1024*1024) { val read=stream.read(buffer);if(read<0)break;output.write(buffer,0,read) }
                    output.toByteArray()
                }
                require(bytes.size<=1024*1024) { "Layer manifest is too large." }
                val value=JSONObject(String(bytes,Charsets.UTF_8))
                require(value.optString("type")=="r2c_mapping_layer" && value.optInt("version")==1 && value.optString("tileScheme")=="xyz") { "Unsupported 2D layer." }
                val layer=parse(value)
                require(layer.incidentId==incidentId) { "This layer belongs to another incident map." }
                return layer
            } finally { connection.disconnect() }
        }
        fun load(context: Context, incidentId: String): List<MappingLayer> {
            val text=context.getSharedPreferences("mapping_layers",0).getString(incidentId,"[]") ?: "[]"
            val hidden=context.getSharedPreferences("mapping_layers",0).getStringSet("hidden:$incidentId",emptySet()).orEmpty()
            return runCatching { val array=JSONArray(text);(0 until array.length()).map { parse(array.getJSONObject(it)) }.filterNot { it.id in hidden } }.getOrDefault(emptyList())
        }
        fun save(context: Context, incidentId: String, layers: List<MappingLayer>) {
            val array=JSONArray();layers.forEach { array.put(it.json()) }
            context.getSharedPreferences("mapping_layers",0).edit().putString(incidentId,array.toString()).apply()
        }
        fun visibility(context: Context, incidentId: String, layerId: String, visible: Boolean) {
            val preferences=context.getSharedPreferences("mapping_layers",0)
            val hidden=preferences.getStringSet("hidden:$incidentId",emptySet()).orEmpty().toMutableSet()
            if(visible)hidden.remove(layerId) else hidden.add(layerId)
            preferences.edit().putStringSet("hidden:$incidentId",hidden).apply()
        }
        /** Metadata only. No original photos or imagery are downloaded by discovery. */
        fun refreshShared(context: Context, incidentId: String): Boolean {
            val prefix=org.ncssar.rid2caltopo.data.CaltopoClient.GetTrackerUrlPfx().trimEnd('/')
            val token=org.ncssar.rid2caltopo.data.CaltopoClient.GetTrackerApiKey()
            if(prefix.isEmpty() || token.isEmpty() || incidentId.isEmpty())return false
            val request=okhttp3.Request.Builder().url("$prefix/api/v1/mapping-layers?incidentId=${java.net.URLEncoder.encode(incidentId,"UTF-8")}")
                .header("X-SAR-Token",token).header("X-R2C-Functionality-Release",org.ncssar.rid2caltopo.BuildConfig.TRACKER_FUNCTIONALITY_RELEASE.toString()).build()
            org.ncssar.rid2caltopo.data.CaltopoSession.MyOkHttpClient.newCall(request).execute().use { response ->
                if(response.code in listOf(401,403,404,503))return false
                if(!response.isSuccessful)return true
                val body=response.body ?: return true
                val bytes=body.byteStream().use { stream ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
                    while(output.size()<=1024*1024) { val count=stream.read(buffer);if(count<0)break;output.write(buffer,0,count) }
                    output.toByteArray()
                }
                if(bytes.size>1024*1024)return true
                val values=JSONArray(String(bytes,Charsets.UTF_8));val hidden=context.getSharedPreferences("mapping_layers",0).getStringSet("hidden:$incidentId",emptySet()).orEmpty()
                val discovered=(0 until values.length()).mapNotNull { index -> runCatching {
                    val descriptor=values.getJSONObject(index);val manifest=descriptor.getJSONObject("manifest")
                    manifest.put("tileUrlTemplate",descriptor.getString("tileUrlTemplate"));parse(manifest)
                }.getOrNull() }.filter { it.incidentId==incidentId && it.id !in hidden }
                val ids=discovered.map { it.id }.toSet()
                save(context,incidentId,load(context,incidentId).filterNot { it.id in ids }+discovered)
                return true
            }
        }
    }
}

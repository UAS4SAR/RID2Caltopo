package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.*

/** A qualified surface can survive interruption during the subsequent, slower tile export. */
internal object MappingSurfaceCheckpoint {
    data class Saved(val model:SurfaceAlignment,val surface:MappingSurface,val representation:JSONObject?)
    private val names=listOf("surface.json","surface.f32","surface.glb","cameras.json","sparse-points.ply")
    private fun key(mission:JSONObject,digests:List<String>)=LocalS3.hex(MessageDigest.getInstance("SHA-256")
        .digest(("surface-checkpoint-best-effort-v5"+mission.toString()+digests.sorted().joinToString()).toByteArray()))
    private fun geometryMission(mission:JSONObject)=JSONObject(mission.toString()).apply{remove("processingRegionBounds")}
    fun save(mission:JSONObject,digests:List<String>,out:File,representation:JSONObject,check:()->Unit) {
        val files=JSONArray(names.map { name->check();val f=File(out,"surface/$name");require(f.isFile)
            JSONObject().put("name",name).put("bytes",f.length()).put("sha256",MappingRegionProcessor.sha(f)) })
        MappingPhotoStore.writeJson(File(out,"geometry-checkpoint.json"),JSONObject().put("key",key(mission,digests))
            .put("geometryKey",key(geometryMission(mission),digests)).put("files",files).put("representation",representation))
        org.ncssar.rid2caltopo.storage.OperationalStorage.retainTree(out, check)
    }
    fun valid(mission:JSONObject,digests:List<String>,out:File,check:()->Unit):Boolean = runCatching {
        val record=JSONObject(File(out,"geometry-checkpoint.json").readText())
        val files=record.getJSONArray("files")
        (record.getString("key")==key(mission,digests)||record.optString("geometryKey",record.getString("key"))==key(geometryMission(mission),digests))&&
            record.optJSONObject("representation")!=null&&files.length()==names.size&&
            (0 until files.length()).all { i->check();val entry=files.getJSONObject(i);val name=entry.getString("name")
                val file=File(out,"surface/$name")
                name==names[i]&&file.isFile&&file.length()==entry.getLong("bytes")&&MappingRegionProcessor.sha(file)==entry.getString("sha256") }
    }.getOrElse { if(it is kotlinx.coroutines.CancellationException)throw it;false }
    fun load(mission:JSONObject,digests:List<String>,root:File,out:File,check:()->Unit):Saved? {
        if(!valid(mission,digests,out,check))return null
        val meta=JSONObject(File(out,"surface/surface.json").readText());val calibration=JSONObject(File(out,"surface/cameras.json").readText())
        val width=meta.getInt("width");val height=meta.getInt("height");require(width>1&&height>1&&width.toLong()*height<=3_000_000)
        val bytes=File(out,"surface/surface.f32").readBytes();require(bytes.size.toLong()==width.toLong()*height*4)
        val heights=FloatArray(width*height);ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(heights)
        val surface=MappingSurface(heights,width,height,meta.getDouble("gridX"),meta.getDouble("gridY"),meta.getDouble("cellMeters"),meta.getJSONObject("quality"))
        val originX=meta.getDouble("originMercatorX");val originY=meta.getDouble("originMercatorY");val altitude=meta.getDouble("originDjiAltitude");val scale=meta.getDouble("mercatorToGroundScale")
        val w=calibration.getInt("width");val h=calibration.getInt("height")
        val used=calibration.getJSONArray("originalSha256").let{a->(0 until a.length()).map{a.getString(it)}}
        require(used.size in 3..200&&digests.containsAll(used)&&used.distinct().size==used.size)
        val cameras=calibration.getJSONArray("camerasEnu").let{a->DoubleArray(a.length()){a.getDouble(it)}}
        val k=calibration.getJSONArray("intrinsicsPixels").let{a->DoubleArray(a.length()){a.getDouble(it)}}
        require(cameras.size==used.size*12&&k.size==5&&cameras.all(Double::isFinite)&&k.all(Double::isFinite))
        val frames=used.map { digest->check();val file=File(root,"originals/$digest.jpg")
            val pose=MappingPose.read(file,mission.getJSONObject("cameraSpecifications").getDouble("diagonalFOVDegrees"),true)
            SurfaceFrame(file,pose,w,h,doubleArrayOf((MappingGeometry.east(pose.longitude)-originX)*scale,(MappingGeometry.north(pose.latitude)-originY)*scale,pose.absoluteAltitude!!-altitude)) }
        // Full-resolution intrinsics: fullIntrinsics() consequently applies a scale of one.
        val model=SurfaceAlignment(frames,cameras,k,DoubleArray(0),originX,originY,altitude,scale,w,h,meta.getJSONObject("cameraAdjustment"))
        val record=JSONObject(File(out,"geometry-checkpoint.json").readText())
        // Output bounds do not affect adjustment/stereo. A changed crop rebuilds GLB/colors/tiles.
        val representation=record.getJSONObject("representation").takeIf{record.getString("key")==key(mission,digests)}
        return Saved(model,surface,representation)
    }
    fun stereoModel(saved:Saved,out:File):SurfaceAlignment {
        val model=saved.model
        val points=readSparsePoints(File(out,"surface/sparse-points.ply"))
        val divisor=generateSequence(1){it*2}.first{model.frames[0].width/it<=1400&&model.frames[0].height/it<=1400}
        val w=(model.frames[0].width+divisor-1)/divisor;val h=(model.frames[0].height+divisor-1)/divisor
        val scale=w.toDouble()/model.sampleWidth
        val k=model.intrinsic.copyOf().apply {this[0]*=scale;this[1]=(this[1]+.5)*scale-.5;this[2]=(this[2]+.5)*scale-.5}
        return model.copy(points=points,intrinsic=k,sampleWidth=w,sampleHeight=h)
    }
    internal fun readSparsePoints(file:File):DoubleArray {
        require(file.length() in 1..1_000_000){"Saved sparse geometry is unavailable; reconstruct from the originals."}
        val bytes=file.readBytes();val header=bytes.take(4096).toByteArray().toString(Charsets.US_ASCII)
        val end=header.indexOf("end_header\n")
        require(end>=0&&header.startsWith("ply\nformat binary_little_endian 1.0\n")){"Unsupported saved sparse geometry."}
        val count=Regex("element vertex (\\d+)\n").find(header.substring(0,end))?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val offset=end+"end_header\n".length
        require(count in 500..24000&&bytes.size-offset==count*12){"Saved sparse geometry is incomplete."}
        val values=ByteBuffer.wrap(bytes,offset,count*12).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return DoubleArray(count*3){values.get().toDouble()}.also {require(it.all(Double::isFinite)){"Saved sparse geometry contains invalid points."}}
    }
    fun representation(meta:JSONObject)=JSONObject().put("type","r2c_visible_surface").put("version",1)
        .put("modelPath","surface/surface.glb").put("surfacePath","surface/surface.f32")
        .put("vertexCount",meta.getInt("vertexCount")).put("triangleCount",meta.getInt("triangleCount"))
        .put("meshSpacingMeters",meta.getDouble("meshSpacingMeters")).put("surfaceCellMeters",meta.getDouble("cellMeters"))
        .put("quality","Stereo visible-surface heightfield. Includes roofs and canopy; unsupported gaps stay unknown.")
}

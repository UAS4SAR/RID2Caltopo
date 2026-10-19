package org.ncssar.rid2caltopo.mapping

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*

/** Opt-in saved-calibration experiment; writes independent artifacts, never replaces a layer. */
@RunWith(AndroidJUnit4::class)
class MappingStereoExperimentTest {
    private fun sha(file:File):String=MessageDigest.getInstance("SHA-256").let{digest->file.inputStream().buffered().use{stream->val block=ByteArray(65536);while(true){val n=stream.read(block);if(n<0)break;digest.update(block,0,n)}};LocalS3.hex(digest.digest())}
    @Test fun reconstructRetainedCalibrationWithPreservedStereoNeighbors() {
        val args=InstrumentationRegistry.getArguments();val id=args.getString("stereoBaseline");assumeTrue(id!=null)
        val experiment=args.getString("stereoExperiment")?:error("An explicit new experiment UUID is required.")
        require(UUID.fromString(experiment).toString()==experiment)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        require(MappingProcessingManager.state.value?.active!=true)
        val root=LocalMappingTiles.directory(context,id!!);val layer=JSONObject(File(root,"layer.json").readText())
        val mission=File(context.filesDir,"mapping-missions/${layer.getString("missionId")}")
        require(!JSONObject(File(mission,"processing-status.json").readText()).optBoolean("active"))
        val manifest=JSONObject(File(mission,"mission.json").readText());val metadata=JSONObject(File(root,"surface/surface.json").readText())
        val saved=JSONObject(File(root,"surface/cameras.json").readText())
        fun doubles(name:String)=saved.getJSONArray(name).let{a->DoubleArray(a.length()){a.getDouble(it)}}
        val cameras=doubles("camerasEnu");val k=doubles("intrinsicsPixels");val width=saved.getInt("width");val height=saved.getInt("height")
        val digests=saved.getJSONArray("originalSha256").let{a->(0 until a.length()).map{a.getString(it)}}
        val protected=root.walkTopDown().filter{it.isFile}.toList()+mission.walkTopDown().filter{it.isFile}.toList()
        val before=protected.associate{it to sha(it)}
        val fov=manifest.getJSONObject("cameraSpecifications").getDouble("diagonalFOVDegrees")
        val originX=metadata.getDouble("originMercatorX");val originY=metadata.getDouble("originMercatorY");val altitude=metadata.getDouble("originDjiAltitude");val scale=metadata.getDouble("mercatorToGroundScale")
        val frames=digests.map{digest->val file=File(mission,"originals/$digest.jpg");assertEquals(digest,sha(file));val pose=MappingPose.read(file,fov,allowOblique=true)
            SurfaceFrame(file,pose,width,height,doubleArrayOf((MappingGeometry.east(pose.longitude)-originX)*scale,(MappingGeometry.north(pose.latitude)-originY)*scale,pose.absoluteAltitude!!-altitude))}
        val ply=File(root,"surface/sparse-points.ply").readBytes();val headerEnd="end_header\n".toByteArray()
        val offset=(0..ply.size-headerEnd.size).first{i->headerEnd.indices.all{ply[i+it]==headerEnd[it]}}+headerEnd.size
        require((ply.size-offset)%12==0)
        val data=ByteBuffer.wrap(ply,offset,ply.size-offset).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val points=DoubleArray(data.remaining()){data.get().toDouble()}
        val sample=generateSequence(1){it*2}.first{width/it<=1400&&height/it<=1400};val sw=(width+sample-1)/sample;val sh=(height+sample-1)/sample
        val ratio=width.toDouble()/sw;val intrinsic=k.copyOf().apply{this[0]/=ratio;this[1]=(this[1]+.5)/ratio-.5;this[2]=(this[2]+.5)/ratio-.5}
        val model=SurfaceAlignment(frames,cameras,intrinsic,points,originX,originY,altitude,scale,sw,sh,JSONObject(layer.getJSONObject("cameraAdjustment").toString()))
        val pairs=MappingStereoPairs.select(frames.indices.map{i->MappingStereoPairs.Camera(model.centre(i),cameras.copyOfRange(i*12+6,i*12+9),frames[i].pose.pitch)})
        val nadir=frames.indices.filter{abs(frames[it].pose.pitch+90)<3}
        val within=MappingStereoPairs.select(nadir.map{i->MappingStereoPairs.Camera(model.centre(i),cameras.copyOfRange(i*12+6,i*12+9),frames[i].pose.pitch)}).map{(i,j)->min(nadir[i],nadir[j]) to max(nadir[i],nadir[j])}
        assertTrue(pairs.containsAll(within));assertEquals(66,nadir.size)
        val out=File(context.filesDir,"mapping-experiments/$experiment");require(!out.exists());out.mkdirs()
        val raw=mutableListOf<File>();var cancelled=false
        val wake=context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"R2C:StereoExperiment").apply{acquire(35*60*1000L)}
        val lease=MappingWorkRuntime.begin({cancelled=true},true);val start=System.nanoTime()
        val check={check(!cancelled)}
        val progress:(String,Double)->Unit={stage,fraction->MappingPhotoStore.writeJson(File(out,"progress.json"),JSONObject().put("stage",stage).put("fraction",fraction));android.util.Log.i("MappingStereoExperiment",stage);Unit}
        try {
            require(OpenCVLoader.initLocal());Core.setNumThreads(2);MappingSurfaceNative.requireAvailable()
            val surface=MappingDenseSurface.run(model,progress,check)
            assertEquals(metadata.getInt("width"),surface.width);assertEquals(metadata.getInt("height"),surface.height)
            assertEquals(metadata.getDouble("gridX"),surface.x,1e-9);assertEquals(metadata.getDouble("gridY"),surface.y,1e-9)
            val ring=manifest.getJSONObject("feature").getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
            val coverage=MappingSurfaceCoverage((0 until ring.length()).map{i->val p=ring.getJSONArray(i);((MappingGeometry.east(p.getDouble(0))-originX)*scale) to ((MappingGeometry.north(p.getDouble(1))-originY)*scale)})
            val cells=coverage.cells(surface.heights,surface.width,surface.height,surface.x,surface.y,surface.cell)
            assertTrue(cells.fraction>.5)
            surface.report.put("assignmentSurfaceCells",cells.total).put("knownAssignmentSurfaceCells",cells.known).put("assignmentKnownSurfaceFraction",cells.fraction)
            frames.forEachIndexed{i,f->val bitmap=BitmapFactory.decodeFile(f.file.path,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888})!!
                val file=File(out,"working-$i.raw");raw.add(file)
                try{val bytes=ByteBuffer.allocate(bitmap.byteCount);bitmap.copyPixelsToBuffer(bytes);file.outputStream().buffered().use{it.write(bytes.array())}}finally{bitmap.recycle()}}
            val representation=MappingSurfaceExport.write(model,surface,raw.map{it.path}.toTypedArray(),out,progress,check)
            assertEquals(.4,representation.getDouble("meshSpacingMeters"),1e-9)
            val provenance=JSONObject().put("sourceLayerId",id).put("savedCameraCalibrationReused",true).put("sparseTracksSource","retained float32 PLY promoted to double; no new camera fitting")
                .put("nadirSubsetPairsRetained",within.size).put("candidatePairs",pairs.size).put("seconds",(System.nanoTime()-start)/1_000_000_000)
            layer.put("surfaceReconstruction",surface.report).put("surfaceModel",representation).put("experiment",provenance)
            MappingPhotoStore.writeJson(File(out,"layer.json"),layer);MappingPhotoStore.writeJson(File(out,"mission.json"),manifest)
            MappingPhotoStore.writeJson(File(out,"result.json"),provenance.put("surface",surface.report))
            before.forEach{(file,digest)->assertEquals(file.path,digest,sha(file))}
            android.util.Log.i("MappingStereoExperiment","PASS: independent artifact $experiment; source mission and layer unchanged")
        } finally {MappingSurfaceNative.releasePixels();raw.forEach{it.delete()};MappingWorkRuntime.finish(lease);if(wake.isHeld)wake.release()}
    }
}

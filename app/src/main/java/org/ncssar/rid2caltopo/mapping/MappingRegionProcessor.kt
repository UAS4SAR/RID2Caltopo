package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.*

/** Bounded independent RTK regions. Checkpoints include all output hashes, never raw working files. */
internal object MappingRegionProcessor {
    private const val VERSION="regions-v13-best-effort-mesh04"
    fun run(context:Context,mission:JSONObject,digests:List<String>,root:File,out:File,progress:(String,Double)->Unit,check:()->Unit):JSONObject {
        val plan=plan(mission,digests,root,check)
        val regions=plan.regions;val commonMission=plan.mission
        val fingerprint=hash((VERSION+mission.toString()+digests.sorted().joinToString()).toByteArray())
        val work=File(out,"regions").apply{mkdirs()}
        val results=mutableListOf<JSONObject>();var resumed=0
        val regionManifest=JSONArray()
        regions.forEachIndexed { i,region ->
            check();val block=File(work,"$i").apply{mkdirs()};val marker=File(block,"checkpoint.json")
            val regionMission=regionMission(commonMission,region)
            progress("Checking saved region ${i+1}/${regions.size}",.04+.88*i/regions.size)
            val old=runCatching{JSONObject(marker.readText())}.getOrNull()
            val valid=runCatching { old?.optString("fingerprint")==fingerprint && old.optJSONObject("report")!=null && old.optJSONArray("files")?.let{files->
                val paths=(0 until files.length()).map{files.getJSONObject(it).getString("path")}
                val required=listOf("surface/surface.glb","surface/surface.json","surface/cameras.json","surface/surface.f32")
                paths.distinct().size==paths.size && paths.containsAll(required) && paths.any{it.endsWith(".png")} &&
                (0 until files.length()).all { j -> check();progress("Verifying saved region ${i+1}/${regions.size} · file ${j+1}/${files.length()}",.04+.88*i/regions.size);val r=files.getJSONObject(j);val file=File(block,r.getString("path"))
                    file.canonicalPath.startsWith(block.canonicalPath+"/")&&file.isFile&&file.length()==r.getLong("bytes")&&MappingFileProgress.sha(file,check){bytes->progress("Verifying saved region ${i+1}/${regions.size} · file ${j+1}/${files.length()} · ${MappingFileProgress.label(bytes,file.length())}",.04+.88*i/regions.size)}==r.getString("sha256") }
            }==true }.getOrElse { if(it is kotlinx.coroutines.CancellationException)throw it;false }
            val report=if(valid){resumed++;progress("Reusing verified region ${i+1}/${regions.size}",.04+.88*(i+1)/regions.size);old!!.getJSONObject("report")} else {
                // Only this unfinished block is discarded; successful regions remain resumable.
                if(!MappingSurfaceCheckpoint.valid(regionMission,region.images.map{it.digest},block,check)){
                    block.deleteRecursively();block.mkdirs()
                    // Small flights can cover several acreage cores with the same image set.
                    // Reuse their solved geometry, then rebuild only this core's colors/tiles.
                    MappingRegionPlan.geometrySource(regions,i)?.let { previous->val source=File(work,"$previous")
                        if(MappingSurfaceCheckpoint.valid(regionMission,region.images.map{it.digest},source,check)) {
                            progress("Reusing camera solution from region ${previous+1} for region ${i+1}",.04+.88*i/regions.size)
                            MappingFileProgress.copyTree(File(source,"surface"),File(block,"surface"),"Copying saved geometry",check){stage->progress(stage,.04+.88*i/regions.size)}
                            File(source,"geometry-checkpoint.json").copyTo(File(block,"geometry-checkpoint.json"),overwrite=true)
                        }
                    }
                }
                val result=try { MappingSurfaceStitcher.run(context,regionMission,region.images.map{it.digest},root,block,
                    {stage,f->progress("Region ${i+1}/${regions.size} · $stage",.04+.88*(i+f)/regions.size)},check)
                } catch(e:kotlinx.coroutines.CancellationException){throw e}
                  catch(e:Exception){throw IllegalStateException("Region ${i+1}/${regions.size} stopped: ${e.message}. $i completed regions are saved; retry resumes them.",e)}
                val files=JSONArray();val savedFiles=block.walkTopDown().filter{it.isFile&&it!=marker}.toList()
                val totalBytes=savedFiles.sumOf{it.length()};var savedBytes=0L
                savedFiles.forEachIndexed{index,file->check()
                    val digest=MappingFileProgress.sha(file,check){bytes->progress("Saving region ${i+1}/${regions.size} · file ${index+1}/${savedFiles.size} · ${MappingFileProgress.label(savedBytes+bytes,totalBytes)}",.04+.88*(i+1)/regions.size)}
                    files.put(JSONObject().put("path",file.relativeTo(block).invariantSeparatorsPath).put("bytes",file.length()).put("sha256",digest));savedBytes+=file.length()
                }
                MappingPhotoStore.writeJson(marker,JSONObject().put("fingerprint",fingerprint).put("report",result).put("files",files))
                result
            }
            results.add(report)
            regionManifest.put(JSONObject().put("index",i).put("title","Region ${i+1} of ${regions.size}")
                .put("path","regions/$i/surface.glb").put("bounds",report.getJSONArray("bounds"))
                .put("photoCount",report.getInt("photoCount")).put("coreAcres",region.bounds?.area(groundScale(mission))?.div(MappingRegionPlan.ACRE))
                .put("quality",report.getString("quality")))
            progress("Saved region ${i+1}/${regions.size} on microSD",.04+.88*(i+1)/regions.size)
        }
        // Copy one bounded region's model at a time. Keep checkpoints until publication succeeds.
        val surface=File(out,"surface")
        // Assembly can be retried with a new region layout; do not package obsolete models.
        require(!surface.exists()||surface.deleteRecursively()){ "Cannot replace the previous regional assembly." }
        require(surface.mkdirs()) { "Cannot create the regional surface collection." }
        regions.indices.forEach { i ->
            check();MappingFileProgress.copyTree(File(work,"$i/surface"),File(surface,"regions/$i"),"Assembling 3D region ${i+1}/${regions.size}",check){stage->progress(stage,.92)}
        }
        MappingPhotoStore.writeJson(File(surface,"collection.json"),JSONObject().put("version",1).put("regions",regionManifest).put("meshSpacingMeters",.4)
            .put("targetCoreAcres",MappingRegionPlan.TARGET_ACRES).put("maximumCoreAcres",MappingRegionPlan.MAX_CORE_ACRES).put("outputOverlapMeters",MappingRegionPlan.OUTPUT_OVERLAP_METERS))
        // Rebuild the mosaic deterministically; interruption never appends duplicate tiles.
        File(out,"mosaic-weights").deleteRecursively()
        out.listFiles().orEmpty().filter{it.isDirectory&&it.name.matches(Regex("[0-9]+"))}.forEach{it.deleteRecursively()}
        regions.forEachIndexed { i,region ->
            val tiles=File(work,"$i").walkTopDown().filter{it.isFile&&it.extension=="png"}.toList()
            tiles.forEachIndexed { tileIndex,file ->
                check();val relative=file.relativeTo(File(work,"$i")).invariantSeparatorsPath
                if(!relative.matches(Regex("[0-9]+/[0-9]+/[0-9]+\\.png")))return@forEachIndexed
                progress("Assembling 2D region ${i+1}/${regions.size} · tile ${tileIndex+1}/${tiles.size}",.92+.03*(i+tileIndex.toDouble()/tiles.size)/regions.size)
                val dst=File(out,relative);dst.parentFile!!.mkdirs()
                merge(file,dst,region,relative)
            }
            progress("Assembling map region ${i+1}/${regions.size}",.92+.03*(i+1)/regions.size)
        }
        File(out,"mosaic-weights").deleteRecursively()
        val allBounds=results.map{it.getJSONArray("bounds")}
        val supported=mutableSetOf<String>()
        regions.indices.forEach { i->val a=JSONObject(File(work,"$i/surface/cameras.json").readText()).getJSONArray("originalSha256");(0 until a.length()).forEach{supported.add(a.getString(it))} }
        val scaleWarning=if(results.any{it.optString("scaleMode")=="nominal-calibration"})MappingPositionQuality.SCALE_WARNING else ""
        val approximateRegions=results.count{it.optString("reconstructionMode")=="approximate-plane"}
        val lowerConfidence=results.any{it.optString("positioningMode")=="gnss-assisted"}
        val report=JSONObject(results.first().toString()).put("positioningMode",if(lowerConfidence)"gnss-assisted" else "fixed-rtk").put("positioningWarning",if(lowerConfidence)MappingPositionQuality.WARNING else "").put("scaleWarning",scaleWarning).put("scaleMode",if(scaleWarning.isEmpty())"laser-anchored" else "nominal-calibration")
        // Per-region percentages must not be reported as whole-assignment coverage.
        listOf("cameraAdjustment","surfaceReconstruction","assignmentImageCoverageFraction","assignmentProjectedImagePixels","projectedImagePixels","excludedPhotos","resumedGeometry").forEach{report.remove(it)}
        return report.put("reconstructionMode",if(approximateRegions>0)"approximate-plane" else "measured-surface").put("approximateRegionCount",approximateRegions).put("photogrammetric3D",approximateRegions==0).put("photoCount",supported.size).put("selectedPhotoCount",digests.size).put("excludedPhotoCount",digests.size-supported.size)
            .put("captureTimeLocal",results.minOf{it.getString("captureTimeLocal")}).put("captureEndTimeLocal",results.maxOf{it.getString("captureEndTimeLocal")})
            .put("outputTileRectangleCount",results.sumOf{it.optLong("outputTileRectangleCount")})
            .put("tileProjectionSeconds",results.sumOf{it.optDouble("tileProjectionSeconds",0.0)})
            .put("resumedGeometryRegionCount",results.count{it.optBoolean("resumedGeometry")})
            .put("resumedRegionCount",resumed).put("regionCount",regions.size).put("regionReports",JSONArray(results)).put("engine",VERSION)
            .put("bounds",JSONArray(listOf(allBounds.minOf{it.getDouble(0)},allBounds.minOf{it.getDouble(1)},allBounds.maxOf{it.getDouble(2)},allBounds.maxOf{it.getDouble(3)})))
            .put("surfaceModel",JSONObject().put("type","r2c_surface_collection").put("path","surface/collection.json").put("regionCount",regions.size).put("meshSpacingMeters",.4))
            .put("quality",(if(approximateRegions>0)"$approximateRegions regions use approximate reference planes, not measured depth. Position and height accuracy are unknown. " else "")+"${supported.size} of ${digests.size} originals support ${regions.size} overlapping reconstructed regions. View 3D one region at a time. Regions are independently adjusted; inspect their boundaries. Unknown depth remains transparent. "+(if(lowerConfidence)MappingPositionQuality.WARNING else "")+" "+scaleWarning)
    }
    internal data class Plan(val regions:List<MappingRegionPlan.Region>,val mission:JSONObject)
    private fun ring(mission:JSONObject)=mission.getJSONObject("feature").getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
    private fun groundScale(mission:JSONObject)=cos(Math.toRadians(ring(mission).let{r->(0 until r.length()).map{r.getJSONArray(it).getDouble(1)}.average()}))
    private fun bounds(mission:JSONObject):MappingRegionPlan.Bounds {
        val r=ring(mission);val xs=(0 until r.length()).map{MappingGeometry.east(r.getJSONArray(it).getDouble(0))}
        val ys=(0 until r.length()).map{MappingGeometry.north(r.getJSONArray(it).getDouble(1))}
        return MappingRegionPlan.Bounds(xs.min(),ys.min(),xs.max(),ys.max())
    }
    fun needsRegions(mission:JSONObject,photos:Int)=photos>MappingRegionPlan.LIMIT||bounds(mission).area(groundScale(mission))>MappingRegionPlan.MAX_CORE_ACRES*MappingRegionPlan.ACRE
    internal fun singleMission(mission:JSONObject)=regionMission(mission,MappingRegionPlan.Region(emptyList(),emptyList(),bounds(mission)))
    internal fun regionMission(common:JSONObject,region:MappingRegionPlan.Region):JSONObject {
        val result=JSONObject(common.toString());val b=region.bounds?.expanded(MappingRegionPlan.OUTPUT_OVERLAP_METERS/groundScale(common))?:return result
        return result.put("processingRegionBounds",JSONArray(listOf(b.west,b.south,b.east,b.north)))
    }
    internal fun plan(mission:JSONObject,digests:List<String>,root:File,check:()->Unit):Plan {
        val fov=mission.getJSONObject("cameraSpecifications").getDouble("diagonalFOVDegrees")
        val photos=digests.map { digest ->
            check();val p=MappingPose.read(File(root,"originals/$digest.jpg"),fov,true)
            // Partition by optical centre, so crossing obliques accompany the ground they see.
            val scale=cos(Math.toRadians(p.latitude));val offset=p.height*tan(Math.toRadians(90+p.pitch))
            MappingRegionPlan.Photo(digest,MappingGeometry.east(p.longitude)+sin(Math.toRadians(p.yaw))*offset/scale,
                MappingGeometry.north(p.latitude)+cos(Math.toRadians(p.yaw))*offset/scale)
        }
        val polygon=ring(mission).let{r->MappingSurfaceCoverage((0 until r.length()).map { i->
            MappingGeometry.east(r.getJSONArray(i).getDouble(0)) to MappingGeometry.north(r.getJSONArray(i).getDouble(1)) })}
        val regions=MappingRegionPlan.build(photos,bounds(mission),groundScale(mission)).filter { polygon.clipped(it.bounds!!)!=null }
        require(regions.isNotEmpty()) { "No processing region intersects the assignment." }
        val commonMission=JSONObject(mission.toString()).put("processingZoom",MappingGeometry.zoom(
            mission.getJSONObject("parameters").getDouble("desiredGroundResolutionCmPerPixel")/100,
            photos.map{MappingGeometry.latitude(it.y)}.average()))
        return Plan(regions,commonMission)
    }
    private fun merge(source:File,destination:File,region:MappingRegionPlan.Region,path:String) {
        val parts=path.split('/');val z=parts[0].toInt();val tx=parts[1].toInt();val ty=parts[2].removeSuffix(".png").toInt()
        val a=BitmapFactory.decodeFile(source.path)?:error("Unreadable regional tile")
        val b=if(destination.isFile)BitmapFactory.decodeFile(destination.path)?:error("Unreadable mosaic tile") else null
        val weight=File(destination.parentFile!!.parentFile!!.parentFile!!,"mosaic-weights/$path.bin")
        val distances=FloatArray(65536){Float.POSITIVE_INFINITY}
        if(weight.isFile)DataInputStream(weight.inputStream().buffered()).use{input->distances.indices.forEach{distances[it]=input.readFloat()}}
        try {
            val fresh=IntArray(65536);val old=IntArray(65536);a.getPixels(fresh,0,256,0,0,256,256);b?.getPixels(old,0,256,0,0,256,256)
            val unit=MappingGeometry.WORLD/(256.0*(1 shl z))
            for(row in 0..255)for(col in 0..255) {val n=row*256+col;if(fresh[n] ushr 24==0)continue
                val x=(tx*256.0+col+.5)*unit-MappingGeometry.WORLD/2;val y=MappingGeometry.WORLD/2-(ty*256.0+row+.5)*unit
                val distance=(x-region.x).pow(2)+(y-region.y).pow(2)
                if(old[n] ushr 24==0||distance<distances[n]){old[n]=fresh[n];distances[n]=distance.toFloat()}
            }
            weight.parentFile!!.mkdirs()
            DataOutputStream(weight.outputStream().buffered()).use{output->distances.forEach{output.writeFloat(it)}}
            val image=Bitmap.createBitmap(old,256,256,Bitmap.Config.ARGB_8888)
            try{destination.outputStream().use{require(image.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{image.recycle()}
        }finally{a.recycle();b?.recycle()}
    }
    internal fun sha(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use { stream->val bytes=ByteArray(65536);while(true){val n=stream.read(bytes);if(n<0)break;digest.update(bytes,0,n)} };return LocalS3.hex(digest.digest())}
    private fun hash(bytes:ByteArray)=LocalS3.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

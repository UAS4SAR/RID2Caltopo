package org.ncssar.rid2caltopo.mapping

import android.content.Context
import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.*

/** One local reconstruction produces a retained 3D DSM/GLB and surface-corrected XYZ tiles. */
internal object MappingSurfaceStitcher {
    fun run(context:Context,mission:JSONObject,digests:List<String>,root:File,out:File,progress:(String,Double)->Unit,check:()->Unit):JSONObject {
        require(OpenCVLoader.initLocal());Core.setNumThreads(2);MappingSurfaceNative.requireAvailable()
        require(mission.getString("profile")=="daylight-rgb")
        val restored=MappingSurfaceCheckpoint.load(mission,digests,root,out,check)
        if(restored!=null)progress("Reusing verified 3D geometry · preparing 2D tiles",.68)
        if(restored!=null&&restored.representation==null)out.listFiles().orEmpty()
            .filter{it.isDirectory&&it.name.matches(Regex("[0-9]+"))}.forEach{check();it.deleteRecursively()}
        val model=restored?.let { saved ->
            if(saved.surface.report.optString("heightFusion")=="supported-upper-v3")saved.model
            else MappingSurfaceCheckpoint.stereoModel(saved,out)
        } ?: MappingSurfaceAlignment.run(mission,digests,root,progress,check)
        if(mission.has("processingZoom")||mission.has("processingRegionBounds"))model.report.put("regionalProcessing",true)
        val region=mission.optJSONArray("processingRegionBounds")?.let { b->MappingRegionPlan.Bounds(
            (b.getDouble(0)-model.originX)*model.mercatorScale,(b.getDouble(1)-model.originY)*model.mercatorScale,
            (b.getDouble(2)-model.originX)*model.mercatorScale,(b.getDouble(3)-model.originY)*model.mercatorScale) }
        region?.let{model.report.put("processingRegionBoundsEnu",JSONArray(listOf(it.west,it.south,it.east,it.north)))}
        val surface=restored?.surface?.takeIf{it.report.optString("heightFusion")=="supported-upper-v3"} ?: MappingDenseSurface.run(model,progress,check)
        val refined=surface.report.optString("refinement")!="local-median-v1"
        val needsExport=refined||surface.report.optString("projection")!="ray-coverage-photo-seams-v5"
        if(refined){check();progress("Refining local surface heights",.68)
            MappingSurfaceNative.refine(surface.heights,surface.width,surface.height,surface.cell)
            surface.report.put("refinement","local-median-v1")
        }
        if(needsExport){
            surface.report.put("projection","ray-coverage-photo-seams-v5")
            // Changed projection invalidates old tiles, including tiles that become empty.
            out.listFiles().orEmpty().filter{it.isDirectory&&it.name.matches(Regex("[0-9]+"))}
                .forEach{check();require(it.deleteRecursively()){ "Cannot replace previous surface tiles." }}
        }
        val ring=mission.getJSONObject("feature").getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        model.report.put("assignmentBoundary",JSONArray((0 until ring.length()).map{i->val p=ring.getJSONArray(i);listOf(p.getDouble(0),p.getDouble(1))}))
        val fullAssignment=MappingSurfaceCoverage((0 until ring.length()).map{i->val p=ring.getJSONArray(i)
            ((MappingGeometry.east(p.getDouble(0))-model.originX)*model.mercatorScale) to ((MappingGeometry.north(p.getDouble(1))-model.originY)*model.mercatorScale)})
        val assignment=if(region==null)fullAssignment else fullAssignment.clipped(region)?:error("Region does not intersect the assignment.")
        val assignmentCells=assignment.cells(surface.heights,surface.width,surface.height,surface.x,surface.y,surface.cell)
        surface.report.put("assignmentSurfaceCells",assignmentCells.total).put("knownAssignmentProjectionCells",assignmentCells.known).put("knownAssignmentSurfaceCells",if(model.report.optString("reconstructionMode")=="approximate-plane")0 else assignmentCells.known)
            .put("assignmentKnownSurfaceFraction",if(model.report.optString("reconstructionMode")=="approximate-plane")0.0 else assignmentCells.fraction)
            .put("boundingRectangleKnownSurfaceFraction",surface.heights.count(Float::isFinite).toDouble()/surface.heights.size)
        MappingPhotoStore.writeJson(File(root,"surface-reconstruction-report.json"),JSONObject().put("cameraAdjustment",model.report).put("surface",surface.report))
        require(assignmentCells.known>0){"No recoverable surface lies within the assignment. Originals are retained."}
        val raw=mutableListOf<File>()
        try{
            model.frames.forEachIndexed{i,f->check();progress("Preparing original image colors ${i+1}/${model.frames.size}",.68+.01*i/model.frames.size)
                require(f.width.toLong()*f.height*8<Runtime.getRuntime().maxMemory()*.7)
                val bitmap=BitmapFactory.decodeFile(f.file.path,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inPreferredColorSpace=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)})?:error("Cannot decode a surface original.")
                val file=File(out,"working-surface-$i.raw");raw.add(file)
                try{val bytes=ByteBuffer.allocate(bitmap.byteCount);bitmap.copyPixelsToBuffer(bytes);file.outputStream().buffered().use{it.write(bytes.array())}}finally{bitmap.recycle()}}
            val paths=raw.map{it.path}.toTypedArray()
            MappingSurfaceNative.prepareProjection(surface.heights,surface.width,surface.height,surface.x,surface.y,surface.cell,
                model.cameras,MappingSurfaceExport.fullIntrinsics(model),model.frames[0].width,model.frames[0].height,paths,
                object:MappingSurfaceNative.Progress {override fun iteration(iteration:Int,cost:Double):Boolean {
                    check();progress("Matching photo seams · ${(cost*100).toInt()}%",.69+.01*cost);return true
                }})
            val representation=(if(needsExport)null else restored?.representation) ?: MappingSurfaceExport.write(model,surface,paths,out,progress,check).also {
                MappingSurfaceCheckpoint.save(mission,digests,out,it,check)
            }
            check();progress("3D surface ready · projecting the 2D map",.73)
            val projectionStarted=System.nanoTime()
            val lat=MappingGeometry.latitude(model.originY);val k=MappingSurfaceExport.fullIntrinsics(model)
            val medianHeight=surface.heights.filter(Float::isFinite).sorted().let{it[it.size/2]}.toDouble()
            val gsd=model.frames.map{(it.centre[2]-medianHeight)/k[0]}.sorted().let{it[it.size/2]}
            val zoom=mission.optInt("processingZoom",MappingGeometry.zoom(gsd,lat));require(zoom in 14..23);val factor=256.0*(1 shl zoom)/MappingGeometry.WORLD
            val west=model.originX+max(surface.x,region?.west ?: surface.x)/model.mercatorScale
            val east=model.originX+min(surface.x+(surface.width-1)*surface.cell,region?.east ?: Double.POSITIVE_INFINITY)/model.mercatorScale
            val south=model.originY+max(surface.y,region?.south ?: surface.y)/model.mercatorScale
            val north=model.originY+min(surface.y+(surface.height-1)*surface.cell,region?.north ?: Double.POSITIVE_INFINITY)/model.mercatorScale
            require(east>west&&north>south){"No recovered surface intersects this region."}
            val x0=floor((west+MappingGeometry.WORLD/2)*factor/256).toInt();val x1=floor((east+MappingGeometry.WORLD/2)*factor/256).toInt()
            val y0=floor((MappingGeometry.WORLD/2-north)*factor/256).toInt();val y1=floor((MappingGeometry.WORLD/2-south)*factor/256).toInt()
            val count=(x1.toLong()-x0+1)*(y1-y0+1);require(count in 1..16000){"Surface tile extent exceeds this tablet's job limit."}
            require(out.usableSpace>count*700000L+MappingPhotoStore.FREE_SPACE_RESERVE){"Not enough storage for the corrected layer and package."}
            val highest=mutableListOf<Long>();val pixels=IntArray(256*256);var tile=0;var coverage=0L;var assignmentPixels=0L
            for(x in x0..x1)for(y in y0..y1){check();progress("Projecting corrected 2D tile ${++tile}/$count",.73+.17*tile/count)
                val wx=((x*256.0/factor-MappingGeometry.WORLD/2)-model.originX)*model.mercatorScale
                val wy=((MappingGeometry.WORLD/2-y*256.0/factor)-model.originY)*model.mercatorScale
                var visible=MappingSurfaceNative.render(surface.heights,surface.width,surface.height,surface.x,surface.y,surface.cell,model.cameras,k,model.frames[0].width,model.frames[0].height,paths,wx,wy,model.mercatorScale/factor,pixels)
                if(region!=null){val step=model.mercatorScale/factor
                    for(row in 0..255)for(col in 0..255)if(wx+(col+.5)*step !in region.west..region.east||wy-(row+.5)*step !in region.south..region.north)pixels[row*256+col]=0
                    visible=pixels.count{it!=0}
                }
                if(visible<1)continue;coverage+=visible;assignmentPixels+=assignment.imagePixels(pixels,wx,wy,model.mercatorScale/factor);highest.add(MapTileIndex.getTileIndex(zoom,x,y))
                val bitmap=Bitmap.createBitmap(pixels,256,256,Bitmap.Config.ARGB_8888)
                try{png(bitmap,File(out,"$zoom/$x/$y.png"))}finally{bitmap.recycle()}
            }
            val assignmentImageFraction=(assignmentPixels*(model.mercatorScale/factor).pow(2)/assignment.area).coerceAtMost(1.0)
            require(highest.isNotEmpty()&&assignmentPixels>0){"No corrected image coverage lies within the assignment. Originals are retained."}
            var children=highest.toList();val paint=Paint(Paint.FILTER_BITMAP_FLAG)
            for(level in zoom-1 downTo 14){check();progress("Building corrected map zoom levels",.90+.05*(zoom-level)/(zoom-14))
                val parents=children.map{MapTileIndex.getTileIndex(level,MapTileIndex.getX(it)/2,MapTileIndex.getY(it)/2)}.distinct()
                parents.forEach{index->check();val x=MapTileIndex.getX(index);val y=MapTileIndex.getY(index);val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
                    try{val canvas=Canvas(bitmap);for(dx in 0..1)for(dy in 0..1){val f=File(out,"${level+1}/${x*2+dx}/${y*2+dy}.png");if(f.isFile){val child=BitmapFactory.decodeFile(f.path)?:error("Cannot decode surface tile.");try{canvas.drawBitmap(child,null,Rect(dx*128,dy*128,dx*128+128,dy*128+128),paint)}finally{child.recycle()}}};png(bitmap,File(out,"$level/$x/$y.png"))}finally{bitmap.recycle()}}
                children=parents
            }
            val captured=model.frames.map{it.pose.capturedAt}.sorted()
            val excluded=model.report.getInt("excludedPhotoCount")
            val approximate=model.report.optString("reconstructionMode")=="approximate-plane"
            return JSONObject().put("resumedGeometry",restored!=null).put("photoCount",model.frames.size).put("selectedPhotoCount",digests.size).put("excludedPhotoCount",excluded)
                .put("outputTileRectangleCount",count).put("tileProjectionSeconds",(System.nanoTime()-projectionStarted)/1_000_000_000.0)
                .put("assignmentBoundary",JSONArray((0 until ring.length()).map{i->val p=ring.getJSONArray(i);listOf(p.getDouble(0),p.getDouble(1))}))
                .put("excludedPhotos",model.report.getJSONArray("excludedPhotos")).put("profile","daylight-rgb").put("captureTimeLocal",captured.first()).put("captureEndTimeLocal",captured.last()).put("captureTimeZone","camera local time; zone unknown")
                .put("groundResolutionCmPerPixel",gsd*100).put("minZoom",14).put("maxZoom",zoom)
                .put("bounds",JSONArray(listOf(MappingGeometry.longitude(west),MappingGeometry.latitude(south),MappingGeometry.longitude(east),MappingGeometry.latitude(north))))
                .put("scaleMode",model.report.optString("scaleMode")).put("scaleWarning",model.report.optString("scaleWarning")).put("engine",model.report.getString("engine")).put("reconstructionMode",if(approximate)"approximate-plane" else "measured-surface").put("quality",(if(approximate)"Approximate photo map: depth could not be recovered. Heights use an estimated reference plane; the 3D preview is flat, not a measured surface. ${(assignmentImageFraction*100).toInt()}% image coverage. Position and height accuracy are unknown. " else "")+(if(excluded>0)"${model.frames.size} of ${digests.size} selected photos support this result; $excluded lacked sufficient connected 3D support and remain retained. " else "")+(if(approximate)"" else "${(assignmentCells.fraction*100).toInt()}% of assignment surface has supported heights. Image-verified stereo surface projection. Includes roofs and canopy; unknown depth stays transparent. Review local seams. Pixel detail does not establish surveyed accuracy. ")+model.report.optString("positioningWarning")+" "+model.report.optString("scaleWarning"))
                .put("positioningMode",model.report.optString("positioningMode","fixed-rtk")).put("positioningWarning",model.report.optString("positioningWarning"))
                .put("photogrammetric3D",!approximate).put("surfaceModel",representation).put("cameraAdjustment",model.report).put("surfaceReconstruction",surface.report).put("projectedImagePixels",coverage)
                .put("assignmentProjectedImagePixels",assignmentPixels).put("assignmentImageCoverageFraction",assignmentImageFraction)
        }finally{MappingSurfaceNative.releasePixels();raw.forEach{it.delete()}}
    }
    private fun png(bitmap:Bitmap,file:File){file.parentFile!!.mkdirs();file.outputStream().buffered().use{require(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
}

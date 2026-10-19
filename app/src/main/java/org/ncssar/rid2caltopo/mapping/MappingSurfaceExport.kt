package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Retain camera/DSM geometry and an interoperable colored GLB for local 3D inspection. */
internal object MappingSurfaceExport {
    fun fullIntrinsics(model:SurfaceAlignment):DoubleArray {
        val scale=model.frames[0].width.toDouble()/model.sampleWidth
        return model.intrinsic.copyOf().apply{this[0]*=scale;this[1]=(this[1]+.5)*scale-.5;this[2]=(this[2]+.5)*scale-.5}
    }
    fun write(model:SurfaceAlignment,surface:MappingSurface,paths:Array<String>,out:File,progress:(String,Double)->Unit,check:()->Unit):JSONObject {
        val directory=File(out,"surface").apply{mkdirs()};val k=fullIntrinsics(model)
        val metadata=JSONObject().put("reconstructionMode",model.report.optString("reconstructionMode","measured-surface")).put("type","r2c_visible_surface").put("version",1).put("width",surface.width).put("height",surface.height)
            .put("title",model.report.optString("missionTitle","Mission")+" · Visible").put("captureTimeLocal",model.frames.map{it.pose.capturedAt}.min()).put("photoCount",model.frames.size)
            .put("gridX",surface.x).put("gridY",surface.y).put("cellMeters",surface.cell).put("byteOrder","little-endian").put("noData","NaN")
            .put("originMercatorX",model.originX).put("originMercatorY",model.originY).put("mercatorToGroundScale",model.mercatorScale)
            .put("originDjiAltitude",model.originAltitude).put("quality",surface.report).put("cameraAdjustment",model.report)
        model.report.optJSONArray("assignmentBoundary")?.let{metadata.put("assignmentBoundary",it)}
        MappingPhotoStore.writeJson(File(directory,"surface.json"),metadata)
        val finite=model.points.toList().chunked(3).filter{it.all(Double::isFinite)}
        // Restored geometry keeps its already-verified sparse artifact while regenerating a crop.
        if(model.points.isNotEmpty()||!File(directory,"sparse-points.ply").isFile)File(directory,"sparse-points.ply").outputStream().buffered().use{stream->
            stream.write("ply\nformat binary_little_endian 1.0\ncomment R2C local ENU; see surface.json for reference\nelement vertex ${finite.size}\nproperty float x\nproperty float y\nproperty float z\nend_header\n".toByteArray())
            val buffer=ByteBuffer.allocate(finite.size*12).order(ByteOrder.LITTLE_ENDIAN);finite.forEach{p->p.forEach{buffer.putFloat(it.toFloat())}};stream.write(buffer.array())}
        File(directory,"surface.f32").outputStream().buffered().use{stream->val buffer=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
            surface.heights.forEach{z->if(buffer.remaining()<4){stream.write(buffer.array(),0,buffer.position());buffer.clear()};buffer.putFloat(z)};stream.write(buffer.array(),0,buffer.position())}
        MappingPhotoStore.writeJson(File(directory,"cameras.json"),JSONObject().put("camerasEnu",JSONArray(model.cameras.toList())).put("intrinsicsPixels",JSONArray(k.toList()))
            .put("width",model.frames[0].width).put("height",model.frames[0].height).put("originalSha256",JSONArray(model.frames.map{it.file.nameWithoutExtension})))
        val bounds=model.report.optJSONArray("processingRegionBoundsEnu")?.let{MappingRegionPlan.Bounds(it.getDouble(0),it.getDouble(1),it.getDouble(2),it.getDouble(3))}
        val colors=renderColors(surface,model.cameras,k,model.frames[0].width,model.frames[0].height,paths,progress,check,bounds)
        return writeMesh(surface,colors,directory,metadata,check)
    }
    fun renderColors(surface:MappingSurface,cameras:DoubleArray,k:DoubleArray,imageWidth:Int,imageHeight:Int,paths:Array<String>,progress:(String,Double)->Unit,check:()->Unit,bounds:MappingRegionPlan.Bounds?=null):IntArray {
        MappingSurfaceNative.prepareProjection(surface.heights,surface.width,surface.height,surface.x,surface.y,surface.cell,
            cameras,k,imageWidth,imageHeight,paths,object:MappingSurfaceNative.Progress {
                override fun iteration(iteration:Int,cost:Double):Boolean {check();progress("Matching photo seams · ${(cost*100).toInt()}%",.68+.01*cost);return true}
            })
        val colors=IntArray(surface.width*surface.height);val pixels=IntArray(256*256)
        val bx=(surface.width+255)/256;val by=(surface.height+255)/256
        for(blockY in 0 until by)for(blockX in 0 until bx){check();progress("Coloring the 3D surface",.69+.035*(blockY*bx+blockX)/(bx*by))
            val left=surface.x+blockX*256*surface.cell;val top=surface.y+(surface.height-1-blockY*256)*surface.cell
            if(bounds!=null&&(left>bounds.east||left+256*surface.cell<bounds.west||top<bounds.south||top-256*surface.cell>bounds.north))continue
            MappingSurfaceNative.render(surface.heights,surface.width,surface.height,surface.x,surface.y,surface.cell,cameras,k,imageWidth,imageHeight,paths,
                surface.x+(blockX*256-.5)*surface.cell,surface.y+(surface.height-1-blockY*256+.5)*surface.cell,surface.cell,pixels)
            for(row in 0 until min(256,surface.height-blockY*256))for(col in 0 until min(256,surface.width-blockX*256)) {
                if(bounds==null||(left+col*surface.cell in bounds.west..bounds.east&&top-row*surface.cell in bounds.south..bounds.north))
                    colors[(surface.height-1-blockY*256-row)*surface.width+blockX*256+col]=pixels[row*256+col]
            }
        }
        return colors
    }
    fun writeMesh(surface:MappingSurface,colors:IntArray,directory:File,metadata:JSONObject,check:()->Unit):JSONObject {
        require(colors.size==surface.heights.size)
        val stride=if(metadata.optJSONObject("cameraAdjustment")?.optBoolean("regionalProcessing")==true)max(1,ceil(.4/surface.cell-1e-9).toInt())
            else MappingSurfaceMesh.stride(surface.heights,surface.width,surface.height,surface.cell)
        val cols=(surface.width-1)/stride+1;val rows=(surface.height-1)/stride+1
        val ids=IntArray(cols*rows){-1};var count=0
        for(row in 0 until rows)for(col in 0 until cols){val index=row*stride*surface.width+col*stride;if(surface.heights[index].isFinite()&&colors[index]!=0)ids[row*cols+col]=count++}
        require(count>=500){"Too few colored 3D surface vertices."}
        val positions=ByteBuffer.allocate(count*12).order(ByteOrder.LITTLE_ENDIAN);val normals=ByteBuffer.allocate(count*12).order(ByteOrder.LITTLE_ENDIAN);val rgba=ByteBuffer.allocate(count*4)
        val min=FloatArray(3){Float.POSITIVE_INFINITY};val max=FloatArray(3){Float.NEGATIVE_INFINITY}
        for(row in 0 until rows){check();for(col in 0 until cols){if(ids[row*cols+col]<0)continue
            val xx=col*stride;val yy=row*stride;val index=yy*surface.width+xx;val z=surface.heights[index]
            val v=floatArrayOf((surface.x+xx*surface.cell).toFloat(),z,(-surface.y-yy*surface.cell).toFloat())
            repeat(3){i->positions.putFloat(v[i]);min[i]=minOf(min[i],v[i]);max[i]=maxOf(max[i],v[i])}
            fun at(x:Int,y:Int)=surface.heights[y.coerceIn(0,surface.height-1)*surface.width+x.coerceIn(0,surface.width-1)].takeIf(Float::isFinite)?:z
            val nx=-(at(xx+stride,yy)-at(xx-stride,yy))/(2*stride*surface.cell);val nz=(at(xx,yy+stride)-at(xx,yy-stride))/(2*stride*surface.cell);val length=sqrt(nx*nx+nz*nz+1)
            normals.putFloat((nx/length).toFloat());normals.putFloat((1/length).toFloat());normals.putFloat((nz/length).toFloat())
            val c=colors[index];rgba.put((c shr 16).toByte());rgba.put((c shr 8).toByte());rgba.put(c.toByte());rgba.put(255.toByte())
        }}
        val indices=ByteBuffer.allocate((cols-1)*(rows-1)*24).order(ByteOrder.LITTLE_ENDIAN)
        check()
        MappingSurfaceNative.faces(surface.heights,surface.width,surface.height,stride,ids).forEach{indices.putInt(it)}
        require(indices.position()>0){"No supported surface faces could be recovered. Originals are retained."}
        val blocks=listOf(positions.array(),normals.array(),rgba.array(),indices.array().copyOf(indices.position()));val views=JSONArray();var offset=0
        blocks.forEachIndexed{i,bytes->views.put(JSONObject().put("buffer",0).put("byteOffset",offset).put("byteLength",bytes.size).put("target",if(i==3)34963 else 34962));offset+=bytes.size}
        val accessors=JSONArray(listOf(
            JSONObject().put("bufferView",0).put("componentType",5126).put("count",count).put("type","VEC3").put("min",JSONArray(min.toList())).put("max",JSONArray(max.toList())),
            JSONObject().put("bufferView",1).put("componentType",5126).put("count",count).put("type","VEC3"),
            JSONObject().put("bufferView",2).put("componentType",5121).put("normalized",true).put("count",count).put("type","VEC4"),
            JSONObject().put("bufferView",3).put("componentType",5125).put("count",indices.position()/4).put("type","SCALAR")))
        val primitive=JSONObject().put("attributes",JSONObject().put("POSITION",0).put("NORMAL",1).put("COLOR_0",2)).put("indices",3).put("mode",4)
        metadata.put("meshSpacingMeters",stride*surface.cell).put("vertexCount",count).put("triangleCount",indices.position()/12)
        MappingPhotoStore.writeJson(File(directory,"surface.json"),metadata)
        val gltf=JSONObject().put("asset",JSONObject().put("version","2.0").put("generator","R2C local RTK stereo surface"))
            .put("scene",0).put("scenes",JSONArray(listOf(JSONObject().put("nodes",JSONArray(listOf(0)))))).put("nodes",JSONArray(listOf(JSONObject().put("mesh",0))))
            .put("meshes",JSONArray(listOf(JSONObject().put("primitives",JSONArray(listOf(primitive)))))).put("accessors",accessors).put("bufferViews",views).put("buffers",JSONArray(listOf(JSONObject().put("byteLength",offset))))
            .put("extras",metadata)
        val json=gltf.toString().toByteArray();val padded=(json.size+3)/4*4;val total=12+8+padded+8+offset
        File(directory,"surface.glb").outputStream().buffered().use{stream->val header=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2).putInt(total).putInt(padded).putInt(0x4e4f534a).array();stream.write(header);stream.write(json);repeat(padded-json.size){stream.write(32)}
            stream.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(offset).putInt(0x004e4942).array());blocks.forEach{stream.write(it)}}
        return JSONObject().put("type","r2c_visible_surface").put("version",1).put("modelPath","surface/surface.glb").put("surfacePath","surface/surface.f32")
            .put("vertexCount",count).put("triangleCount",indices.position()/12).put("meshSpacingMeters",stride*surface.cell).put("surfaceCellMeters",surface.cell)
            .put("quality",if(metadata.optJSONObject("cameraAdjustment")?.optString("reconstructionMode")=="approximate-plane")"Approximate reference plane only; no measured 3D depth. Position and height accuracy unknown." else "Stereo visible-surface heightfield with bounded nearby-3D-track interpolation. Includes roofs and canopy; unsupported gaps stay unknown. Not bare earth or a complete volumetric model.")
    }
}

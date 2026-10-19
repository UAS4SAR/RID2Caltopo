package org.ncssar.rid2caltopo.mapping

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Re-export a saved surface with its original calibrated color projection, without reconstructing. */
internal object MappingSurfacePreview {
    @Synchronized fun rebuild(context:Context,id:String,progress:(String,Double)->Unit,check:()->Unit):MappingLayer {
        require(MappingProcessingManager.state.value?.active!=true){"Wait for reconstruction to finish."}
        MappingSurfaceNative.requireAvailable()
        val root=LocalMappingTiles.directory(context,id);val report=JSONObject(File(root,"layer.json").readText())
        val missionId=report.getString("missionId");require(UUID.fromString(missionId).toString()==missionId)
        val mission=File(context.filesDir,"mapping-missions/$missionId")
        require(!JSONObject(File(mission,"processing-status.json").readText()).optBoolean("active")){"Wait for reconstruction to finish."}
        val metadata=JSONObject(File(root,"surface/surface.json").readText());val calibration=JSONObject(File(root,"surface/cameras.json").readText())
        val width=metadata.getInt("width");val height=metadata.getInt("height");require(width>0&&height>0&&width.toLong()*height<=3_000_000)
        val bytes=File(root,"surface/surface.f32").readBytes();require(bytes.size.toLong()==width.toLong()*height*4)
        val values=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();val heights=FloatArray(width*height).also{values.get(it)}
        val surface=MappingSurface(heights,width,height,metadata.getDouble("gridX"),metadata.getDouble("gridY"),metadata.getDouble("cellMeters"),metadata.getJSONObject("quality"))
        val cameras=calibration.getJSONArray("camerasEnu").let{a->DoubleArray(a.length()){a.getDouble(it)}}
        val k=calibration.getJSONArray("intrinsicsPixels").let{a->DoubleArray(a.length()){a.getDouble(it)}}
        val digests=calibration.getJSONArray("originalSha256").let{a->(0 until a.length()).map{a.getString(it).also(MappingPhotoStore::checkDigest)}}
        require(digests.size in 3..200&&cameras.size==digests.size*12&&k.size==5&&cameras.all(Double::isFinite)&&k.all(Double::isFinite))
        val imageWidth=calibration.getInt("width");val imageHeight=calibration.getInt("height")
        require(imageWidth>0&&imageHeight>0&&imageWidth.toLong()*imageHeight<=24_000_000)
        require(root.usableSpace>digests.size*imageWidth.toLong()*imageHeight*4+MappingPhotoStore.FREE_SPACE_RESERVE+512L*1024*1024){"Not enough space for temporary preview colors."}
        val work=File(context.filesDir,"mapping-preview-${UUID.randomUUID()}").apply{mkdirs()}
        val originals=digests.map{File(mission,"originals/$it.jpg")}
        val replacement=linkedMapOf<File,File>()
        val backups=linkedMapOf<File,File>()
        var committed=false
        try {
            val paths=originals.mapIndexed{i,file->check();progress("Preparing preview colors ${i+1}/${originals.size}",.65*i/originals.size)
                require(sha(file)==digests[i]){"A retained original checksum changed."}
                val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888;inPreferredColorSpace=android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)})?:error("Cannot decode retained original.")
                val raw=File(work,"$i.raw")
                try{require(bitmap.width==imageWidth&&bitmap.height==imageHeight&&bitmap.rowBytes==imageWidth*4)
                    val buffer=ByteBuffer.allocate(bitmap.byteCount);bitmap.copyPixelsToBuffer(buffer);raw.outputStream().buffered().use{it.write(buffer.array())}}
                finally{bitmap.recycle()};raw.path
            }.toTypedArray()
            val output=File(work,"surface").apply{mkdirs()}
            val colors=MappingSurfaceExport.renderColors(surface,cameras,k,imageWidth,imageHeight,paths,progress,check)
            MappingBoundary.resolve(context.filesDir,MappingLayer.parse(report))?.let{val boundary=org.json.JSONArray(it.coordinates);metadata.put("assignmentBoundary",boundary);report.put("assignmentBoundary",boundary)}
            val model=MappingSurfaceExport.writeMesh(surface,colors,output,metadata,check)
            report.put("surfaceModel",model).put("previewRegeneratedAt",java.time.Instant.now().toString())
            val layer=MappingLayer.parse(report)
            val layerFile=File(work,"layer.json");MappingPhotoStore.writeJson(layerFile,report)
            replacement[File(root,"surface/surface.glb")]=File(output,"surface.glb")
            replacement[File(root,"surface/surface.json")]=File(output,"surface.json")
            replacement[File(root,"layer.json")]=layerFile
            val packageFile=File(root,"share.zip")
            if(packageFile.isFile){progress("Updating the 3D share package",.97);val next=File(work,"share.zip")
                ZipFile(packageFile).use{old->val manifest=JSONObject(old.getInputStream(old.getEntry("manifest.json")).bufferedReader().use{it.readText()})
                    val layers=manifest.getJSONArray("mapping_layers");for(i in 0 until layers.length())if(layers.getJSONObject(i).getString("layerId")==id)layers.put(i,layer.json())
                    val entries=manifest.getJSONArray("surface_entries");for(i in 0 until entries.length()){val entry=entries.getJSONObject(i)
                        val path=entry.getString("path");val file=when(path){"mapping-layers/$id/surface/surface.glb"->File(output,"surface.glb");"mapping-layers/$id/surface/surface.json"->File(output,"surface.json");else->null}
                        file?.let{entry.put("bytes",it.length()).put("sha256",sha(it))}}
                    ZipOutputStream(next.outputStream().buffered()).use{zip->old.entries().asSequence().forEach{entry->check();zip.putNextEntry(ZipEntry(entry.name))
                        val file=when(entry.name){"mapping-layers/$id/surface/surface.glb"->File(output,"surface.glb");"mapping-layers/$id/surface/surface.json"->File(output,"surface.json");else->null}
                        if(entry.name=="manifest.json")zip.write(manifest.toString().toByteArray())else if(file!=null)file.inputStream().use{it.copyTo(zip)}else old.getInputStream(entry).use{it.copyTo(zip)}
                        zip.closeEntry()}}
                };replacement[packageFile]=next
            }
            check();require(MappingProcessingManager.state.value?.active!=true)
            // All computation and packaging finish before any current artifact is replaced.
            replacement.entries.forEachIndexed{i,(destination,source)->val backup=File(work,"backup-$i")
                require(destination.renameTo(backup)){"Cannot retain previous preview."};backups[destination]=backup
                require(source.renameTo(destination)){"Cannot publish regenerated preview."}}
            MappingLayer.save(context,layer.incidentId,MappingLayer.load(context,layer.incidentId).map{if(it.id==id)layer else it})
            committed=true;MappingWorkspaceState.layersChanged();progress("${Units.current.length(model.getDouble("meshSpacingMeters"))} preview ready",1.0)
            return layer
        } finally {
            MappingSurfaceNative.releasePixels()
            if(!committed)backups.forEach{(destination,backup)->destination.delete();check(backup.renameTo(destination)){"Could not restore previous preview."}}
            work.deleteRecursively()
        }
    }
    private fun sha(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{input->val block=ByteArray(65536);while(true){val n=input.read(block);if(n<0)break;digest.update(block,0,n)}};return LocalS3.hex(digest.digest())}
}

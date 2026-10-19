package org.ncssar.rid2caltopo.mapping

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Only data artifacts can accompany a model. No executable HTML/JS is imported. */
object MappingSurfaceFiles {
    private val core=setOf("surface.glb","surface.f32","surface.json","cameras.json")
    private val names=core+"sparse-points.ply"
    internal fun files(root:File)=root.walkTopDown().filter{it.isFile&&validPath(it.relativeTo(root).invariantSeparatorsPath)}.toList()
    private fun validPath(name:String)=name in names || name=="collection.json" || Regex("regions/[0-9]{1,4}/(surface\\.glb|surface\\.f32|surface\\.json|cameras\\.json|sparse-points\\.ply)").matches(name)
    fun export(context:Context,layer:MappingLayer):List<Pair<JSONObject,File>> {
        if(layer.surfaceModel.isBlank())return emptyList()
        return files(File(LocalMappingTiles.directory(context,layer.id),"surface")).map{file->
            val path="mapping-layers/${layer.id}/surface/${file.relativeTo(File(LocalMappingTiles.directory(context,layer.id),"surface")).invariantSeparatorsPath}"
            JSONObject().put("layerId",layer.id).put("path",path).put("bytes",file.length()).put("sha256",sha(file)) to file}
    }
    fun import(context:Context,entries:JSONArray,layers:List<MappingLayer>,read:(String)->ByteArray?):Int {
        require(entries.length()<=layers.size*1001){"Too many 3D surface files."}
        val records=(0 until entries.length()).map{entries.getJSONObject(it)}
        var models=0
        for(layer in layers){val selected=records.filter{it.getString("layerId")==layer.id};if(selected.isEmpty())continue
            require(layer.surfaceModel.isNotBlank()&&selected.size in 4..1001){"Incomplete 3D surface package."}
            val root=MappingVolume.directory(context,"mapping-layers",layer.id);val stage=File(root,".surface-import-${UUID.randomUUID()}").apply{mkdirs()}
            val destination=File(root,"surface");val backup=File(root,".surface-backup-${UUID.randomUUID()}")
            try{
                val found=mutableSetOf<String>()
                selected.forEach{record->val path=record.getString("path");val name=path.removePrefix("mapping-layers/${layer.id}/surface/")
                    require(validPath(name)&&path=="mapping-layers/${layer.id}/surface/$name"&&found.add(name)){"Invalid 3D surface file path."}
                    val bytes=read(path)?:error("3D surface file is missing.");require(bytes.size.toLong()==record.getLong("bytes")&&bytes.size<=128*1024*1024){"Invalid 3D surface file size."}
                    val expected=record.getString("sha256");require(expected.matches(Regex("[a-f0-9]{64}"))&&LocalS3.hex(MessageDigest.getInstance("SHA-256").digest(bytes))==expected){"3D surface checksum mismatch."}
                    File(stage,name).apply{parentFile!!.mkdirs()}.writeBytes(bytes)
                }
                if("collection.json" in found) {
                    val regions=JSONObject(File(stage,"collection.json").readText()).getJSONArray("regions")
                    require(regions.length() in 1..200)
                    val expected=mutableSetOf("collection.json")
                    (0 until regions.length()).forEach { i->
                        require(regions.getJSONObject(i).getString("path")=="regions/$i/surface.glb")
                        require(core.all { "regions/$i/$it" in found })
                        names.forEach { if("regions/$i/$it" in found)expected.add("regions/$i/$it") }
                    }
                    require(found==expected)
                } else require(found.containsAll(core)&&found.all{it in names})
                val same=destination.isDirectory&&selected.all{r->val f=File(destination,r.getString("path").removePrefix("mapping-layers/${layer.id}/surface/"));f.isFile&&sha(f)==r.getString("sha256")}
                if(!same){if(destination.exists())require(destination.renameTo(backup));require(stage.renameTo(destination)){"Cannot install 3D surface."};backup.deleteRecursively()}
                models++
            }catch(e:Exception){if(backup.exists()&&!destination.exists())backup.renameTo(destination);throw e}
            finally{stage.deleteRecursively()}
        }
        require(records.all{record->layers.any{it.id==record.getString("layerId")}}){"3D surface has no matching layer."}
        return models
    }
    private fun sha(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().buffered().use{stream->val buffer=ByteArray(65536);while(true){val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)}};return LocalS3.hex(digest.digest())}
}

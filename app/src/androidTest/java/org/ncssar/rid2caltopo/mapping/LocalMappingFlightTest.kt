package org.ncssar.rid2caltopo.mapping

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Explicit opt-in: reconstruct the authorized on-device flight; no aircraft or WAN access. */
@RunWith(AndroidJUnit4::class)
class LocalMappingFlightTest {
    @Test fun verifyExistingLayerSharingRoundTrip(){
        val args=InstrumentationRegistry.getArguments();val id=args.getString("mappingLayer")
        assumeTrue(id!=null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=LocalMappingTiles.directory(context,id!!);val layer=MappingLayer.parse(JSONObject(File(root,"layer.json").readText()))
        assertEquals("daylight-rgb",layer.profile);assertEquals(66,layer.photoCount)
        val indices=LocalMappingTiles.indices(root);val source=layer.tileSource()
        val writer=org.ncssar.rid2caltopo.video.mapcache.TileDiskCacheWriter(context)
        val z=19;val tiles=indices.filter{org.osmdroid.util.MapTileIndex.getZoom(it)==z}
        val x0=tiles.minOf{org.osmdroid.util.MapTileIndex.getX(it)};val x1=tiles.maxOf{org.osmdroid.util.MapTileIndex.getX(it)}
        val y0=tiles.minOf{org.osmdroid.util.MapTileIndex.getY(it)};val y1=tiles.maxOf{org.osmdroid.util.MapTileIndex.getY(it)}
        require(x1-x0<16&&y1-y0<16)
        val preview=android.graphics.Bitmap.createBitmap((x1-x0+1)*256,(y1-y0+1)*256,android.graphics.Bitmap.Config.ARGB_8888)
        try{val canvas=android.graphics.Canvas(preview);tiles.forEach{index->
            val bytes=writer.readTileBytes(source,index)!!;val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            try{canvas.drawBitmap(image,((org.osmdroid.util.MapTileIndex.getX(index)-x0)*256).toFloat(),((org.osmdroid.util.MapTileIndex.getY(index)-y0)*256).toFloat(),null)}finally{image.recycle()}}
            val pixels=IntArray(preview.width*preview.height);preview.getPixels(pixels,0,preview.width,0,0,preview.width,preview.height)
            assertTrue("Generated map lacks image coverage",pixels.count{android.graphics.Color.alpha(it)>0}>pixels.size/3)
            File(root,"preview.png").outputStream().use{preview.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        }finally{preview.recycle()}
        ZipFile(File(root,"share.zip")).use{zip->
            val manifest=JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
            val shared=manifest.getJSONArray("mapping_layers").getJSONObject(0)
            assertEquals("r2c_mapping_layer",shared.getString("type"));assertEquals("xyz",shared.getString("tileScheme"));assertEquals(1,shared.getInt("version"))
            if(layer.surfaceModel.isNotBlank()){
                val entries=manifest.getJSONArray("surface_entries");assertTrue(entries.length() in 4..5)
                val glb=zip.getInputStream(zip.getEntry("mapping-layers/${layer.id}/surface/surface.glb")).use{it.readBytes()}
                val header=java.nio.ByteBuffer.wrap(glb).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                assertEquals(0x46546c67,header.int);assertEquals(2,header.int);assertEquals(glb.size,header.int)
                val jsonSize=header.int;assertEquals(0x4e4f534a,header.int)
                val model=JSONObject(String(glb,20,jsonSize,Charsets.UTF_8));assertTrue(model.getJSONArray("accessors").getJSONObject(0).getInt("count")>500)
                val cloneId=java.util.UUID.randomUUID().toString();val clone=layer.copy(id=cloneId,tileUrlTemplate=layer.tileUrlTemplate.replace(layer.id,cloneId))
                val cloneEntries=org.json.JSONArray((0 until entries.length()).map{i->JSONObject(entries.getJSONObject(i).toString()).put("layerId",cloneId).put("path",entries.getJSONObject(i).getString("path").replace(layer.id,cloneId))})
                try{
                    assertEquals(1,MappingSurfaceFiles.import(context,cloneEntries,listOf(clone)){path->zip.getInputStream(zip.getEntry(path.replace(cloneId,layer.id))).use{it.readBytes()}})
                    assertArrayEquals(glb,File(LocalMappingTiles.directory(context,cloneId),"surface/surface.glb").readBytes())
                    cloneEntries.getJSONObject(0).put("sha256","0".repeat(64))
                    assertThrows(IllegalArgumentException::class.java){MappingSurfaceFiles.import(context,cloneEntries,listOf(clone)){path->zip.getInputStream(zip.getEntry(path.replace(cloneId,layer.id))).use{it.readBytes()}}}
                    assertArrayEquals(glb,File(LocalMappingTiles.directory(context,cloneId),"surface/surface.glb").readBytes())
                }finally{LocalMappingTiles.directory(context,cloneId).deleteRecursively()}
            }
        }
        val result=org.ncssar.rid2caltopo.data.MutualAidPackageManager.importPackage(context,android.net.Uri.fromFile(File(root,"share.zip")))
        assertTrue(result.second,result.first)
        assertEquals(indices.toSet(),writer.cachedTileIndices(source).toSet())
        assertEquals(layer.id,MappingLayer.load(context,layer.incidentId).first{it.id==layer.id}.id)
        if(layer.surfaceModel.isNotBlank())assertTrue(MappingLayer.load(context,layer.incidentId).first{it.id==layer.id}.surfaceModel.isNotBlank())
        android.util.Log.i("LocalMappingFlightTest","PASS: generated coverage, layer metadata and ${indices.size}-tile map-package import round trip verified")
    }
    @Test fun stitchVerifiedFlightAndVerifyLocalTilesAndSharePackage(){
        val args=InstrumentationRegistry.getArguments();val id=args.getString("mappingMission")
        assumeTrue(id!=null);val context=InstrumentationRegistry.getInstrumentation().targetContext
        val expected=args.getString("mappingPhotoCount")?.toInt()?:error("Expected photo count is required for this opt-in test.")
        val missionRoot=File(context.filesDir,"mapping-missions/$id")
        val before=MappingPhotoStore(missionRoot).inventory()
        assertEquals(expected,before.getInt("photoCount"))
        val selected=if(args.getString("mappingSelection")=="completed")MappingFlightRepository.completionDigests(missionRoot) else null
        val selectedCount=selected?.size ?: expected
        args.getString("mappingSelectedPhotoCount")?.let{assertEquals(it.toInt(),selectedCount)}
        context.startActivity(android.content.Intent(context,org.ncssar.rid2caltopo.app.R2CActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        MappingProcessingManager.start(context,id!!,selectedCount,engine=if(args.getString("surfaceEngine")=="true")"rtk-surface" else "flat",
            selectedDigests=selected,completionSource=if(selected!=null)"operator_selected_completed_batch" else "operator_selected_verified_photos")
        val deadline=System.currentTimeMillis()+30*60*1000
        var last=""
        while(MappingProcessingManager.state.value?.active==true&&System.currentTimeMillis()<deadline){
            val state=MappingProcessingManager.state.value!!
            if(state.stage!=last){last=state.stage;android.util.Log.i("LocalMappingFlightTest",last)}
            Thread.sleep(1000)
        }
        val state=MappingProcessingManager.state.value!!
        assertFalse("Processing timeout",state.active);assertNull(state.error,state.error)
        val layer=state.layer!!
        val resultReport=JSONObject(File(LocalMappingTiles.directory(context,layer.id),"layer.json").readText())
        if(args.getString("surfaceEngine")=="true") {
            assertEquals(selectedCount,resultReport.getInt("selectedPhotoCount"))
            assertEquals(selectedCount,layer.photoCount+resultReport.getInt("excludedPhotoCount"))
            assertEquals(resultReport.getInt("excludedPhotoCount"),resultReport.getJSONArray("excludedPhotos").length())
        }else assertEquals(selectedCount,layer.photoCount)
        val root=LocalMappingTiles.directory(context,layer.id);val indices=LocalMappingTiles.indices(root)
        if(args.getString("surfaceEngine")=="true"){
            assertTrue(layer.surfaceModel.isNotBlank());assertTrue(File(root,"surface/surface.glb").length()>1000)
            args.getString("surfaceMeshSpacing")?.let{assertEquals(it.toDouble(),JSONObject(layer.surfaceModel).getDouble("meshSpacingMeters"),1e-9)}
            val report=JSONObject(File(root,"layer.json").readText());assertTrue(report.getBoolean("photogrammetric3D"))
            assertEquals(layer.photoCount,report.getJSONObject("cameraAdjustment").getInt("fixedRtkPhotos"))
        }
        assertTrue(indices.isNotEmpty());val writer=org.ncssar.rid2caltopo.video.mapcache.TileDiskCacheWriter(context)
        val source=layer.tileSource();val sample=writer.readTileBytes(source,indices.first())!!
        val bitmap=BitmapFactory.decodeByteArray(sample,0,sample.size);assertEquals(256,bitmap.width);bitmap.recycle()
        assertTrue(writer.exists(source,indices.first()));assertEquals(Long.MAX_VALUE,writer.getExpirationTimestamp(source,indices.first()))
        assertEquals(indices.toSet(),writer.cachedTileIndices(source).toSet())
        assertTrue(MappingLayer.load(context,layer.incidentId).any{it.id==layer.id})
        ZipFile(File(root,"share.zip")).use{zip->
            val manifest=JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
            assertEquals("rid2caltopo_mutual_aid_package",manifest.getString("format"));assertEquals(indices.size,manifest.getJSONArray("tile_entries").length())
            assertEquals(layer.id,manifest.getJSONArray("mapping_layers").getJSONObject(0).getString("layerId"))
            assertFalse(zip.entries().asSequence().any{it.name.contains("originals/")||it.name.contains("credentials")})
        }
        assertEquals(before.getInt("photoCount"),MappingPhotoStore(File(context.filesDir,"mapping-missions/$id")).inventory().getInt("photoCount"))
        android.util.Log.i("LocalMappingFlightTest","PASS: ${layer.photoCount} photos, ${indices.size} durable tiles, zoom ${layer.minZoom}-${layer.maxZoom}; layer and share package verified")
    }
}

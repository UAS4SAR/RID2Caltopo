package org.ncssar.rid2caltopo.mapping

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class MappingMissionStorageTest {
    @get:Rule val temp=TemporaryFolder()
    private fun write(path:String,size:Int):File=File(temp.root,path).apply{parentFile!!.mkdirs();writeBytes(ByteArray(size))}
    private fun mission(id:String=UUID.randomUUID().toString()):String {
        File(temp.root,"mapping-missions/$id").mkdirs()
        File(temp.root,"mapping-missions/$id/mission.json").writeText(JSONObject().put("missionId",id).toString())
        return id
    }
    private fun product(owner:String?,legacyDigests:List<String>?=null):String {
        val id=UUID.randomUUID().toString();val directory=File(temp.root,"mapping-layers/$id").apply{mkdirs()}
        val report=JSONObject().put("layerId",id);owner?.let{report.put("missionId",it)}
        File(directory,"layer.json").writeText(report.toString())
        legacyDigests?.let{File(directory,"surface").mkdirs();File(directory,"surface/cameras.json").writeText(JSONObject().put("originalSha256",JSONArray(it)).toString())}
        return id
    }
    @Test fun countsOriginalsControllerReturnsEveryResultVersionPackagesAndOwnedWork() {
        val id=mission();val a=product(id);val b=product(id)
        write("mapping-missions/$id/originals/a.jpg",100)
        write("mapping-missions/$id/cloud/storage/objects/thermal",200)
        write("mapping-missions/$id/pending/p.part",30)
        write("mapping-layers/$a/18/1/2.png",40);write("mapping-layers/$b/18/1/2.png",50)
        write("mapping-layers/$a/surface/surface.glb",60);write("mapping-layers/$b/surface/surface.f32",70)
        write("mapping-layers/$a/share.zip",80)
        val workId=UUID.randomUUID().toString()
        val owner=File(temp.root,"mapping-processing/$workId/work-owner.json").apply{parentFile!!.mkdirs();writeText(JSONObject().put("missionId",id).toString())}
        write("mapping-processing/$workId/working.raw",90)
        val usage=MappingMissionStorage.index(temp.root).mission(id)
        assertEquals(100L,usage.originals);assertEquals(230L,usage.controllerReturns)
        assertEquals(90L,usage.maps2D);assertEquals(130L,usage.models3D);assertEquals(80L,usage.exports)
        assertEquals(90+owner.length(),usage.working)
        assertTrue(usage.metadata>0);assertTrue(usage.complete)
        assertEquals(usage.originals+usage.controllerReturns+usage.maps2D+usage.models3D+usage.exports+usage.working+usage.metadata+usage.planning,usage.total)
    }
    @Test fun hardLinkedControllerPhotoIsNotChargedTwiceButDistinctCopyAndZipAre() {
        val id=mission();val original=write("mapping-missions/$id/originals/a.jpg",100)
        val linked=File(temp.root,"mapping-missions/$id/cloud/storage/objects/linked").apply{parentFile!!.mkdirs()}
        Files.createLink(linked.toPath(),original.toPath())
        write("mapping-missions/$id/cloud/storage/objects/copy",100)
        val p=product(id);write("mapping-layers/$p/share.zip",100)
        val usage=MappingMissionStorage.index(temp.root).mission(id)
        assertEquals(100L,usage.originals);assertEquals(100L,usage.controllerReturns);assertEquals(100L,usage.exports)
    }
    @Test fun legacyCameraProofAssociatesOldVersionsOnlyToOneMissionAndRemovedResultsDisappear() {
        val id=mission();val hash="a".repeat(64)
        write("mapping-missions/$id/inventory/$hash.json",3)
        val legacy=product(null,listOf(hash));write("mapping-layers/$legacy/18/1/2.png",40)
        val unknown=product(null);write("mapping-layers/$unknown/18/1/2.png",20)
        var index=MappingMissionStorage.index(temp.root)
        assertEquals(40L,index.mission(id).maps2D);assertEquals(20L,index.unassigned().maps2D)
        val other=mission();write("mapping-missions/$other/inventory/$hash.json",3)
        index=MappingMissionStorage.index(temp.root)
        assertEquals(0L,index.mission(id).maps2D);assertEquals(60L,index.unassigned().maps2D)
        File(temp.root,"mapping-layers/$legacy").deleteRecursively()
        assertEquals(20L,MappingMissionStorage.index(temp.root).unassigned().maps2D)
    }
    @Test fun unrelatedMissionDoesNotInheritWorkOrProductsAndLargeAmountsUseGiB() {
        val id=mission();val other=mission();val p=product(other)
        write("mapping-layers/$p/surface/surface.glb",80)
        write("mapping-processing/legacy/working.raw",50)
        val index=MappingMissionStorage.index(temp.root,MappingProcessingState(other,"Other"))
        assertEquals(0L,index.mission(id).models3D);assertEquals(0L,index.mission(id).working)
        assertEquals(80L,index.mission(other).models3D);assertEquals(50L,index.mission(other).working)
        assertEquals(50L,MappingMissionStorage.index(temp.root).unassigned().working)
        assertTrue(MappingStorageUsage.format(3L*1024*1024*1024).contains("GiB"))
    }
    @Test fun persistentCacheSurvivesMemoryResetAndIsOutsideMeasuredTrees() {
        val id=mission();val p=product(id)
        write("mapping-missions/$id/originals/a.jpg",100)
        write("mapping-layers/$p/18/1/2.png",40)
        val first=MappingMissionStorage.index(temp.root).mission(id)
        assertTrue(File(temp.root,"mapping-storage-stats").listFiles().orEmpty().isNotEmpty())
        MappingMissionStorage.clearMemoryCache()
        assertEquals(first,MappingMissionStorage.index(temp.root).cachedMission(id))
        assertEquals(first,MappingMissionStorage.index(temp.root).mission(id))
    }
    @Test fun nestedFileAdditionsAndDeletionsInvalidateWithoutRootTimestampChanges() {
        val id=mission();val p=product(id)
        val tile=write("mapping-layers/$p/18/1/2.png",40)
        val first=MappingMissionStorage.index(temp.root).mission(id)
        val root=File(temp.root,"mapping-layers/$p");val rootTime=root.lastModified()
        write("mapping-layers/$p/18/1/3.png",20)
        tile.parentFile!!.setLastModified(tile.parentFile!!.lastModified()+2000)
        assertEquals(rootTime,root.lastModified())
        assertNull(MappingMissionStorage.index(temp.root).cachedMission(id))
        assertEquals(first.maps2D+20,MappingMissionStorage.index(temp.root).mission(id).maps2D)
        tile.delete();tile.parentFile!!.setLastModified(tile.parentFile!!.lastModified()+4000)
        assertNull(MappingMissionStorage.index(temp.root).cachedMission(id))
        assertEquals(20L,MappingMissionStorage.index(temp.root).mission(id).maps2D)
    }
    @Test fun inPlaceWritesInvalidateAndCorruptCacheIsRebuilt() {
        val id=mission();val original=write("mapping-missions/$id/originals/a.jpg",100)
        MappingMissionStorage.index(temp.root).mission(id)
        MappingMissionStorage.invalidate(original)
        original.writeBytes(ByteArray(200));MappingMissionStorage.invalidate(original)
        assertNull(MappingMissionStorage.index(temp.root).cachedMission(id))
        assertEquals(200L,MappingMissionStorage.index(temp.root).mission(id).originals)
        File(temp.root,"mapping-storage-stats").listFiles().orEmpty().filter { it.extension=="json" }.forEach { it.writeText("broken") }
        MappingMissionStorage.clearMemoryCache()
        assertNull(MappingMissionStorage.index(temp.root).cachedMission(id))
        assertEquals(200L,MappingMissionStorage.index(temp.root).mission(id).originals)
    }

}

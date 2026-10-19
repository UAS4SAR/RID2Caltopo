package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MappingSurfaceCheckpointTest {
    @Test fun recoveryRequiresMatchingInputsAndUnchangedGeometry() {
        val out=Files.createTempDirectory("surface-checkpoint").toFile()
        try {
            val mission=JSONObject().put("missionId","test");val inputs=listOf("a","b","c")
            listOf("surface.json","surface.f32","surface.glb","cameras.json","sparse-points.ply").forEach { name->
                File(out,"surface/$name").apply{parentFile.mkdirs();writeText("retained $name")}
            }
            MappingSurfaceCheckpoint.save(mission,inputs,out,JSONObject().put("meshSpacingMeters",.4),{})
            assertTrue(MappingSurfaceCheckpoint.valid(mission,inputs.reversed(),out,{}))
            assertTrue(MappingSurfaceCheckpoint.valid(JSONObject(mission.toString()).put("processingRegionBounds",org.json.JSONArray(listOf(0,0,10,10))),inputs,out,{}))
            assertFalse(MappingSurfaceCheckpoint.valid(mission,inputs+"d",out,{}))
            assertFalse(MappingSurfaceCheckpoint.valid(JSONObject().put("missionId","different"),inputs,out,{}))
            val model=File(out,"surface/surface.glb");val bytes=model.readBytes()
            model.writeBytes(bytes.also{it[0]=(it[0].toInt() xor 1).toByte()})
            assertFalse(MappingSurfaceCheckpoint.valid(mission,inputs,out,{}))
            model.writeText("retained surface.glb")
            assertTrue(MappingSurfaceCheckpoint.valid(mission,inputs,out,{}))
            val marker=File(out,"geometry-checkpoint.json")
            val record=JSONObject(marker.readText());record.remove("representation");marker.writeText(record.toString())
            assertFalse(MappingSurfaceCheckpoint.valid(mission,inputs,out,{}))
        }finally{out.deleteRecursively()}
    }
}

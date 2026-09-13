package org.ncssar.rid2caltopo.video.surface
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
class SurfacePreparedSetTest {
    private val root=File(if(File("../test-fixtures").exists()) "../test-fixtures/aol/prepared-set" else "test-fixtures/aol/prepared-set")
    private fun index()=JSONObject(File(root,"index.json").readText())
    @Test fun validatesSharedFixtureAndPreservesOriginalAge() {
        val index=index();SurfacePreparedSet.validate(index) { File(root,it).readBytes() }
        val stamp=index.getLong("preparedAtEpochMs")
        assertTrue(SurfacePreparedSet.fresh(stamp,stamp+999,1000))
        assertFalse(SurfacePreparedSet.fresh(stamp,stamp+1000,1000))
        assertFalse(SurfacePreparedSet.fresh(stamp,stamp-1,1000))
        assertFalse(SurfacePreparedSet.fresh(0,stamp,1000))
    }
    @Test fun coverageIncludesRequiredMarginAndRejectsPartialOverlap() {
        assertTrue(SurfacePreparedSet.contains(524,524,-200.0,-200.0,200.0,200.0))
        assertTrue(SurfacePreparedSet.contains(524,524,-100.0,-100.0,100.0,100.0))
        assertFalse(SurfacePreparedSet.contains(524,524,-200.0,-200.0,201.0,200.0))
        assertFalse(SurfacePreparedSet.contains(400,400,-200.0,-200.0,200.0,200.0))
    }
    @Test fun rejectsMissingCorruptAndMisplacedCores() {
        assertThrows(Exception::class.java) { SurfacePreparedSet.validate(index()) { error("Missing tile") } }
        assertThrows(Exception::class.java) { SurfacePreparedSet.validate(index()) { byteArrayOf(1,2,3) } }
        val wrong=index().put("width",162)
        assertThrows(Exception::class.java) { SurfacePreparedSet.validate(wrong) { File(root,it).readBytes() } }
        val unsafe=index();unsafe.getJSONArray("entries").getJSONObject(0).put("file","../tile.aol")
        assertThrows(Exception::class.java) { SurfacePreparedSet.validate(unsafe) { error("Must reject before reading") } }
    }
}

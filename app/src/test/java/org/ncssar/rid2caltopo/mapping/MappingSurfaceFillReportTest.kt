package org.ncssar.rid2caltopo.mapping

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class MappingSurfaceFillReportTest {
    private val assignment=MappingSurfaceCoverage(listOf(0.0 to 0.0,10.0 to 0.0,10.0 to 10.0,0.0 to 10.0))

    private fun mask(cells:Int,estimated:Set<Int>)=ByteArray((cells+7)/8).also{m->estimated.forEach{i->m[i shr 3]=(m[i shr 3].toInt() or (1 shl (i and 7))).toByte()}}

    @Test fun estimatedCellsCountOnlyMaskBitsInsideTheAssignment() {
        // 30x30 grid at 1 m starting at (-10,-10); assignment spans cells 10..20 in each axis.
        val w=30;val h=30
        val inside=setOf(12*w+12,15*w+11,10*w+10)
        val outside=setOf(0,5*w+5,25*w+25)
        val m=mask(w*h,inside+outside)
        assertEquals(inside.size,assignment.estimatedCells(m,w,h,-10.0,-10.0,1.0))
        assertEquals(0,assignment.estimatedCells(mask(w*h,emptySet()),w,h,-10.0,-10.0,1.0))
    }

    @Test fun planeHeightIsMedianOfFiniteHeights() {
        assertEquals(3.0,MappingSurfaceFill.planeHeight(floatArrayOf(Float.NaN,1f,5f,3f,Float.NaN)),0.0)
        assertTrue(MappingSurfaceFill.planeHeight(FloatArray(4){Float.NaN}).isNaN())
    }

    @Test fun reportFieldsPresentOnlyWhenFillRan() {
        val report=JSONObject().put("estimatedFillFraction",.9).put("estimatedFillCells",7)
        val fill=MappingSurfaceFill.Result(FloatArray(4),ByteArray(1),42,25.0)
        MappingSurfaceFill.putReport(report,fill,10,100)
        assertTrue(report.getBoolean("fillUnknown2DAreas"))
        assertEquals(42,report.getInt("estimatedFillCells"));assertEquals(10,report.getInt("estimatedFillAssignmentCells"))
        assertEquals(.1,report.getDouble("estimatedFillFraction"),1e-12);assertEquals(25.0,report.getDouble("fillRadiusMeters"),0.0)
        MappingSurfaceFill.putReport(report,null,0,100)
        assertFalse(report.getBoolean("fillUnknown2DAreas"))
        listOf("estimatedFillCells","estimatedFillAssignmentCells","estimatedFillFraction","fillRadiusMeters").forEach{assertFalse(it,report.has(it))}
    }

    @Test fun percentLabelNeverRoundsNonzeroToZero() {
        assertEquals("<1%",MappingSurfaceFill.percentLabel(.004));assertEquals("12%",MappingSurfaceFill.percentLabel(.12));assertEquals("0%",MappingSurfaceFill.percentLabel(0.0))
    }

    @Test fun maskIsWrittenAtomicallyAndRemoved() {
        val root=Files.createTempDirectory("fill").toFile()
        try {
            val dir=File(root,"surface")
            MappingSurfaceFill.writeMask(dir,byteArrayOf(1,2,3))
            assertArrayEquals(byteArrayOf(1,2,3),File(dir,MappingSurfaceFill.MASK_NAME).readBytes())
            MappingSurfaceFill.writeMask(dir,byteArrayOf(9))
            assertArrayEquals(byteArrayOf(9),File(dir,MappingSurfaceFill.MASK_NAME).readBytes())
            assertEquals(listOf(MappingSurfaceFill.MASK_NAME),dir.list()!!.toList())
            MappingSurfaceFill.removeMask(dir);assertFalse(File(dir,MappingSurfaceFill.MASK_NAME).exists())
        } finally {root.deleteRecursively()}
    }

    @Test fun layerAndGeoTiffShowEstimatedShareWhenPresent() {
        assertNull(MappingLayer.fillSummary(Double.NaN))
        assertEquals("Estimated (no measured depth): 12%",MappingLayer.fillSummary(.12))
        assertEquals("Estimated (no measured depth): <1%",MappingLayer.fillSummary(.004))
        val description=MappingGeoTiff.imageDescription(.123)!!
        assertTrue(description.contains("12.3%"));assertTrue(description.all{it.code in 32..126})
        assertNull(MappingGeoTiff.imageDescription(Double.NaN))
    }

    @Test fun geoTiffCarriesImageDescriptionTag() {
        val root=Files.createTempDirectory("geotiff-description").toFile()
        try {
            File(root,"14/2600/6300.png").apply{parentFile.mkdirs();writeBytes(byteArrayOf())}
            val plan=MappingGeoTiff.plan(root,14,14);val file=File(root,"map.tif")
            val text=MappingGeoTiff.imageDescription(.25)!!
            MappingGeoTiff.write(file,plan,{_,_->null},description=text)
            val b=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val tags=(0 until b.getShort(8).toInt()).associate{val i=10+it*12;(b.getShort(i).toInt() and 65535) to i}
            val ids=tags.keys.toList();assertEquals(ids.sorted(),ids)
            val i=tags.getValue(270);assertEquals(2,b.getShort(i+2).toInt())
            val count=b.getInt(i+4);val offset=b.getInt(i+8);assertEquals(0,offset%2)
            val bytes=ByteArray(count).also{b.position(offset);b.get(it)}
            assertEquals(text,String(bytes,Charsets.US_ASCII).trimEnd('\u0000'))
            val plain=File(root,"plain.tif");MappingGeoTiff.write(plain,plan,{_,_->null})
            assertFalse(ByteBuffer.wrap(plain.readBytes()).order(ByteOrder.LITTLE_ENDIAN).let{p->(0 until p.getShort(8).toInt()).any{(p.getShort(10+it*12).toInt() and 65535)==270}})
        } finally {root.deleteRecursively()}
    }
}

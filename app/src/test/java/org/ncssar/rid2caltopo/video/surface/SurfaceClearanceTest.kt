package org.ncssar.rid2caltopo.video.surface
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class SurfaceClearanceTest {
    @Test fun pointAolDoesNotIncludeNearbyPeaks() {
        val p = fixture("complete")
        val (lat, lon) = p.coordinate(10.0, 0.0)
        val point = AolState.calculate(p, lat, lon, 39.0, -121.0, 15.24, pointOnly = true)
        assertEquals(50.0, point.feet!!, 0.001)
        assertTrue(AolState.calculate(p, lat, lon, 39.0, -121.0, 15.24).feet!! < 0)
        assertNull(p.surfaceAt(0.0, 0.0))
        assertNull(AolState.calculate(p,lat,lon,39.0,-121.0,null,pointOnly=true).feet)
        assertNotNull(AolState.calculate(fixture("hole"),39.0,-121.0,39.0,-121.0,15.24,pointOnly=true).feet)
    }

    @Test fun roundedZeroHasNoNegativeSign() {
        for (value in listOf(-0.49, -0.0, 0.0, 0.49)) assertEquals("0'", measurementLabel(value, suffix = "'"))
        assertEquals("-1'", measurementLabel(-0.6, suffix = "'"))
        assertEquals("POS?", measurementLabel(-0.1, MeasurementStatus.Stale))
        assertEquals("Unk", measurementLabel(null))
    }

    private fun fixture(name:String):SurfacePackage {
        val root=if(File("../test-fixtures").exists()) File("../test-fixtures") else File("test-fixtures")
        return SurfacePackage.decode(File(root,"aol/$name.aol").readBytes())
    }
    @Test fun signedClearanceAndDisk() {
        val p=fixture("complete");val a=p.disk(39.0,-121.0)
        assertTrue(a.complete);assertEquals(312.42,a.peak!!.elevation,0.001)
        assertEquals(500.0,p.disk(39.0,-121.0,66.0).peak!!.elevation,0.001)
        for((h,expected) in listOf(0.0 to -25.0,7.62 to 0.0,15.24 to 25.0))
            assertEquals(expected,AolState.calculate(p,39.0,-121.0,39.0,-121.0,h).feet!!,0.001)
        assertNull(AolState.calculate(p,39.0,-121.0,39.0,-121.0,null).feet)
    }
    @Test fun holesEdgesAndBriefing() {
        assertFalse(fixture("hole").disk(39.0,-121.0).complete)
        val p=fixture("complete")
        assertFalse(p.disk(39.0007,-121.0).complete)
        val b=p.briefing(listOf(39.0 to -121.00005,39.0 to -120.99995),10.0,false)
        assertTrue(b.complete);assertEquals(312.42,b.peak!!.elevation,0.001)
        assertEquals(304.8,b.peak!!.ground!!,0.001)
    }
    @Test fun corruption() { assertThrows(Exception::class.java) { SurfacePackage.decode("bad".toByteArray()) } }
    @Test fun realNevadaCountyOfflineQuery() {
        val p=fixture("nevada-city-1m")
        val lat=39.24144906749628;val lon=-121.03819679099078
        val a=p.disk(lat,lon)
        assertTrue(a.complete);assertTrue(a.peak!!.elevation>a.peak!!.ground!!)
        assertNotNull(AolState.calculate(p,lat,lon,lat,lon,50.0).feet)
        val start=System.nanoTime()
        repeat(100){p.disk(lat,lon)}
        println("AOL real-data mean query ms: ${(System.nanoTime()-start)/100000000.0}; cells: ${a.checked}; peak: ${a.peak}")
    }
    @Test fun rejectChecksumReferenceAndUnits() {
        for(name in listOf("bad-checksum","bad-reference","bad-units"))
            assertThrows(Exception::class.java) { fixture(name) }
    }
}

package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test

class MappingRegionPlanTest {
    @Test fun acreageBoundCoversAssignmentWithSharedPhotosAtMercatorLatitude() {
        // A 2.5-acre irregular assignment can have a 3.75-acre bounding rectangle.
        val scale=.8;val width=150.0/scale;val height=3.75*MappingRegionPlan.ACRE/150/scale
        val bounds=MappingRegionPlan.Bounds(0.0,0.0,width,height)
        val photos=(0 until 236).map{MappingRegionPlan.Photo("p-$it",(it%20+.5)/20*width,(it/20+.5)/12*height)}
        val regions=MappingRegionPlan.build(photos,bounds,scale)
        assertEquals(2,regions.size)
        assertEquals(bounds.area(scale),regions.sumOf{it.bounds!!.area(scale)},1e-6)
        assertTrue(regions.all{it.bounds!!.area(scale)<=MappingRegionPlan.MAX_CORE_ACRES*MappingRegionPlan.ACRE+1e-6})
        assertEquals(photos.size,regions.sumOf{it.core.size})
        assertTrue(regions[0].images.intersect(regions[1].images.toSet()).size>=6)
        assertEquals(regions,MappingRegionPlan.build(photos.reversed(),bounds,scale))
        assertNull(MappingRegionPlan.geometrySource(regions,1))
    }
    @Test fun areaLimitAlsoSplitsFlightsBelowThePhotoLimit() {
        val bounds=MappingRegionPlan.Bounds(0.0,0.0,100.0,2.5*MappingRegionPlan.ACRE/100)
        val photos=(0 until 100).map{MappingRegionPlan.Photo("p-$it",(it%10+.5)*10,(it/10+.5)*10)}
        val regions=MappingRegionPlan.build(photos,bounds)
        assertEquals(2,regions.size)
        assertEquals(0,MappingRegionPlan.geometrySource(regions,1))
    }
    @Test fun largeFlightsRetainEveryCoreAndBoundWorkingSet() {
        val photos=(0 until 1000).map{MappingRegionPlan.Photo("photo-$it",(it%25)*8.0,(it/25)*8.0)}
        val plan=MappingRegionPlan.build(photos)
        assertEquals(photos.map{it.digest}.toSet(),plan.flatMap{it.core}.map{it.digest}.toSet())
        assertEquals(photos.size,plan.sumOf{it.core.size})
        assertTrue(plan.all{it.images.size<=192&&it.core.size<=128&&it.images.containsAll(it.core)})
        assertTrue(plan.all { a->plan.any { b->a!==b&&a.images.intersect(b.images.toSet()).size>=6 } })
        assertEquals(plan,MappingRegionPlan.build(photos.reversed()))
    }
    @Test fun thresholdUsesOneRegionUntilBoundIsExceeded() {
        val photos=(0 until 193).map{MappingRegionPlan.Photo("p-$it",it.toDouble(),0.0)}
        assertEquals(1,MappingRegionPlan.build(photos.take(192)).size)
        assertTrue(MappingRegionPlan.build(photos).size>1)
    }
    @Test fun invalidCoordinatesAndDuplicateOriginalsAreRejected() {
        val photos=(0..3).map{MappingRegionPlan.Photo("p-$it",it.toDouble(),0.0)}
        assertThrows(IllegalArgumentException::class.java){MappingRegionPlan.build(photos+photos.first())}
        assertThrows(IllegalArgumentException::class.java){MappingRegionPlan.build(photos+MappingRegionPlan.Photo("nan",Double.NaN,0.0))}
    }
}

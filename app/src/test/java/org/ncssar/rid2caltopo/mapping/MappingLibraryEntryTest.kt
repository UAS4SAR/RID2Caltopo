package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test

class MappingLibraryEntryTest {
    private fun entry(id:String,capture:String="",planned:String="")=MappingLibraryEntry(id,id,capture,planned,true,0,false,false,"",null,0.0 to 0.0)
    @Test fun importedPhotoCountDoesNotClaimLocalOriginals() {
        val layer=MappingLayer("id","incident","Assignment","http://localhost/{Z}/{X}/{Y}.png",14,20,photoCount=396)
        val imported=entry("import").copy(photoCount=396,hasPlan=false,has2D=true,layer=layer)
        assertTrue(imported.statusLabel.contains("396 photos used in map"))
        assertTrue(imported.statusLabel.contains("Originals: not stored here"))
        assertTrue(entry("flight").copy(photoCount=396).statusLabel.contains("396 originals on device"))
    }
    @Test fun archiveNameUsesAssignmentAndSanitizesPaths() {
        assertEquals("Assignment 7_2D_map.zip",MappingArchiveName.fileName("Assignment 7"))
        assertEquals("_path_name_2D_map.zip",MappingArchiveName.fileName("../path/name"))
        assertEquals("Map_2D_map.zip",MappingArchiveName.fileName(" "))
    }
    @Test fun sortsByCaptureThenPlanningWithUnknownLastInBothDirections() {
        val entries=listOf(entry("unknown"),entry("older","2026:10:11 17:21:00"),entry("newer","2026-10-12T13:00:00"),entry("plan",planned="2020-01-01T12:00:00Z"))
        assertEquals(listOf("newer","older","plan","unknown"),MappingLibraryEntry.sorted(entries,true).map{it.id})
        assertEquals(listOf("plan","older","newer","unknown"),MappingLibraryEntry.sorted(entries,false).map{it.id})
    }
    @Test fun unflownMissionNeverLabelsPlanningTimeAsCapture() {
        val plan=entry("plan",planned="2026-10-12T12:00:00Z")
        assertTrue(plan.timeLabel.startsWith("Not captured · planned"))
        assertTrue(plan.copy(photoCount=3).timeLabel.startsWith("Capture time unavailable"))
        assertTrue(plan.copy(capturedAt="2026:10:11 17:21:00").timeLabel.contains("Oct 11, 2026 17:21"))
    }
    @Test fun distanceIsToAreaCenterAndUnavailableIsExplicit() {
        val value=entry("map")
        assertEquals("Distance: current location unavailable",value.distanceLabel(null))
        assertEquals("Distance to area center: 0 ft",value.distanceLabel(0.0 to 0.0))
        assertTrue(value.distanceLabel(0.0 to 1.0).contains("69.09 mi"))
        assertEquals("Distance: mission location unavailable",value.copy(center=null).distanceLabel(0.0 to 0.0))
    }
    @Test fun olderAndManualLocationsRemainUsefulButAreClearlyLabeled() {
        val old=MappingLibraryLocation.from(39.0,-121.0,600000,false)!!
        assertEquals(39.0 to -121.0,old.coordinates)
        assertEquals("last known location, 10 min old",old.note)
        assertEquals("manual map location",MappingLibraryLocation.from(39.0,-121.0,null,true)!!.note)
        assertEquals("",MappingLibraryLocation.from(39.0,-121.0,10000,false)!!.note)
        assertTrue(MappingLibraryLocation.from(39.0,-121.0,-1,false)!!.note.contains("age unavailable"))
        assertNull(MappingLibraryLocation.from(Double.NaN,-121.0,0,false))
        assertNull(MappingLibraryLocation.from(91.0,-121.0,0,false))
    }

}

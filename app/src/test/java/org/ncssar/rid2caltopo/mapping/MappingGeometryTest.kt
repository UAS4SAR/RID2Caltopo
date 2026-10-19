package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.DataOutputStream

class MappingGeometryTest {
    @Test fun yawIsAbsoluteAndImageSizeChangesGroundResolution(){
        val pose=MappingPose(39.0,-121.0,57.98,90.0,"2026:10:11 12:22:58",82.0)
        assertEquals(0.02,MappingGeometry.gsd(pose,4032,3024),0.0001)
        assertEquals(0.01,MappingGeometry.gsd(pose,8064,6048),0.0001)
        val basis=MappingGeometry.basis(pose,4032,3024)
        assertEquals(0.0,basis[0],1e-8);assertTrue(basis[1]<0);assertTrue(basis[2]<0)
        assertEquals(39.0,MappingGeometry.latitude(MappingGeometry.north(39.0)),1e-9)
        assertEquals(-121.0,MappingGeometry.longitude(MappingGeometry.east(-121.0)),1e-9)
        assertEquals(23,MappingGeometry.zoom(0.02,39.0))
    }
    private fun jpeg(pitch:String="-90.0",yaw:String="90",roll:String="0",extra:String=""):File {
        val file=File.createTempFile("mapping-pose",".jpg")
        val xmp="<rdf drone-dji:GpsLatitude='39.0' drone-dji:GpsLongitude='-121.0' drone-dji:RelativeAltitude='58' drone-dji:GimbalYawDegree='$yaw' drone-dji:GimbalRollDegree='$roll' drone-dji:FlightYawDegree='90' drone-dji:GimbalPitchDegree='$pitch' exif:DateTimeOriginal='2026:10:11 12:22:58' $extra/>".toByteArray()
        DataOutputStream(file.outputStream()).use{it.writeShort(0xffd8);it.writeShort(0xffe1);it.writeShort(xmp.size+2);it.write(xmp);it.writeShort(0xffd9)}
        return file
    }
    @Test fun readsPoseWithoutAddingFlightHeading(){val file=jpeg();try{val pose=MappingPose.read(file,82.0);assertEquals(90.0,pose.yaw,0.0);assertEquals("2026:10:11 12:22:58",pose.capturedAt)}finally{file.delete()}}
    @Test fun nadirEulerRollDoesNotReverseTheGroundFootprint(){val a=jpeg();val b=jpeg(yaw="-90",roll="180");try{
        assertArrayEquals(MappingGeometry.basis(MappingPose.read(a,82.0),4032,3024),MappingGeometry.basis(MappingPose.read(b,82.0),4032,3024),1e-10)
    }finally{a.delete();b.delete()}}
    @Test fun rejectsObliqueAndInvalidHeight(){val file=jpeg("-45");try{assertThrows(IllegalArgumentException::class.java){MappingPose.read(file,82.0)}}finally{file.delete()}}
    @Test fun keepsRtkPrecisionSeparateFromMissingMetadata(){
        val plain=jpeg();val fixed=jpeg(extra="drone-dji:RtkFlag='50' drone-dji:AbsoluteAltitude='577.453' drone-dji:RtkStdLon='0.00340' drone-dji:RtkStdLat='0.00238' drone-dji:RtkStdHgt='0.00721'")
        try{assertNull(MappingPose.read(plain,82.0).rtkFlag);assertNull(MappingPose.read(plain,82.0).rtkSigmaEast)
            val p=MappingPose.read(fixed,82.0);assertEquals(50,p.rtkFlag);assertEquals(577.453,p.absoluteAltitude!!,.0001);assertEquals(.0034,p.rtkSigmaEast!!,.00001)
        }finally{plain.delete();fixed.delete()}
    }
    @Test fun surfaceInputRetainsObliqueEulerWhileFlatStitchingRejectsIt() {
        val file=jpeg("-60",yaw="35",roll="12")
        try {
            assertThrows(IllegalArgumentException::class.java){MappingPose.read(file,82.0)}
            val p=MappingPose.read(file,82.0,allowOblique=true)
            assertEquals(-60.0,p.pitch,0.0);assertEquals(35.0,p.yaw,0.0);assertEquals(12.0,p.roll,0.0)
            assertThrows(IllegalArgumentException::class.java){MappingGeometry.basis(p,4032,3024)}
        } finally {file.delete()}
    }

}

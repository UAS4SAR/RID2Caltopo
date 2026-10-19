package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream
import java.nio.file.Files

class DjiOriginalCameraTest {
    @Test fun readsOnlyJpegMetadataAndPreservesOriginalBytes() {
        for ((source,sensor) in listOf("InfraredCamera" to "thermal","WideCamera" to "visible-wide","ZoomCamera" to "visible-zoom")) {
            val file=Files.createTempFile("r2c-camera-",".jpg").toFile()
            try {
                DataOutputStream(file.outputStream()).use { output ->
                    output.writeShort(0xffd8)
                    val xmp="http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:Description drone-dji:ImageSource=\"$source\"/></x:xmpmeta>".toByteArray()
                    output.writeShort(0xffe1);output.writeShort(xmp.size+2);output.write(xmp)
                    output.writeShort(0xffda);output.write(ByteArray(100))
                }
                val bytes=file.readBytes()
                assertEquals(sensor,DjiOriginalCamera.sensor(file))
                assertArrayEquals(bytes,file.readBytes())
            }finally{file.delete()}
        }
    }
    @Test fun ignoresCameraTextInImageDataAndHandlesMissingOrTruncatedMetadata() {
        val file=Files.createTempFile("r2c-camera-",".jpg").toFile()
        try {
            for(bytes in listOf(byteArrayOf(0xff.toByte(),0xd8.toByte()),byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xda.toByte())+"drone-dji:ImageSource=\"InfraredCamera\"".toByteArray())) {
                file.writeBytes(bytes);assertEquals("unknown",DjiOriginalCamera.sensor(file))
            }
        }finally{file.delete()}
    }
}

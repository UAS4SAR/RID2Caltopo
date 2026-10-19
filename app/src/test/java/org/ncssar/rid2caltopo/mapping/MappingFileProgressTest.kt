package org.ncssar.rid2caltopo.mapping

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class MappingFileProgressTest {
    @Test fun copyingReportsRealBytesAndPreservesContents() {
        val bytes=ByteArray(800000){(it%251).toByte()};val output=ByteArrayOutputStream();val updates=mutableListOf<Long>()
        MappingFileProgress.transfer(ByteArrayInputStream(bytes),output,{}){updates.add(it)}
        assertArrayEquals(bytes,output.toByteArray());assertEquals(bytes.size.toLong(),updates.last())
        assertTrue(updates.size>1);assertTrue(updates.zipWithNext().all{it.first<it.second})
    }
    @Test fun cancellationStopsWithinOneBuffer() {
        val output=ByteArrayOutputStream();var cancelled=false
        try { MappingFileProgress.transfer(ByteArrayInputStream(ByteArray(1000000)),output,{if(output.size()>0)throw CancellationException()}){} }
        catch(_:CancellationException){cancelled=true}
        assertTrue(cancelled);assertEquals(256*1024,output.size())
    }
    @Test fun hashReportsBytesWithoutChangingFile() {
        val file=File.createTempFile("mapping-progress",".bin")
        try {val bytes=ByteArray(600000){(it%239).toByte()};file.writeBytes(bytes);var last=0L
            val hash=MappingFileProgress.sha(file,{}){last=it}
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)},hash)
            assertEquals(bytes.size.toLong(),last);assertArrayEquals(bytes,file.readBytes())
        }finally{file.delete()}
    }
}

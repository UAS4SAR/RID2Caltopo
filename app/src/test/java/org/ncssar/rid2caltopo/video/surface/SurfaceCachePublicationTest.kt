package org.ncssar.rid2caltopo.video.surface

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SurfaceCachePublicationTest {
    private val fixture = File(if (File("../test-fixtures").exists()) "../test-fixtures/aol/prepared-set" else "test-fixtures/aol/prepared-set")
    @Test fun publishesVerifiedSetWithoutConsumingSourceAndRetriesSafely() {
        val dir = Files.createTempDirectory("aol-cache").toFile()
        try {
            val target = SurfaceCacheFile.local(dir).child("surface_v1").child("sets").child("abc")
            SurfaceCachePublication.publishSet(fixture, target)
            SurfaceCachePublication.publishSet(fixture, target)
            assertArrayEquals(fixture.resolve("index.json").readBytes(), target.child("index.json").readBytes())
            SurfacePreparedSet.validate(org.json.JSONObject(target.child("index.json").readText())) { target.child(it).readBytes() }
            assertTrue(fixture.resolve("index.json").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun incompleteCopyCanResumeAndCorruptCommittedSetIsNotAccepted() {
        val dir = Files.createTempDirectory("aol-cache").toFile()
        try {
            val target = SurfaceCacheFile.local(dir)
            target.child("tile-0-0.aol").writeBytes(byteArrayOf(0))
            assertFalse(target.child("index.json").exists())
            target.child("index.json").writeBytes("{partial".toByteArray())
            SurfaceCachePublication.publishSet(fixture, target)
            target.child("tile-0-0.aol").writeBytes(byteArrayOf(0))
            assertThrows(Exception::class.java) { SurfaceCachePublication.publishSet(fixture, target) }
            assertTrue(fixture.resolve("tile-0-0.aol").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsInvalidSourceBeforePublishingAndRejectsPathTraversal() {
        val dir = Files.createTempDirectory("aol-cache").toFile()
        try {
            val source = dir.resolve("source"); source.mkdirs()
            fixture.copyRecursively(source, overwrite=true)
            source.resolve("tile-0-0.aol").writeBytes(byteArrayOf(0))
            val target = SurfaceCacheFile.local(dir.resolve("target"))
            assertThrows(Exception::class.java) { SurfaceCachePublication.publishSet(source, target) }
            assertFalse(target.child("index.json").exists())
            assertThrows(IllegalArgumentException::class.java) { target.child("../escape") }
        } finally { dir.deleteRecursively() }
    }
}

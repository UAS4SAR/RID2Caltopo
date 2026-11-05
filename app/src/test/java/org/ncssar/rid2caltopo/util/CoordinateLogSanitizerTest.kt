package org.ncssar.rid2caltopo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoordinateLogSanitizerTest {
    @Test
    fun redactsLabeledCoordinatesAndDegreePairs() {
        val line = CoordinateLogSanitizer.redact(
            "onSnapshotCaptured droneLat=39.153017 droneLng=-121.133046 alt=100.0 pair=39.15301, -121.13304"
        )
        assertFalse(line.contains("39.153"))
        assertFalse(line.contains("-121.133"))
        assertTrue(line.contains("droneLat=[redacted]"))
        assertTrue(line.contains("alt=100.0"))
    }

    @Test
    fun leavesAltitudeAndShortNumbers() {
        val line = CoordinateLogSanitizer.redact("zoom=14.25 altM=120.5 count=3")
        assertEquals("zoom=14.25 altM=120.5 count=3", line)
    }

    @Test
    fun sourceLogStatementsDoNotFormatPreciseCoordinates() {
        val root = repoRoot()
        val pattern = Regex(
            """(lat|lng|lon|latitude|longitude|deltaLat|deltaLon)\s*=\s*%\."""
        )
        val hits = mutableListOf<String>()
        listOf(File(root, "app/src/main"), File(root, "apple")).forEach { dir ->
            dir.walkTopDown().filter { file ->
                file.isFile && file.extension in setOf("kt", "java", "swift") &&
                    !file.path.contains("/Tests/") && !file.path.contains("/src/test/")
            }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (pattern.containsMatchIn(line)) hits.add("${file.path}:${index + 1}")
                }
            }
        }
        assertTrue(hits.joinToString("\n"), hits.isEmpty())
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "app/src/main").isDirectory && File(dir, "apple").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        return dir
    }
}

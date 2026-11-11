package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class OwnedFlightFileNameTest {
    private val pacific = ZoneId.of("America/Los_Angeles")

    @Test
    fun round11FileIsNamedFromTheGrant() {
        val shadowAt = at(17, 39, 17)
        val grantAt = at(17, 41, 21)
        val directory = File.createTempFile("grant-name", null).also {
            assertTrue(it.delete())
            assertTrue(it.mkdirs())
        }
        try {
            val shadowName = OperatorArchiveFilename.track("1581F8HGX255S00A0FZT", shadowAt, pacific)
            val shadow = File(directory, shadowName)
            shadow.writeText("{\"kept\":true}")
            File(directory, FlightArchiveRebuild.kmzName(shadowName)).writeBytes(byteArrayOf(1, 2, 3))

            val grantName = OwnedFlightFileName.adoptGrantName(
                directory, "1581F8HGX255S00A0FZT", shadowAt, grantAt, pacific)

            assertTrue(grantName.contains("174121"))
            assertFalse(grantName.contains("173917"))
            assertEquals(OperatorArchiveFilename.track("1581F8HGX255S00A0FZT", grantAt, pacific), grantName)
            assertFalse(shadow.exists())
            assertEquals("{\"kept\":true}", File(directory, grantName).readText())
            assertFalse(File(directory, FlightArchiveRebuild.kmzName(shadowName)).exists())
            assertTrue(File(directory, FlightArchiveRebuild.kmzName(grantName)).length() == 3L)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun adoptLeavesAnExistingGrantFileUntouched() {
        val shadowAt = at(17, 39, 17)
        val grantAt = at(17, 41, 21)
        val directory = File.createTempFile("grant-exists", null).also {
            assertTrue(it.delete())
            assertTrue(it.mkdirs())
        }
        try {
            val grantName = OperatorArchiveFilename.track("RID", grantAt, pacific)
            File(directory, grantName).writeText("owned")
            val shadowName = OperatorArchiveFilename.track("RID", shadowAt, pacific)
            File(directory, shadowName).writeText("shadow")
            OwnedFlightFileName.adoptGrantName(directory, "RID", shadowAt, grantAt, pacific)
            assertEquals("owned", File(directory, grantName).readText())
            assertEquals("shadow", File(directory, shadowName).readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun at(hour: Int, minute: Int, second: Int): Long =
        ZonedDateTime.of(2026, 11, 10, hour, minute, second, 0, pacific).toInstant().toEpochMilli()
}
